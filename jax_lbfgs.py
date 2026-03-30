"""
Pure-JAX L-BFGS-B optimizer.

Keeps the entire optimization loop on-device (no numpy roundtrips).
Uses projected gradient descent with bound clipping and backtracking
Armijo line search.  The outer iteration is a Python loop so callers
can snapshot / report progress between steps.
"""

from functools import partial

import jax
import jax.numpy as jnp


# ── two-loop recursion (Nocedal & Wright, Algorithm 7.4) ────────────
# History is stored most-recent-first: index 0 = newest pair.

@partial(jax.jit, static_argnames=("m",))
def _two_loop(grad, s_hist, y_hist, rho_hist, n_stored, *, m):
    q = grad

    # Backward pass (most recent → oldest)
    alphas = jnp.zeros(m, dtype=grad.dtype)
    for i in range(m):
        active = n_stored > i
        a = jnp.where(active, rho_hist[i] * jnp.dot(s_hist[i], q), 0.0)
        alphas = alphas.at[i].set(a)
        q = jnp.where(active, q - a * y_hist[i], q)

    # Initial Hessian scaling  γ = sᵀy / yᵀy
    gamma = jnp.where(
        n_stored > 0,
        jnp.dot(s_hist[0], y_hist[0]) / (jnp.dot(y_hist[0], y_hist[0]) + 1e-30),
        1.0,
    )
    r = gamma * q

    # Forward pass (oldest → most recent)
    for i in range(m - 1, -1, -1):
        active = n_stored > i
        beta = jnp.where(active, rho_hist[i] * jnp.dot(y_hist[i], r), 0.0)
        r = jnp.where(active, r + (alphas[i] - beta) * s_hist[i], r)

    return -r


# ── single optimisation step (direction + line-search + history) ─────

def _make_step_fn(value_and_grad_fn, history_size):
    """Return a JIT-compiled function that executes one full L-BFGS-B step."""

    @jax.jit
    def step(x, val, grad, s_hist, y_hist, rho_hist, n_stored, lb, ub):
        # 1. Search direction via L-BFGS two-loop recursion
        d = _two_loop(grad, s_hist, y_hist, rho_hist, n_stored, m=history_size)

        # 2. Project: zero components that push into active bounds
        at_lb = (x <= lb + 1e-10) & (d < 0)
        at_ub = (x >= ub - 1e-10) & (d > 0)
        d = jnp.where(at_lb | at_ub, 0.0, d)

        # 3. Fallback to projected steepest descent if not a descent direction
        slope = jnp.dot(grad, d)
        neg_g = -grad
        neg_g = jnp.where((x <= lb + 1e-10) & (neg_g < 0), 0.0, neg_g)
        neg_g = jnp.where((x >= ub - 1e-10) & (neg_g > 0), 0.0, neg_g)
        d = jnp.where(slope >= 0, neg_g, d)
        slope = jnp.where(slope >= 0, jnp.dot(grad, neg_g), slope)

        # 4. Backtracking Armijo line search (while_loop, memory-efficient)
        c1 = jnp.float32(1e-4)

        def ls_cond(state):
            alpha, _f, _g, n, done = state
            return (~done) & (n < 20) & (alpha > 1e-15)

        def ls_body(state):
            alpha, _f, _g, n, _done = state
            alpha = alpha * 0.5
            x_try = jnp.clip(x + alpha * d, lb, ub)
            f_try, g_try = value_and_grad_fn(x_try)
            ok = f_try <= val + c1 * alpha * slope
            return (alpha, f_try, g_try, n + 1, ok)

        # Initial trial at α=1
        x1 = jnp.clip(x + d, lb, ub)
        f1, g1 = value_and_grad_fn(x1)
        ok1 = f1 <= val + c1 * slope

        init = (jnp.float32(1.0), f1, g1, jnp.int32(1), ok1)
        alpha, f_new, g_new, _n, found = jax.lax.while_loop(ls_cond, ls_body, init)

        x_new = jnp.clip(x + alpha * d, lb, ub)

        # 5. Update L-BFGS history (only if curvature condition met)
        s = x_new - x
        y = g_new - grad
        sy = jnp.dot(s, y)
        should_update = (sy > 1e-10) & found

        s_new = jnp.where(should_update, jnp.roll(s_hist, 1, axis=0).at[0].set(s), s_hist)
        y_new = jnp.where(should_update, jnp.roll(y_hist, 1, axis=0).at[0].set(y), y_hist)
        rho_new = jnp.where(
            should_update,
            jnp.roll(rho_hist, 1).at[0].set(1.0 / sy),
            rho_hist,
        )
        n_new = jnp.where(should_update, jnp.minimum(n_stored + 1, history_size), n_stored)

        return x_new, f_new, g_new, s_new, y_new, rho_new, n_new, alpha, found

    return step


# ── public API ───────────────────────────────────────────────────────

def minimize_lbfgsb(
    value_and_grad_fn,
    x0,
    bounds,
    maxiter=1000,
    history_size=10,
    gtol=1e-8,
    callback=None,
):
    """
    Minimise a scalar function with box constraints, entirely on-device.

    Parameters
    ----------
    value_and_grad_fn : callable
        ``x -> (value, grad)``  — should already be ``jax.jit``-compiled.
    x0 : jax array (float32)
        Initial point.
    bounds : (lower, upper)
        JAX float32 arrays, same shape as ``x0``.
    maxiter : int
        Maximum iterations.
    history_size : int
        Number of (s, y) correction pairs kept.
    gtol : float
        Convergence tolerance on projected-gradient ∞-norm.
    callback : callable, optional
        ``(iteration, x, value) -> should_stop``  (called every iter).

    Returns
    -------
    x_best, best_value, n_iters
    """
    lb, ub = bounds
    n = x0.shape[0]

    step_fn = _make_step_fn(value_and_grad_fn, history_size)

    # History buffers
    s_hist = jnp.zeros((history_size, n), dtype=jnp.float32)
    y_hist = jnp.zeros((history_size, n), dtype=jnp.float32)
    rho_hist = jnp.zeros(history_size, dtype=jnp.float32)
    n_stored = jnp.int32(0)

    x = jnp.asarray(x0, dtype=jnp.float32)
    val, grad = value_and_grad_fn(x)

    best_val = val
    best_x = x
    nit = 0

    for i in range(maxiter):
        # Projected-gradient convergence check (only sync once per iter)
        pg = jnp.where(
            (x <= lb + 1e-10) & (grad > 0), 0.0,
            jnp.where((x >= ub - 1e-10) & (grad < 0), 0.0, grad),
        )
        pg_norm = jnp.max(jnp.abs(pg))

        # Single compiled step: direction + line search + history update
        x, val, grad, s_hist, y_hist, rho_hist, n_stored, alpha, found = step_fn(
            x, val, grad, s_hist, y_hist, rho_hist, n_stored, lb, ub,
        )
        nit = i + 1

        improved = val < best_val
        best_val = jnp.where(improved, val, best_val)
        best_x = jnp.where(improved, x, best_x)

        # Callback (the only device→host sync per iteration)
        if callback is not None:
            should_stop = callback(nit, x, float(val))
            if should_stop:
                break

        # Convergence / line-search failure (pull scalars after callback
        # so the sync from float(val) above is already done)
        if float(pg_norm) < gtol:
            break
        if not bool(found):
            break

    return best_x, float(best_val), nit

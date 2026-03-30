from dataclasses import dataclass
from typing import Callable

import jax
import jax.numpy as jnp
import numpy as np
from scipy.optimize import minimize
from tqdm import tqdm

from jax_lbfgs import minimize_lbfgsb
from mri_fast import B1_MAX, GX_MAX, GZ_MAX


@dataclass
class AnnealStage:
    fraction: float          # fraction of total iterations for this stage
    rf_smooth_mul: float     # multiplier on rf_smooth_pen


DEFAULT_ANNEAL_SCHEDULE = [
    AnnealStage(fraction=0.40, rf_smooth_mul=10.0),
    AnnealStage(fraction=0.30, rf_smooth_mul=3.0),
    AnnealStage(fraction=0.30, rf_smooth_mul=1.0),
]


@dataclass
class SearchConfig:
    opt_steps: int = 1200
    snapshot_every: int = 25
    print_every: int = 25
    stall_patience: int = 0
    min_improvement: float = 0.0
    anneal_schedule: list[AnnealStage] | None = None


@dataclass
class MaskedOptimizationResult:
    x_full: np.ndarray
    nit: int
    nfev: int
    success: bool
    message: str
    snapshots: dict[int, np.ndarray]
    best_value: float


class OptimizationStopRequested(RuntimeError):
    pass


def flatten_ctrl_list(ctrl_list):
    return np.concatenate([np.asarray(c, dtype=np.float32).ravel() for c in ctrl_list]).astype(np.float32)


def split_ctrl_flat(flat_ctrl, segments):
    out = []
    idx = 0
    flat_ctrl = np.asarray(flat_ctrl, dtype=np.float32)
    for seg in segments:
        ns = seg["n_free"] + seg["n_pulse"]
        n_ctrl = seg.get("n_ctrl", 4)
        span = ns * n_ctrl
        out.append(flat_ctrl[idx:idx + span].reshape(ns, n_ctrl).astype(np.float32))
        idx += span
    return out


def flatten_mask_list(mask_list):
    return np.concatenate([np.asarray(m, dtype=bool).ravel() for m in mask_list])


def default_bounds_for_steps(n_total_steps, control_dim=4):
    per_step = [(-B1_MAX, B1_MAX), (-B1_MAX, B1_MAX), (-GX_MAX, GX_MAX), (-GZ_MAX, GZ_MAX)]
    if control_dim >= 5:
        per_step.append((0.0, 1.0))
    return per_step * n_total_steps


def optimize_masked_controls(
    value_and_grad,
    ctrl0_flat,
    free_mask_flat,
    bounds_full,
    config: SearchConfig | None = None,
    progress_fn: Callable[[int, np.ndarray, float], None] | None = None,
    stop_requested_fn: Callable[[], bool] | None = None,
    show_progress_bar: bool = True,
):
    config = config or SearchConfig()
    ctrl0_flat = np.asarray(ctrl0_flat, dtype=np.float32)
    free_mask_flat = np.asarray(free_mask_flat, dtype=bool).ravel()
    snapshots = {0: ctrl0_flat.copy()}

    if ctrl0_flat.shape != free_mask_flat.shape:
        raise ValueError("ctrl0_flat and free_mask_flat must have the same shape")

    free_idx = np.flatnonzero(free_mask_flat)
    if free_idx.size == 0:
        return MaskedOptimizationResult(
            x_full=ctrl0_flat.copy(),
            nit=0,
            nfev=0,
            success=True,
            message="No free control variables; skipped optimisation.",
            snapshots=snapshots,
            best_value=float("nan"),
        )

    scales_full = np.array(
        [max(abs(lo), abs(hi), 1e-12) for lo, hi in bounds_full],
        dtype=np.float32,
    )
    scale_free_np = scales_full[free_idx]
    x0_norm = (ctrl0_flat[free_idx] / scale_free_np).astype(np.float64)
    bounds = [
        (bounds_full[i][0] / scales_full[i], bounds_full[i][1] / scales_full[i])
        for i in free_idx
    ]

    # Build a JIT-compiled function that takes normalized free vars and returns
    # (value, grad_wrt_free_norm) entirely on-device, avoiding per-call copies.
    frozen_jax = jnp.asarray(ctrl0_flat, dtype=jnp.float32)
    free_idx_jax = jnp.asarray(free_idx, dtype=jnp.int32)
    scale_free_jax = jnp.asarray(scale_free_np, dtype=jnp.float32)

    @jax.jit
    def fg_jax(x_free_norm):
        x_full = frozen_jax.at[free_idx_jax].set(x_free_norm * scale_free_jax)
        val, grad_full = value_and_grad(x_full)
        grad_free = grad_full[free_idx_jax] * scale_free_jax
        return val, grad_free

    state = {
        "nit": 0,
        "best_value": None,
        "best_iter": 0,
        "best_x_norm": x0_norm.copy(),
    }
    bar = tqdm(total=config.opt_steps, desc="L-BFGS-B", unit="iter", leave=True) if show_progress_bar else None
    snapshot_every = max(config.snapshot_every, 1)
    print_every = max(config.print_every, 1)

    def merge_free_np(x_free_norm):
        x_full = ctrl0_flat.copy()
        x_full[free_idx] = np.asarray(x_free_norm, dtype=np.float32) * scale_free_np
        return x_full

    def fg(x_free_norm):
        x_jax = jnp.asarray(x_free_norm, dtype=jnp.float32)
        val, grad_free = fg_jax(x_jax)
        val = float(val)
        grad_out = np.asarray(grad_free, dtype=np.float64)
        if state["best_value"] is None or val < state["best_value"]:
            improvement = np.inf if state["best_value"] is None else float(state["best_value"] - val)
            state["best_value"] = val
            state["best_x_norm"] = np.asarray(x_free_norm, dtype=np.float64).copy()
            if improvement > float(config.min_improvement):
                state["best_iter"] = state["nit"]
        return val, grad_out

    def callback(xk_norm):
        state["nit"] += 1
        nit = state["nit"]
        if bar is not None:
            bar.update(1)
        need_snapshot = nit % snapshot_every == 0
        need_progress = progress_fn is not None and (print_every <= 1 or nit % print_every == 0)
        if need_snapshot or need_progress:
            x_full = merge_free_np(xk_norm)
            if need_snapshot:
                snapshots[nit] = x_full.copy()
            if need_progress:
                progress_fn(nit, x_full, float(state["best_value"] or 0))
        if bar is not None and state["best_value"] is not None:
            bar.set_postfix(score=float(-state["best_value"]))
        if config.stall_patience > 0 and (nit - state["best_iter"]) >= config.stall_patience:
            snapshots[nit] = merge_free_np(state["best_x_norm"])
            raise OptimizationStopRequested(
                f"No meaningful improvement for {config.stall_patience} iterations; returning best-so-far controls."
            )
        if stop_requested_fn is not None and stop_requested_fn():
            snapshots[nit] = merge_free_np(state["best_x_norm"])
            raise OptimizationStopRequested("Stop requested; returning best-so-far controls.")

    try:
        try:
            res = minimize(
                fg,
                x0_norm,
                method="L-BFGS-B",
                jac=True,
                bounds=bounds,
                options={"maxiter": config.opt_steps, "ftol": 1e-12, "gtol": 1e-8},
                callback=callback,
            )
        except OptimizationStopRequested as err:
            nit = int(state["nit"])
            if state["best_value"] is None:
                state["best_value"], _ = fg(x0_norm)
            res = type(
                "StoppedOptimizeResult",
                (),
                {
                    "x": state["best_x_norm"],
                    "nit": nit,
                    "nfev": nit,
                    "success": False,
                    "message": str(err),
                },
            )()
    finally:
        if bar is not None:
            bar.close()

    best_x_full = merge_free_np(state["best_x_norm"])
    x_full = merge_free_np(res.x)
    if state["best_value"] is None:
        state["best_value"], _ = fg(res.x)
        best_x_full = x_full.copy()
    snapshots[int(res.nit)] = best_x_full.copy()
    return MaskedOptimizationResult(
        x_full=best_x_full.astype(np.float32),
        nit=int(res.nit),
        nfev=int(res.nfev),
        success=bool(res.success),
        message=str(res.message),
        snapshots=snapshots,
        best_value=float(state["best_value"]),
    )


def optimize_masked_controls_jax(
    value_and_grad,
    ctrl0_flat,
    free_mask_flat,
    bounds_full,
    config: SearchConfig | None = None,
    progress_fn: Callable[[int, np.ndarray, float], None] | None = None,
    stop_requested_fn: Callable[[], bool] | None = None,
    show_progress_bar: bool = True,
):
    """Like optimize_masked_controls but uses pure-JAX L-BFGS-B (no scipy)."""
    config = config or SearchConfig()
    ctrl0_flat = np.asarray(ctrl0_flat, dtype=np.float32)
    free_mask_flat = np.asarray(free_mask_flat, dtype=bool).ravel()
    snapshots = {0: ctrl0_flat.copy()}

    if ctrl0_flat.shape != free_mask_flat.shape:
        raise ValueError("ctrl0_flat and free_mask_flat must have the same shape")

    free_idx = np.flatnonzero(free_mask_flat)
    if free_idx.size == 0:
        return MaskedOptimizationResult(
            x_full=ctrl0_flat.copy(), nit=0, nfev=0, success=True,
            message="No free variables.", snapshots=snapshots, best_value=float("nan"),
        )

    scales_full = np.array(
        [max(abs(lo), abs(hi), 1e-12) for lo, hi in bounds_full], dtype=np.float32,
    )
    scale_free_np = scales_full[free_idx]
    x0_norm = (ctrl0_flat[free_idx] / scale_free_np).astype(np.float32)

    lb = jnp.array([bounds_full[i][0] / scales_full[i] for i in free_idx], dtype=jnp.float32)
    ub = jnp.array([bounds_full[i][1] / scales_full[i] for i in free_idx], dtype=jnp.float32)

    # On-device merge + value_and_grad + grad extract
    frozen_jax = jnp.asarray(ctrl0_flat, dtype=jnp.float32)
    free_idx_jax = jnp.asarray(free_idx, dtype=jnp.int32)
    scale_free_jax = jnp.asarray(scale_free_np, dtype=jnp.float32)

    @jax.jit
    def fg_jax(x_free_norm):
        x_full = frozen_jax.at[free_idx_jax].set(x_free_norm * scale_free_jax)
        val, grad_full = value_and_grad(x_full)
        return val, grad_full[free_idx_jax] * scale_free_jax

    snapshot_every = max(config.snapshot_every, 1)
    print_every = max(config.print_every, 1)
    best_value = float("inf")
    best_x_norm = jnp.array(x0_norm)

    bar = tqdm(total=config.opt_steps, desc="L-BFGS-B", unit="iter", leave=True) if show_progress_bar else None

    def cb(nit, x_norm, val):
        nonlocal best_value, best_x_norm
        if val < best_value:
            best_value = val
            best_x_norm = x_norm
        if bar is not None:
            bar.update(1)
            bar.set_postfix(score=-best_value)
        need_snap = nit % snapshot_every == 0
        need_prog = progress_fn is not None and (print_every <= 1 or nit % print_every == 0)
        if need_snap or need_prog:
            x_full = ctrl0_flat.copy()
            x_full[free_idx] = np.asarray(x_norm, dtype=np.float32) * scale_free_np
            if need_snap:
                snapshots[nit] = x_full.copy()
            if need_prog:
                progress_fn(nit, x_full, best_value)
        if stop_requested_fn is not None and stop_requested_fn():
            return True
        return False

    try:
        x_best, best_val, nit = minimize_lbfgsb(
            fg_jax, jnp.array(x0_norm), (lb, ub),
            maxiter=config.opt_steps, history_size=20, gtol=1e-8, callback=cb,
        )
    finally:
        if bar is not None:
            bar.close()

    best_x_full = ctrl0_flat.copy()
    best_x_full[free_idx] = np.asarray(best_x_norm, dtype=np.float32) * scale_free_np
    snapshots[nit] = best_x_full.copy()

    return MaskedOptimizationResult(
        x_full=best_x_full.astype(np.float32),
        nit=nit, nfev=nit, success=True,
        message=f"Completed {nit} iterations",
        snapshots=snapshots, best_value=best_value,
    )


def optimize_annealed(
    vg_factory: Callable[[float], Callable],
    ctrl0_flat: np.ndarray,
    free_mask_flat: np.ndarray,
    bounds_full: list,
    config: SearchConfig | None = None,
    progress_fn: Callable[[int, np.ndarray, float], None] | None = None,
    stop_requested_fn: Callable[[], bool] | None = None,
    show_progress_bar: bool = True,
):
    """
    Multi-stage optimisation with smoothness annealing.

    vg_factory(rf_smooth_mul) -> value_and_grad callable.
    Runs L-BFGS-B in stages with decreasing rf_smooth_pen multiplier,
    warm-starting each stage from the previous best.
    """
    config = config or SearchConfig()
    schedule = config.anneal_schedule or DEFAULT_ANNEAL_SCHEDULE

    all_snapshots: dict[int, np.ndarray] = {}
    iter_offset = 0
    total_nfev = 0
    current_ctrl = np.asarray(ctrl0_flat, dtype=np.float32).copy()
    best_value = float("inf")
    last_message = ""
    last_success = False

    for stage_idx, stage in enumerate(schedule):
        stage_iters = max(1, int(config.opt_steps * stage.fraction))
        stage_config = SearchConfig(
            opt_steps=stage_iters,
            snapshot_every=config.snapshot_every,
            print_every=config.print_every,
        )

        label = f"Stage {stage_idx + 1}/{len(schedule)} (smooth x{stage.rf_smooth_mul:.0f})"
        if show_progress_bar:
            print(f"\n  {label}, {stage_iters} iters")

        value_and_grad = vg_factory(stage.rf_smooth_mul)

        def offset_progress(nit, best_x, bv, _offset=iter_offset):
            if progress_fn is not None:
                progress_fn(_offset + nit, best_x, bv)

        res = optimize_masked_controls_jax(
            value_and_grad=value_and_grad,
            ctrl0_flat=current_ctrl,
            free_mask_flat=free_mask_flat,
            bounds_full=bounds_full,
            config=stage_config,
            progress_fn=offset_progress,
            stop_requested_fn=stop_requested_fn,
            show_progress_bar=show_progress_bar,
        )

        for it, snap in res.snapshots.items():
            all_snapshots[iter_offset + it] = snap

        current_ctrl = res.x_full.copy()
        total_nfev += res.nfev
        iter_offset += res.nit
        last_message = res.message
        last_success = res.success
        best_value = res.best_value

        if stop_requested_fn is not None and stop_requested_fn():
            break

    return MaskedOptimizationResult(
        x_full=current_ctrl.astype(np.float32),
        nit=iter_offset,
        nfev=total_nfev,
        success=last_success,
        message=last_message,
        snapshots=all_snapshots,
        best_value=float(best_value),
    )

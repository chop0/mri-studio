from dataclasses import dataclass
from typing import Callable

import jax.numpy as jnp
import numpy as np
from scipy.optimize import minimize
from tqdm import tqdm

from mri_fast import B1_MAX, GX_MAX, GZ_MAX


@dataclass
class SearchConfig:
    opt_steps: int = 1200
    snapshot_every: int = 25
    print_every: int = 25


@dataclass
class MaskedOptimizationResult:
    x_full: np.ndarray
    nit: int
    nfev: int
    success: bool
    message: str
    snapshots: dict[int, np.ndarray]
    best_value: float


def flatten_ctrl_list(ctrl_list):
    return np.concatenate([np.asarray(c, dtype=np.float32).ravel() for c in ctrl_list]).astype(np.float32)


def split_ctrl_flat(flat_ctrl, segments):
    out = []
    idx = 0
    flat_ctrl = np.asarray(flat_ctrl, dtype=np.float32)
    for seg in segments:
        ns = seg["n_free"] + seg["n_pulse"]
        span = ns * 4
        out.append(flat_ctrl[idx:idx + span].reshape(ns, 4).astype(np.float32))
        idx += span
    return out


def flatten_mask_list(mask_list):
    return np.concatenate([np.asarray(m, dtype=bool).ravel() for m in mask_list])


def default_bounds_for_steps(n_total_steps):
    return [(-B1_MAX, B1_MAX), (-B1_MAX, B1_MAX), (-GX_MAX, GX_MAX), (-GZ_MAX, GZ_MAX)] * n_total_steps


def optimize_masked_controls(
    value_and_grad,
    ctrl0_flat,
    free_mask_flat,
    bounds_full,
    config: SearchConfig | None = None,
    progress_fn: Callable[[int, np.ndarray, float], None] | None = None,
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

    x0 = ctrl0_flat[free_idx].astype(np.float32)
    scales_full = np.array(
        [max(abs(lo), abs(hi), 1e-12) for lo, hi in bounds_full],
        dtype=np.float32,
    )
    scale_free = scales_full[free_idx]
    x0_norm = (x0 / scale_free).astype(np.float32)
    bounds = [
        (bounds_full[i][0] / scales_full[i], bounds_full[i][1] / scales_full[i])
        for i in free_idx
    ]
    state = {
        "nit": 0,
        "best_x": ctrl0_flat.copy(),
        "best_value": None,
    }
    bar = tqdm(total=config.opt_steps, desc="L-BFGS-B", unit="iter", leave=True) if show_progress_bar else None

    def merge_free(x_free_norm):
        x_full = ctrl0_flat.copy()
        x_full[free_idx] = np.asarray(x_free_norm, dtype=np.float32) * scale_free
        return x_full

    def fg(x_free_norm):
        x_full = merge_free(x_free_norm)
        value, grad_full = value_and_grad(jnp.asarray(x_full, dtype=jnp.float32))
        value = float(value)
        grad_full = np.asarray(grad_full, dtype=np.float64)
        if state["best_value"] is None or value < state["best_value"]:
            state["best_value"] = value
            state["best_x"] = x_full.copy()
        return value, grad_full[free_idx] * scale_free

    def callback(xk_norm):
        state["nit"] += 1
        if bar is not None:
            bar.update(1)
        x_full = merge_free(xk_norm)
        if state["nit"] % max(config.snapshot_every, 1) == 0:
            snapshots[state["nit"]] = x_full.copy()
        best_value = state["best_value"]
        if best_value is None:
            best_value, _ = fg(xk_norm)
        if progress_fn is not None:
            should_emit = config.print_every <= 1 or state["nit"] % config.print_every == 0
            if should_emit:
                progress_fn(state["nit"], state["best_x"].copy(), float(best_value))
        current_best = state["best_value"]
        if bar is not None and current_best is not None:
            bar.set_postfix(score=float(-current_best))

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
    finally:
        if bar is not None:
            bar.close()

    x_full = merge_free(res.x)
    if state["best_value"] is None:
        best_value, _ = fg(res.x)
        state["best_value"] = best_value
        state["best_x"] = x_full.copy()
    snapshots[int(res.nit)] = x_full.copy()
    return MaskedOptimizationResult(
        x_full=x_full.astype(np.float32),
        nit=int(res.nit),
        nfev=int(res.nfev),
        success=bool(res.success),
        message=str(res.message),
        snapshots=snapshots,
        best_value=float(state["best_value"]),
    )

from dataclasses import dataclass

import jax.numpy as jnp
import numpy as np
import optuna

from mri_fast import B1_MAX, GX_MAX, GZ_MAX


@dataclass
class SearchConfig:
    backend: str = "optuna"
    n_trials: int = 32
    inner_steps: int = 80
    seed: int = 0
    snapshot_every: int = 1
    lr_min: float = 1e-4
    lr_max: float = 5e-2
    grad_clip_min: float = 1e-3
    grad_clip_max: float = 1.0
    beta1_min: float = 0.8
    beta1_max: float = 0.98
    beta2_min: float = 0.95
    beta2_max: float = 0.9995
    decay_min: float = 0.90
    decay_max: float = 1.0


@dataclass
class MaskedOptimizationResult:
    x_full: np.ndarray
    nit: int
    nfev: int
    success: bool
    message: str
    snapshots: dict[int, np.ndarray]
    best_value: float
    backend: str


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


def _clip_to_bounds(x_full, bounds_full):
    lo = np.array([b[0] for b in bounds_full], dtype=np.float32)
    hi = np.array([b[1] for b in bounds_full], dtype=np.float32)
    return np.clip(x_full, lo, hi)


def _bounds_arrays(bounds_full):
    lo = np.array([b[0] for b in bounds_full], dtype=np.float32)
    hi = np.array([b[1] for b in bounds_full], dtype=np.float32)
    scale = np.maximum(np.maximum(np.abs(lo), np.abs(hi)), 1e-12).astype(np.float32)
    return lo, hi, scale


def _masked_adam_trial(value_and_grad, x0_full, free_mask_flat, bounds_full,
                       inner_steps, lr, beta1, beta2, grad_clip, decay):
    x0 = np.asarray(x0_full, dtype=np.float32)
    free_mask = np.asarray(free_mask_flat, dtype=bool)
    mask = free_mask.astype(np.float32)
    lo, hi, scale = _bounds_arrays(bounds_full)
    y0 = np.clip(x0 / scale, lo / scale, hi / scale)
    y = y0.copy()
    m = np.zeros_like(y)
    v = np.zeros_like(y)

    best_y = y.copy()
    best_value = None
    evals = 0

    for step in range(inner_steps):
        x = np.where(free_mask, y * scale, x0)
        value, grad_x = value_and_grad(jnp.asarray(x, dtype=jnp.float32))
        value = float(value)
        grad = (np.asarray(grad_x, dtype=np.float32) * scale) * mask
        evals += 1

        if best_value is None or value < best_value:
            best_value = value
            best_y = y.copy()

        gnorm = float(np.linalg.norm(grad))
        if gnorm > grad_clip and gnorm > 0:
            grad *= grad_clip / gnorm

        lr_t = lr * (decay ** step)
        m = beta1 * m + (1.0 - beta1) * grad
        v = beta2 * v + (1.0 - beta2) * (grad * grad)
        m_hat = m / (1.0 - beta1 ** (step + 1))
        v_hat = v / (1.0 - beta2 ** (step + 1))
        y = y - lr_t * m_hat / (np.sqrt(v_hat) + 1e-8)
        y = np.clip(y, lo / scale, hi / scale)
        y = np.where(free_mask, y, y0)

    if best_value is None:
        x = np.where(free_mask, y * scale, x0)
        value, _ = value_and_grad(jnp.asarray(x, dtype=jnp.float32))
        best_value = float(value)
        best_y = y.copy()
        evals += 1

    best_x = np.where(free_mask, best_y * scale, x0)
    best_x = _clip_to_bounds(best_x, bounds_full)
    return best_x.astype(np.float32), float(best_value), evals


def optimize_masked_controls_optuna(value_and_grad, ctrl0_flat, free_mask_flat, bounds_full,
                                    config: SearchConfig):
    ctrl0_flat = np.asarray(ctrl0_flat, dtype=np.float32)
    free_mask_flat = np.asarray(free_mask_flat, dtype=bool).ravel()
    snapshots = {0: ctrl0_flat.copy()}
    if ctrl0_flat.shape != free_mask_flat.shape:
        raise ValueError("ctrl0_flat and free_mask_flat must have the same shape")
    if not np.any(free_mask_flat):
        return MaskedOptimizationResult(
            x_full=ctrl0_flat.copy(),
            nit=0,
            nfev=0,
            success=True,
            message="No free control variables; skipped optimisation.",
            snapshots=snapshots,
            best_value=float("nan"),
            backend="optuna",
        )

    best = {"value": None, "x": ctrl0_flat.copy(), "evals": 0}

    def objective(trial):
        lr = trial.suggest_float("lr", config.lr_min, config.lr_max, log=True)
        beta1 = trial.suggest_float("beta1", config.beta1_min, config.beta1_max)
        beta2 = trial.suggest_float("beta2", config.beta2_min, config.beta2_max)
        grad_clip = trial.suggest_float("grad_clip", config.grad_clip_min, config.grad_clip_max, log=True)
        decay = trial.suggest_float("decay", config.decay_min, config.decay_max)
        x_trial, value_trial, evals = _masked_adam_trial(
            value_and_grad=value_and_grad,
            x0_full=ctrl0_flat,
            free_mask_flat=free_mask_flat,
            bounds_full=bounds_full,
            inner_steps=config.inner_steps,
            lr=lr,
            beta1=beta1,
            beta2=beta2,
            grad_clip=grad_clip,
            decay=decay,
        )
        trial.set_user_attr("x_full", x_trial.tolist())
        trial.set_user_attr("evals", int(evals))
        trial.set_user_attr("value", float(value_trial))
        if best["value"] is None or value_trial < best["value"]:
            best["value"] = value_trial
            best["x"] = x_trial
        best["evals"] += evals
        return value_trial

    sampler = optuna.samplers.TPESampler(seed=config.seed)
    study = optuna.create_study(direction="minimize", sampler=sampler)
    study.enqueue_trial(
        {
            "lr": 5e-3,
            "beta1": 0.9,
            "beta2": 0.999,
            "grad_clip": 0.1,
            "decay": 0.99,
        }
    )

    def capture_snapshot(study_, trial):
        if trial.state != optuna.trial.TrialState.COMPLETE:
            return
        if trial.number % max(config.snapshot_every, 1) != 0:
            return
        best_trial = study_.best_trial
        x_full = np.asarray(best_trial.user_attrs["x_full"], dtype=np.float32)
        snapshots[trial.number + 1] = x_full

    study.optimize(objective, n_trials=config.n_trials, callbacks=[capture_snapshot], show_progress_bar=False)
    snapshots[max(study.best_trial.number + 1, 0)] = best["x"].copy()
    return MaskedOptimizationResult(
        x_full=best["x"].astype(np.float32),
        nit=int(len(study.trials)),
        nfev=int(best["evals"]),
        success=study.best_trial is not None,
        message=f"Optuna completed {len(study.trials)} trials.",
        snapshots=snapshots,
        best_value=float(study.best_value),
        backend="optuna",
    )


def optimize_masked_controls_ray(value_and_grad, ctrl0_flat, free_mask_flat, bounds_full,
                                 config: SearchConfig):
    import ray
    from ray import tune

    ctrl0_flat = np.asarray(ctrl0_flat, dtype=np.float32)
    free_mask_flat = np.asarray(free_mask_flat, dtype=bool).ravel()
    snapshots = {0: ctrl0_flat.copy()}
    if ctrl0_flat.shape != free_mask_flat.shape:
        raise ValueError("ctrl0_flat and free_mask_flat must have the same shape")
    if not np.any(free_mask_flat):
        return MaskedOptimizationResult(
            x_full=ctrl0_flat.copy(),
            nit=0,
            nfev=0,
            success=True,
            message="No free control variables; skipped optimisation.",
            snapshots=snapshots,
            best_value=float("nan"),
            backend="ray",
        )

    ray.init(ignore_reinit_error=True, include_dashboard=False, log_to_driver=False, local_mode=True)
    try:
        def trainable(params):
            x_trial, value_trial, evals = _masked_adam_trial(
                value_and_grad=value_and_grad,
                x0_full=ctrl0_flat,
                free_mask_flat=free_mask_flat,
                bounds_full=bounds_full,
                inner_steps=config.inner_steps,
                lr=params["lr"],
                beta1=params["beta1"],
                beta2=params["beta2"],
                grad_clip=params["grad_clip"],
                decay=params["decay"],
            )
            tune.report(loss=float(value_trial), evals=int(evals), x_full=x_trial.tolist())

        param_space = {
            "lr": tune.loguniform(config.lr_min, config.lr_max),
            "beta1": tune.uniform(config.beta1_min, config.beta1_max),
            "beta2": tune.uniform(config.beta2_min, config.beta2_max),
            "grad_clip": tune.loguniform(config.grad_clip_min, config.grad_clip_max),
            "decay": tune.uniform(config.decay_min, config.decay_max),
        }

        tuner = tune.Tuner(
            trainable,
            tune_config=tune.TuneConfig(metric="loss", mode="min", num_samples=config.n_trials),
            param_space=param_space,
        )
        results = tuner.fit()
        best_result = results.get_best_result(metric="loss", mode="min")
        for idx, result in enumerate(results):
            if idx % max(config.snapshot_every, 1) == 0:
                snapshots[idx + 1] = np.asarray(result.metrics["x_full"], dtype=np.float32)
        best_x = np.asarray(best_result.metrics["x_full"], dtype=np.float32)
        return MaskedOptimizationResult(
            x_full=best_x,
            nit=int(len(results)),
            nfev=int(sum(int(result.metrics.get("evals", 0)) for result in results)),
            success=True,
            message=f"Ray Tune completed {len(results)} trials.",
            snapshots=snapshots,
            best_value=float(best_result.metrics["loss"]),
            backend="ray",
        )
    finally:
        ray.shutdown()


def optimize_masked_controls(value_and_grad, ctrl0_flat, free_mask_flat, bounds_full,
                             config: SearchConfig | None = None):
    config = config or SearchConfig()
    if config.backend == "ray":
        return optimize_masked_controls_ray(value_and_grad, ctrl0_flat, free_mask_flat, bounds_full, config)
    return optimize_masked_controls_optuna(value_and_grad, ctrl0_flat, free_mask_flat, bounds_full, config)

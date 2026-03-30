"""
Multi-pulse reconvergence — single-stage, xy cost.

Dense non-uniform z grid with excitation-weighted mask.
Exports GRAPE iteration snapshots for the Bloch viewer.
"""

import time
import json
import os
import signal
from dataclasses import dataclass
from concurrent.futures import ProcessPoolExecutor, wait, FIRST_COMPLETED
from multiprocessing import Manager

import numpy as np
import jax
import jax.numpy as jnp
from tqdm import tqdm

from mri_fast import (
    GAMMA, T1, T2, FOV_X, FOV_Z, GX_MAX, GZ_MAX, B1_MAX,
    ProblemNP, ProblemJAX,
    compute_B0, build_cpmg_warm,
    outside_slice_weights,
    excite_sinc_numpy, rodrigues_numpy, z_step_numpy,
    make_value_and_grad_full,
)
from mri_opt import (
    SearchConfig,
    default_bounds_for_steps,
    flatten_ctrl_list,
    flatten_mask_list,
    optimize_annealed,
    optimize_masked_controls,
    split_ctrl_flat,
)


@dataclass
class ScenarioSpec:
    name: str
    base: list[np.ndarray]
    free_mask: list[np.ndarray]
    objective_kind: str | None = None
    search: SearchConfig | None = None
    log_metrics_prob: ProblemNP | None = None
    log_metrics_w: np.ndarray | None = None
    log_metrics_smax: float | None = None


class ExitSignalError(RuntimeError):
    def __init__(self, signum: int):
        self.signum = signum
        super().__init__(f"Received signal {signum}")


class LocalStopFlag:
    def __init__(self):
        self._stop = False

    def set(self):
        self._stop = True

    def is_set(self):
        return self._stop


def problem_np_to_jax(prob_np: ProblemNP) -> ProblemJAX:
    flat = lambda a: jnp.asarray(a.ravel(), dtype=jnp.float32)
    nx, nz = prob_np.dBz.shape
    return ProblemJAX(
        flat(prob_np.Mx0),
        flat(prob_np.My0),
        flat(prob_np.Mz0),
        flat(prob_np.dBz),
        flat(prob_np.Gxm),
        flat(prob_np.Gzm),
        flat(prob_np.B1s),
        flat(prob_np.w_in),
        flat(prob_np.w_out),
        flat(GAMMA * prob_np.dBz),
        prob_np.s_max,
        nx,
        nz,
    )


def optimize_scenario_worker(task):
    (
        name,
        base,
        free_mask,
        seg_meta,
        bounds_full,
        search,
        objective_prob_np,
        eval_prob_np,
        eval_w,
        eval_smax,
        progress_queue,
        stop_flag,
    ) = task

    base_rf_smooth_pen = 4.0
    prob_jax = problem_np_to_jax(objective_prob_np)

    def vg_factory(rf_smooth_mul):
        vg = make_value_and_grad_full(
            prob_jax,
            seg_meta,
            lam_out=12.0,
            lam_pow=1.5,
            rf_pen=0.05,
            rf_smooth_pen=base_rf_smooth_pen * rf_smooth_mul,
            gate_switch_pen=1.5,
            gate_binary_pen=0.15,
        )
        return vg

    ctrl0_flat = flatten_ctrl_list(base)
    free_mask_flat = flatten_mask_list(free_mask)
    # Warm up JIT with the first stage's objective
    first_mul = (search.anneal_schedule[0].rf_smooth_mul
                 if search and search.anneal_schedule else 10.0)
    warmup_vg = vg_factory(first_mul)
    _ = warmup_vg(jnp.asarray(ctrl0_flat, dtype=jnp.float32))
    jax.block_until_ready(_)

    res = optimize_annealed(
        vg_factory=vg_factory,
        ctrl0_flat=ctrl0_flat,
        free_mask_flat=free_mask_flat,
        bounds_full=bounds_full,
        config=search,
        progress_fn=(
            None
            if progress_queue is None
            else lambda nit, _best_x, best_value: progress_queue.put(
                ("progress", name, int(nit), float(best_value))
            )
        ),
        stop_requested_fn=(None if stop_flag is None else stop_flag.is_set),
        show_progress_bar=progress_queue is None,
    )
    ctrl_list = split_ctrl_flat(res.x_full, seg_meta)
    snapshots = {
        int(it): split_ctrl_flat(flat_ctrl, seg_meta)
        for it, flat_ctrl in sorted(res.snapshots.items())
    }
    sig_eval, Mx_eval, My_eval, Mz_eval = simulate_numpy_full_signal(ctrl_list, seg_meta, eval_prob_np)
    m_eval = full_metrics(Mx_eval, My_eval, Mz_eval, eval_w, eval_smax)
    if progress_queue is not None:
        progress_queue.put(("done", name, int(res.nit), float(res.best_value)))
    return {
        "name": name,
        "message": str(res.message),
        "success": bool(res.success),
        "ctrl_list": ctrl_list,
        "snapshots": snapshots,
        "sig": sig_eval,
        "Mx": Mx_eval,
        "My": My_eval,
        "Mz": Mz_eval,
        "metrics": m_eval,
    }


def export_viewer_json(
    scenario_results,
    seg_meta,
    sw_half,
    B0n,
    t0,
    partial=False,
):
    if not scenario_results:
        print("\nSkipping JSON export: no scenarios available yet.")
        return

    print("\nExporting JSON..." + (" (partial)" if partial else ""))

    Nr_f, Nz_f = 30, 300
    r_arr = np.linspace(0, FOV_X / 2, Nr_f)
    z_arr_f = np.linspace(-FOV_Z / 2, FOV_Z / 2, Nz_f)
    Rf, Zf = np.meshgrid(r_arr, z_arr_f, indexing="ij")
    Bxfg, Bzfg = compute_B0(Rf, Zf)
    dBzfg = (Bzfg - B0n) + Bxfg ** 2 / (2 * B0n)

    Gxm_f = Rf + Zf ** 2 / (2 * B0n)
    Gzm_f = Zf + (Rf / 2) ** 2 / (2 * B0n)
    B1s_f = 1 + 0.12 * (Rf / (FOV_X / 2)) ** 2 + 0.08 * (Zf / (FOV_Z / 2)) ** 2
    Mx0_f, My0_f, Mz0_f = excite_sinc_numpy(dBzfg, Gzm_f, B1s_f)

    field_data = {
        "B0n": B0n, "gamma": GAMMA, "T1": T1, "T2": T2,
        "FOV_X": FOV_X, "FOV_Z": FOV_Z, "slice_half": sw_half,
        "segments": seg_meta,
        "r_mm": [round(r * 1e3, 1) for r in r_arr],
        "z_mm": [round(zv * 1e3, 1) for zv in z_arr_f],
        "dBz_uT": np.round(dBzfg * 1e6, 3).tolist(),
        "Mx0": np.round(Mx0_f, 4).tolist(),
        "My0": np.round(My0_f, 4).tolist(),
        "Mz0": np.round(Mz0_f, 4).tolist(),
    }

    def export_seg(steps):
        return [[round(float(v), 10) for v in step] for step in steps]

    def export_ctrl(ctrl_list):
        return [export_seg(c) for c in ctrl_list]

    iso_specs = [
        ("In-slice centre", 0.0, 0.0, "#22c55e", True),
        ("In-slice edge", 0.0, 0.003, "#06b6d4", True),
        ("Near out-of-slice", 0.0, 0.010, "#f59e0b", False),
        ("Far out-of-slice", 0.0, 0.030, "#ef4444", False),
        ("Off-axis in-slice", 0.025, 0.0, "#a855f7", True),
    ]

    def make_trajs(ctrl_list):
        segs = []
        for si, seg_m in enumerate(seg_meta):
            segs.append({"dt": seg_m["dt"], "n_free": seg_m["n_free"], "steps": ctrl_list[si]})
        trajs = []
        for i, (_, xp, zp, _, _) in enumerate(iso_specs):
            t = trace_isochromat(xp, zp, segs, B0n)
            trajs.append([i, [v for pt in t for v in pt]])
        return trajs

    export_scenarios = {}
    for scen_name, scen in scenario_results.items():
        pulses = {}
        trajectories = {}
        for it, ctrl_list in sorted(scen["snapshots"].items()):
            key = str(it)
            pulses[key] = export_ctrl(ctrl_list)
            trajectories[key] = make_trajs(ctrl_list)
        export_scenarios[scen_name] = {
            "pulses": pulses,
            "trajectories": trajectories,
        }

    output = {
        "iso": [[n, c, ins] for n, _, _, c, ins in iso_specs],
        "field": field_data,
        "scenarios": export_scenarios,
        "multi_segment": True,
        "partial_export": partial,
    }

    out_json = json.dumps(output, separators=(",", ":"))
    json_path = "bloch_data.json"
    with open(json_path, "w") as f:
        f.write(out_json)
    print(f"  {json_path}  ({len(out_json)} bytes)")
    for scen_name, scen in export_scenarios.items():
        print(f"  {scen_name} snapshots: {sorted(map(int, scen['pulses'].keys()))}")
    print(f"\nTotal: {time.time() - t0:.1f}s")


# ---------------------------------------------------------------------------
# Problem setup
# ---------------------------------------------------------------------------

def make_problem_dense(Nx=12, sw_half=0.004, exc_threshold=0.3):
    x = np.linspace(-FOV_X / 2, FOV_X / 2, Nx, dtype=np.float32)
    z_dense = np.linspace(-0.006, 0.006, 50, dtype=np.float32)
    z_neg = np.linspace(-0.125, -0.008, 30, dtype=np.float32)
    z_pos = np.linspace(0.008, 0.125, 30, dtype=np.float32)
    z = np.sort(np.concatenate([z_neg, z_dense, z_pos]))
    Nz = len(z)

    X, Z = np.meshgrid(x, z, indexing="ij")
    Bx0, Bz0 = compute_B0(X, Z)
    B0n = float(Bz0[Nx // 2, Nz // 2])
    dBz = (Bz0 - B0n) + Bx0 ** 2 / (2 * B0n)
    Gxm = X + Z ** 2 / (2 * B0n)
    Gzm = Z + (X / 2) ** 2 / (2 * B0n)
    B1s = 1 + 0.12 * (X / FOV_X * 2) ** 2 + 0.08 * (Z / FOV_Z * 2) ** 2

    Mx0, My0, Mz0 = excite_sinc_numpy(dBz, Gzm, B1s)
    Mp0 = np.sqrt(Mx0 ** 2 + My0 ** 2)

    geom_in = np.abs(Z) < sw_half
    w_in = ((Mp0 > exc_threshold) & geom_in).astype(np.float32)
    w_out = outside_slice_weights(Z, sw_half)
    s_max = float(np.sum(w_in))

    prob_np = ProblemNP(
        Mx0.astype(np.float32), My0.astype(np.float32), Mz0.astype(np.float32),
        dBz.astype(np.float32), Gxm.astype(np.float32), Gzm.astype(np.float32),
        B1s.astype(np.float32), w_in, w_out, s_max,
    )
    flat = lambda a: jnp.asarray(a.ravel(), dtype=jnp.float32)
    prob_jax = ProblemJAX(
        flat(Mx0), flat(My0), flat(Mz0), flat(dBz), flat(Gxm), flat(Gzm),
        flat(B1s), flat(w_in), flat(w_out), flat(GAMMA * dBz), s_max, Nx, Nz,
    )
    return prob_np, prob_jax, x, z, B0n


# ---------------------------------------------------------------------------
# Simulation + metrics
# ---------------------------------------------------------------------------

def simulate_numpy(ctrl, prob_np, n_free, dt):
    n_seg, n_steps, _ = ctrl.shape
    E2 = np.exp(-dt / T2)
    E1 = np.exp(-dt / T1)
    Mx, My, Mz = prob_np.Mx0.copy(), prob_np.My0.copy(), prob_np.Mz0.copy()
    omega_free = GAMMA * prob_np.dBz
    w_in = prob_np.w_in
    sig = [np.abs(np.sum(w_in * (Mx + 1j * My)))]
    for seg in range(n_seg):
        for step in range(n_steps):
            b1x, b1y, ux, uz = ctrl[seg, step, :4]
            rf_gate = float(ctrl[seg, step, 4]) if ctrl.shape[-1] > 4 else (0.0 if step < n_free else 1.0)
            if rf_gate < 0.5:
                om = omega_free + GAMMA * (ux * prob_np.Gxm + uz * prob_np.Gzm)
                Mx, My, Mz = z_step_numpy(Mx, My, Mz, om, dt, E1, E2)
            else:
                Mx, My, Mz = rodrigues_numpy(
                    b1x * prob_np.B1s, b1y * prob_np.B1s,
                    prob_np.dBz + ux * prob_np.Gxm + uz * prob_np.Gzm,
                    Mx, My, Mz, dt, E1, E2,
                )
            sig.append((1.0 - rf_gate) * np.abs(np.sum(w_in * (Mx + 1j * My))))
    return np.array(sig, dtype=np.float32), Mx, My, Mz


def simulate_numpy_full_signal(ctrl_list, segments, prob_np):
    Mx = np.zeros_like(prob_np.dBz)
    My = np.zeros_like(prob_np.dBz)
    Mz = np.ones_like(prob_np.dBz)
    omega_free = GAMMA * prob_np.dBz
    sig = [np.abs(np.sum(prob_np.w_in * (Mx + 1j * My)))]
    for ctrl_seg, seg in zip(ctrl_list, segments):
        dt_s = seg["dt"]
        nf = seg["n_free"]
        E2 = np.exp(-dt_s / T2)
        E1 = np.exp(-dt_s / T1)
        for j in range(ctrl_seg.shape[0]):
            b1x, b1y, ux, uz = ctrl_seg[j, :4]
            rf_gate = float(ctrl_seg[j, 4]) if ctrl_seg.shape[-1] > 4 else (0.0 if j < nf else 1.0)
            if rf_gate < 0.5:
                Mx, My, Mz = z_step_numpy(
                    Mx, My, Mz,
                    omega_free + GAMMA * (ux * prob_np.Gxm + uz * prob_np.Gzm),
                    dt_s, E1, E2,
                )
            else:
                Mx, My, Mz = rodrigues_numpy(
                    b1x * prob_np.B1s, b1y * prob_np.B1s,
                    prob_np.dBz + ux * prob_np.Gxm + uz * prob_np.Gzm,
                    Mx, My, Mz, dt_s, E1, E2,
                )
            sig.append((1.0 - rf_gate) * np.abs(np.sum(prob_np.w_in * (Mx + 1j * My))))
    return np.array(sig, dtype=np.float32), Mx, My, Mz


def full_metrics(Mx, My, Mz, w_in, s_max):
    mask = w_in > 0
    Mxi, Myi, Mzi = Mx[mask], My[mask], Mz[mask]
    Mpi = np.sqrt(Mxi ** 2 + Myi ** 2)

    Sx = np.sum(w_in * Mx)
    Sy = np.sum(w_in * My)
    pw = np.sum(w_in * (Mx ** 2 + My ** 2))
    coh = float((Sx ** 2 + Sy ** 2) / (pw * s_max)) if pw > 1e-10 else 0.0

    ok = Mpi > 0.05
    if np.sum(ok) > 2:
        phases = np.arctan2(Myi[ok], Mxi[ok])
        mean_ph = np.arctan2(np.mean(np.sin(phases)), np.mean(np.cos(phases)))
        phi_std = np.degrees(np.std(np.angle(np.exp(1j * (phases - mean_ph)))))
    else:
        phi_std = 180.0

    theta_std = np.degrees(np.std(np.arctan2(Mpi, Mzi)))
    mz_std = np.std(Mzi)
    return {"coh": coh, "phi_std": phi_std, "theta_std": theta_std,
            "mz_std": mz_std, "mperp_mean": np.mean(Mpi),
            "sig": np.abs(Sx + 1j * Sy) / s_max}


# ---------------------------------------------------------------------------
# Trajectory for viewer
# ---------------------------------------------------------------------------

def trace_isochromat(xp, zp, segments, B0n):
    """
    Compute trajectory for one (x, z) point through variable-dt segments.
    segments: list of dicts with 'dt', 'n_free', 'steps' (np array Nx4).
    Starts from thermal equilibrium (0, 0, 1).
    """
    xx = np.array([[xp]]); zz = np.array([[zp]])
    Bx_p, Bz_p = compute_B0(xx, zz)
    dBz_v = float((Bz_p[0, 0] - B0n) + Bx_p[0, 0] ** 2 / (2 * B0n))
    gxm_v = float(xp + zp ** 2 / (2 * B0n))
    gzm_v = float(zp + (xp / 2) ** 2 / (2 * B0n))
    b1s_v = float(1 + 0.12 * (xp / (FOV_X / 2)) ** 2 + 0.08 * (zp / (FOV_Z / 2)) ** 2)
    mx, my, mz = 0.0, 0.0, 1.0
    traj = []; t = 0.0
    for seg in segments:
        dt_s = seg["dt"]
        n_free_s = seg["n_free"]
        E2 = np.exp(-dt_s / T2); E1 = np.exp(-dt_s / T1)
        for si, step in enumerate(seg["steps"]):
            b1x, b1y, ux, uz = step[:4]
            rf_gate = float(step[4]) if len(step) > 4 else (0.0 if si < n_free_s else 1.0)
            is_free = rf_gate < 0.5
            traj.append([round(t * 1e6, 1), round(float(mx), 4),
                         round(float(my), 4), round(float(mz), 4),
                         0 if is_free else 1])
            if is_free:
                om = GAMMA * dBz_v + GAMMA * (ux * gxm_v + uz * gzm_v)
                th = om * dt_s; c, s = np.cos(th), np.sin(th)
                mx, my, mz = (mx*c - my*s)*E2, (mx*s + my*c)*E2, 1 + (mz - 1)*E1
            else:
                bx, by = b1x * b1s_v, b1y * b1s_v
                bz = dBz_v + ux * gxm_v + uz * gzm_v
                Bm = np.sqrt(bx**2 + by**2 + bz**2 + 1e-30)
                th = GAMMA * Bm * dt_s
                nx, ny, nz = bx/Bm, by/Bm, bz/Bm
                cv, sv = np.cos(th), np.sin(th); omc = 1 - cv
                ndM = nx*mx + ny*my + nz*mz
                cxv, cyv, czv = ny*mz - nz*my, nz*mx - nx*mz, nx*my - ny*mx
                mx = (mx*cv + cxv*sv + nx*ndM*omc) * E2
                my = (my*cv + cyv*sv + ny*ndM*omc) * E2
                mz = 1 + (mz*cv + czv*sv + nz*ndM*omc - 1) * E1
            t += dt_s
    traj.append([round(t * 1e6, 1), round(float(mx), 4),
                 round(float(my), 4), round(float(mz), 4), 2])
    return traj


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run():
    t0 = time.time()
    old_handlers = {}
    interrupted = False
    received_signal = {"signum": None}
    shared_stop_flag = {"flag": None}

    def handle_exit_signal(signum, _frame):
        nonlocal interrupted
        interrupted = True
        received_signal["signum"] = signum
        stop_flag = shared_stop_flag["flag"]
        if stop_flag is not None:
            stop_flag.set()
        print(f"\nReceived signal {signum}; stopping at the current optimizer iteration...")

    for sig in (signal.SIGINT, signal.SIGTERM):
        old_handlers[sig] = signal.getsignal(sig)
        signal.signal(sig, handle_exit_signal)

    print("=" * 65)
    print("  Multi-pulse reconvergence (dense grid, xy cost)")
    print("=" * 65)

    dt = 10e-6
    n_seg = 10
    n_free = 35
    n_pulse = 29
    n_steps = n_free + n_pulse
    sw_half = 0.004

    prob_np, prob_jax, x_arr, z_arr, B0n = make_problem_dense(Nx=12, sw_half=sw_half)
    Nx, Nz = 12, len(z_arr)
    mask2d = prob_np.w_in > 0
    ix_mid = Nx // 2
    mask_z = np.abs(z_arr) < sw_half

    print(f"\nGrid: {Nx}×{Nz} (non-uniform z)")
    print(f"In-slice weighted: {int(np.sum(mask2d))}  S_max: {prob_np.s_max:.0f}")

    # --- Build excitation pulse (used for warm start and export) ---
    dt_exc = 2e-6
    T_exc = 400e-6
    N_exc = int(T_exc / dt_exc)
    N_reph = N_exc // 2
    t_exc_arr = np.linspace(-T_exc / 2, T_exc / 2, N_exc)
    rf_exc = np.sinc(t_exc_arr / (T_exc / 2)) * (0.54 + 0.46 * np.cos(2 * np.pi * t_exc_arr / T_exc))
    rf_exc *= (np.pi / 2) / (GAMMA * np.sum(rf_exc) * dt_exc)
    exc_steps = np.zeros((N_exc + N_reph, 5), dtype=np.float32)
    exc_steps[:N_exc, 0] = rf_exc          # B1x during RF
    exc_steps[:N_exc, 3] = GZ_MAX          # Gz during RF
    exc_steps[:N_exc, 4] = 1.0             # RF enabled during excitation
    exc_steps[N_exc:, 3] = -GZ_MAX         # -Gz during rephase

    # Segment metadata (excitation + refocusing segments)
    seg_meta = [{"dt": dt_exc, "n_free": 0, "n_pulse": N_exc + N_reph, "n_ctrl": 5}]
    for _ in range(n_seg):
        seg_meta.append({"dt": dt, "n_free": n_free, "n_pulse": n_pulse, "n_ctrl": 5})

    ctrl0 = build_cpmg_warm(n_seg, n_free, n_pulse, dt)
    ctrl_free = np.zeros_like(ctrl0)

    # Basic CPMG: hard 180° y-pulse, NO gradients
    ctrl_basic = np.zeros_like(ctrl0)
    n_rf_basic = n_pulse * 2 // 3
    b1_hard = np.pi / (GAMMA * n_rf_basic * dt)
    b1_hard = min(b1_hard, B1_MAX)
    ctrl_basic[:, n_free:n_free + n_rf_basic, 1] = b1_hard
    ctrl_basic[:, n_free:, 4] = 1.0

    X_g, Z_g = np.meshgrid(x_arr, z_arr, indexing="ij")
    geom_in_full = (np.abs(Z_g) < sw_half).astype(np.float32)
    geom_out_full = outside_slice_weights(Z_g, sw_half)
    s_max_full = float(np.sum(geom_in_full))

    flat_f = lambda a: jnp.asarray(a.ravel(), dtype=jnp.float32)
    prob_jax_full = ProblemJAX(
        flat_f(np.zeros_like(prob_np.dBz)),
        flat_f(np.zeros_like(prob_np.dBz)),
        flat_f(np.ones_like(prob_np.dBz)),
        flat_f(prob_np.dBz), flat_f(prob_np.Gxm), flat_f(prob_np.Gzm),
        flat_f(prob_np.B1s), flat_f(geom_in_full), flat_f(geom_out_full),
        flat_f(GAMMA * prob_np.dBz), s_max_full, Nx, Nz,
    )
    prob_np_full = ProblemNP(
        np.zeros_like(prob_np.dBz), np.zeros_like(prob_np.dBz), np.ones_like(prob_np.dBz),
        prob_np.dBz, prob_np.Gxm, prob_np.Gzm, prob_np.B1s,
        geom_in_full, geom_out_full, s_max_full,
    )

    base_selective = [exc_steps.copy()] + [ctrl0[si].copy() for si in range(n_seg)]
    base_basic = [exc_steps.copy()] + [ctrl_basic[si].copy() for si in range(n_seg)]
    base_free = [exc_steps.copy()] + [ctrl_free[si].copy() for si in range(n_seg)]

    mask_none = [np.zeros_like(seg, dtype=bool) for seg in base_selective]
    mask_refocus = [np.zeros_like(base_selective[0], dtype=bool)] + [
        np.ones_like(seg, dtype=bool) for seg in base_selective[1:]
    ]
    mask_all = [np.ones_like(seg, dtype=bool) for seg in base_selective]

    total_steps = sum(seg["n_free"] + seg["n_pulse"] for seg in seg_meta)
    bounds_full = default_bounds_for_steps(total_steps, control_dim=5)

    scenario_specs = [
        ScenarioSpec(
            name="GRAPE",
            base=base_selective,
            free_mask=mask_refocus,
            objective_kind="selective",
            search=SearchConfig(
                opt_steps=5000,
                snapshot_every=200,
                print_every=100,
            ),
            log_metrics_prob=prob_np,
            log_metrics_w=prob_np.w_in,
            log_metrics_smax=prob_np.s_max,
        ),
        ScenarioSpec(
            name="Full GRAPE",
            base=base_selective,
            free_mask=mask_all,
            objective_kind="full",
            search=SearchConfig(
                opt_steps=5000,
                snapshot_every=200,
                print_every=100,
            ),
            log_metrics_prob=prob_np_full,
            log_metrics_w=geom_in_full,
            log_metrics_smax=s_max_full,
        ),
        ScenarioSpec(name="Selective CPMG", base=base_selective, free_mask=mask_none),
        ScenarioSpec(name="Basic CPMG", base=base_basic, free_mask=mask_none),
        ScenarioSpec(name="Free", base=base_free, free_mask=mask_none),
    ]

    scenario_results = {}
    try:
        optimized_specs = [spec for spec in scenario_specs if spec.objective_kind is not None and spec.search is not None]
        static_specs = [spec for spec in scenario_specs if spec.objective_kind is None or spec.search is None]
        opt_tasks = []
        opt_results = []

        # Use 1 worker on GPU to avoid VRAM exhaustion; scale to CPU count otherwise
        try:
            on_gpu = jax.devices()[0].platform == "gpu"
        except Exception:
            on_gpu = False
        opt_workers = 1 if on_gpu else min(len(optimized_specs), max(os.cpu_count() or 1, 1))
        if optimized_specs:
            print(f"\nOptimizing scenarios with {opt_workers} worker(s)...")
            for spec in optimized_specs:
                n_free_vars = int(np.count_nonzero(flatten_mask_list(spec.free_mask)))
                print(f"\n--- {spec.name} ({n_free_vars} free variables) ---")
                opt_tasks.append({
                    "name": spec.name,
                    "task": (
                        spec.name,
                        spec.base,
                        spec.free_mask,
                        seg_meta,
                        bounds_full,
                        spec.search,
                        spec.log_metrics_prob,
                        prob_np,
                        prob_np.w_in,
                        prob_np.s_max,
                    ),
                    "opt_steps": spec.search.opt_steps,
                })

        if opt_workers > 1 and len(opt_tasks) > 1:
            with Manager() as manager:
                shared_stop_flag["flag"] = manager.Event()
                progress_queue = manager.Queue()
                task_progress = {task_info["name"]: 0 for task_info in opt_tasks}
                task_targets = {task_info["name"]: task_info["opt_steps"] for task_info in opt_tasks}
                active_scores = {task_info["name"]: None for task_info in opt_tasks}
                total_progress = sum(task_info["opt_steps"] for task_info in opt_tasks)

                def refresh_progress_postfix(progress_bar):
                    running_scores = [
                        (name, score) for name, score in active_scores.items()
                        if score is not None and task_progress[name] < task_targets[name]
                    ]
                    if running_scores:
                        postfix = ", ".join(
                            f"{name}={-score:.4f}" for name, score in running_scores
                        )
                        progress_bar.set_postfix_str(postfix)
                    else:
                        progress_bar.set_postfix_str("")

                with tqdm(total=total_progress, desc="Overall optimization", unit="iter", leave=True) as progress_bar:
                    with ProcessPoolExecutor(max_workers=min(opt_workers, len(opt_tasks))) as pool:
                        futures = [
                            pool.submit(optimize_scenario_worker, (*task_info["task"], progress_queue, shared_stop_flag["flag"]))
                            for task_info in opt_tasks
                        ]
                        pending = set(futures)

                        while pending:
                            while not progress_queue.empty():
                                event, scenario_name, nit, best_value = progress_queue.get()
                                target = task_targets[scenario_name]
                                completed = min(max(int(nit), 0), int(target))
                                delta = completed - task_progress[scenario_name]
                                if delta > 0:
                                    progress_bar.update(delta)
                                    task_progress[scenario_name] = completed
                                if np.isfinite(best_value):
                                    active_scores[scenario_name] = float(best_value)
                                if event == "done" and task_progress[scenario_name] < target:
                                    remaining = target - task_progress[scenario_name]
                                    progress_bar.update(remaining)
                                    task_progress[scenario_name] = target
                                refresh_progress_postfix(progress_bar)

                            done_now, pending = wait(pending, timeout=0.1, return_when=FIRST_COMPLETED)
                            for future in done_now:
                                opt_results.append(future.result())

                        while not progress_queue.empty():
                            event, scenario_name, nit, best_value = progress_queue.get()
                            target = task_targets[scenario_name]
                            completed = min(max(int(nit), 0), int(target))
                            delta = completed - task_progress[scenario_name]
                            if delta > 0:
                                progress_bar.update(delta)
                                task_progress[scenario_name] = completed
                            if np.isfinite(best_value):
                                active_scores[scenario_name] = float(best_value)
                            if event == "done" and task_progress[scenario_name] < target:
                                remaining = target - task_progress[scenario_name]
                                progress_bar.update(remaining)
                                task_progress[scenario_name] = target
                            refresh_progress_postfix(progress_bar)

                    final_total = sum(task_progress.values())
                    if final_total < total_progress:
                        progress_bar.update(total_progress - final_total)
                    refresh_progress_postfix(progress_bar)
                shared_stop_flag["flag"] = None
        else:
            local_stop_flag = LocalStopFlag()
            shared_stop_flag["flag"] = local_stop_flag
            opt_results = []
            for task_info in opt_tasks:
                opt_results.append(optimize_scenario_worker((*task_info["task"], None, local_stop_flag)))
                if interrupted:
                    break
            shared_stop_flag["flag"] = None

        for result in opt_results:
            print(f"  {result['name']} status: {result['message']}")
            scenario_results[result["name"]] = {
                "ctrl_list": result["ctrl_list"],
                "snapshots": result["snapshots"],
                "sig": result["sig"],
                "Mx": result["Mx"],
                "My": result["My"],
                "Mz": result["Mz"],
                "metrics": result["metrics"],
            }

        if interrupted:
            raise ExitSignalError(received_signal["signum"] or signal.SIGINT)

        for spec in static_specs:
            name = spec.name
            n_free_vars = int(np.count_nonzero(flatten_mask_list(spec.free_mask)))
            print(f"\n--- {name} ({n_free_vars} free variables) ---")
            ctrl_list = [seg.copy() for seg in spec.base]
            snapshots = {0: [seg.copy() for seg in ctrl_list]}
            print("  Fully constrained; optimisation skipped.")

            sig_eval, Mx_eval, My_eval, Mz_eval = simulate_numpy_full_signal(ctrl_list, seg_meta, prob_np)
            m_eval = full_metrics(Mx_eval, My_eval, Mz_eval, prob_np.w_in, prob_np.s_max)
            scenario_results[name] = {
                "ctrl_list": ctrl_list,
                "snapshots": snapshots,
                "sig": sig_eval,
                "Mx": Mx_eval,
                "My": My_eval,
                "Mz": Mz_eval,
                "metrics": m_eval,
            }

        sig_opt = scenario_results["GRAPE"]["sig"]
        Mx_opt = scenario_results["GRAPE"]["Mx"]
        My_opt = scenario_results["GRAPE"]["My"]
        Mz_opt = scenario_results["GRAPE"]["Mz"]
        m_opt = scenario_results["GRAPE"]["metrics"]
        sig_cpmg = scenario_results["Selective CPMG"]["sig"]
        Mx_c = scenario_results["Selective CPMG"]["Mx"]
        My_c = scenario_results["Selective CPMG"]["My"]
        Mz_c = scenario_results["Selective CPMG"]["Mz"]
        m_cpmg = scenario_results["Selective CPMG"]["metrics"]
        sig_basic = scenario_results["Basic CPMG"]["sig"]
        Mx_b = scenario_results["Basic CPMG"]["Mx"]
        My_b = scenario_results["Basic CPMG"]["My"]
        Mz_b = scenario_results["Basic CPMG"]["Mz"]
        m_basic = scenario_results["Basic CPMG"]["metrics"]
        sig_free = scenario_results["Free"]["sig"]
        Mx_f = scenario_results["Free"]["Mx"]
        My_f = scenario_results["Free"]["My"]
        Mz_f = scenario_results["Free"]["Mz"]
        m_free = scenario_results["Free"]["metrics"]
        ctrl_opt = np.stack(scenario_results["GRAPE"]["ctrl_list"][1:], axis=0)

        print(f"\n{'':>20s} {'coh':>6s} {'sig':>6s} {'φ_std':>7s} {'θ_std':>7s}")
        print("-" * 50)
        for nm in ["Full GRAPE", "GRAPE", "Selective CPMG", "Basic CPMG", "Free"]:
            m = scenario_results[nm]["metrics"]
            print(f"  {nm:18s} {m['coh']:6.3f} {m['sig']:6.3f} {m['phi_std']:6.1f}° {m['theta_std']:6.1f}°")

        # --- Plots ---
        print("\nPlotting...")
        import matplotlib.pyplot as plt
        from matplotlib.gridspec import GridSpec

        grape_total = max(scenario_results["GRAPE"]["snapshots"].keys())
        scenarios = [
            ("GRAPE", sig_opt, Mx_opt, My_opt, Mz_opt, m_opt, "#2ca02c"),
            ("Selective CPMG", sig_cpmg, Mx_c, My_c, Mz_c, m_cpmg, "blue"),
            ("Basic CPMG", sig_basic, Mx_b, My_b, Mz_b, m_basic, "orange"),
            ("Free", sig_free, Mx_f, My_f, Mz_f, m_free, "red"),
        ]

        fig = plt.figure(figsize=(17, 26))
        gs = GridSpec(7, 2, figure=fig, hspace=0.42, wspace=0.3)

        # Row 0: signal
        ax = fig.add_subplot(gs[0, :])
        for nm, sig, _, _, _, m, col in scenarios:
            ax.plot(np.arange(len(sig)) * dt * 1e3, sig / prob_np.s_max,
                    col, lw=2, label=f"{nm} coh={m['coh']:.2f}")
        ax.set_xlabel("t [ms]"); ax.set_ylabel("signal / S_max")
        ax.set_title(f"Multi-pulse reconvergence ({grape_total} trials)")
        ax.legend(fontsize=8); ax.grid(alpha=0.3); ax.set_ylim(-0.05, 1.05)

        # Row 1: |M⊥| full FOV and zoomed near slice
        ax = fig.add_subplot(gs[1, 0])
        for nm, _, Mxf, Myf, _, _, col in scenarios:
            Mp = np.sqrt(Mxf ** 2 + Myf ** 2)
            ax.plot(z_arr * 1e3, Mp[ix_mid, :], col, lw=2, label=nm)
        ax.axvspan(-sw_half * 1e3, sw_half * 1e3, color="green", alpha=0.1)
        ax.set_xlabel("z [mm]"); ax.set_ylabel("|M⊥|")
        ax.set_title("|M⊥| — full FOV"); ax.legend(fontsize=7); ax.grid(alpha=0.3)

        ax = fig.add_subplot(gs[1, 1])
        zoom = np.abs(z_arr) < 0.008  # ±8mm zoom
        for nm, _, Mxf, Myf, _, _, col in scenarios:
            Mp = np.sqrt(Mxf ** 2 + Myf ** 2)
            ax.plot(z_arr[zoom] * 1e3, Mp[ix_mid, zoom], col, lw=2, label=nm)
        Mp0 = np.sqrt(prob_np.Mx0 ** 2 + prob_np.My0 ** 2)
        ax.plot(z_arr[zoom] * 1e3, Mp0[ix_mid, zoom], "gray", ls="--", lw=1, alpha=0.5, label="Excitation")
        ax.axvspan(-sw_half * 1e3, sw_half * 1e3, color="green", alpha=0.1)
        ax.set_xlabel("z [mm]"); ax.set_ylabel("|M⊥|")
        ax.set_title("|M⊥| — zoomed ±8mm"); ax.legend(fontsize=7); ax.grid(alpha=0.3)

        # Row 2: |M⊥| as 2D map near slice + all x rows overlaid
        ax = fig.add_subplot(gs[2, 0])
        Mp_opt = np.sqrt(Mx_opt ** 2 + My_opt ** 2)
        im = ax.pcolormesh(z_arr[zoom] * 1e3, x_arr * 1e3, Mp_opt[:, zoom],
                           cmap="inferno", shading="auto", vmin=0, vmax=1)
        ax.set_xlabel("z [mm]"); ax.set_ylabel("x [mm]")
        ax.set_title("GRAPE |M⊥| — 2D (±8mm)"); plt.colorbar(im, ax=ax, shrink=0.85)
        for zz in [-sw_half, sw_half]:
            ax.axvline(x=zz * 1e3, color="w", ls="--", lw=0.8)

        ax = fig.add_subplot(gs[2, 1])
        for xi in range(Nx):
            Mp_xi = np.sqrt(Mx_opt[xi, :] ** 2 + My_opt[xi, :] ** 2)
            ax.plot(z_arr[zoom] * 1e3, Mp_xi[zoom], "#2ca02c", lw=0.6, alpha=0.3)
        ax.plot(z_arr[zoom] * 1e3, Mp_opt[ix_mid, zoom], "#2ca02c", lw=2, label="x=0")
        ax.axvspan(-sw_half * 1e3, sw_half * 1e3, color="green", alpha=0.1)
        ax.set_xlabel("z [mm]"); ax.set_ylabel("|M⊥|")
        ax.set_title("|M⊥| all x rows (green=x=0)"); ax.legend(); ax.grid(alpha=0.3)

        # Row 3: phase + polar in slice
        ax = fig.add_subplot(gs[3, 0])
        for nm, _, Mxf, Myf, Mzf, _, col in scenarios[:2]:
            Mp = np.sqrt(Mxf ** 2 + Myf ** 2)
            th2 = np.degrees(np.arctan2(Mp, Mzf))
            vals = np.where(Mp[ix_mid, mask_z] > 0.05, th2[ix_mid, mask_z], np.nan)
            ax.plot(z_arr[mask_z] * 1e3, vals, col, lw=2, marker=".", ms=3, label=nm)
        ax.set_xlabel("z [mm]"); ax.set_ylabel("Polar angle [deg]")
        ax.set_title(f"Polar — GRAPE θ_std={m_opt['theta_std']:.1f}°")
        ax.legend(); ax.grid(alpha=0.3)

        ax = fig.add_subplot(gs[3, 1])
        for nm, _, Mxf, Myf, _, _, col in scenarios[:2]:
            Mp = np.sqrt(Mxf ** 2 + Myf ** 2)
            ph = np.degrees(np.arctan2(Myf, Mxf))
            vals = np.where(Mp[ix_mid, mask_z] > 0.05, ph[ix_mid, mask_z], np.nan)
            ax.plot(z_arr[mask_z] * 1e3, vals, col, lw=2, marker=".", ms=3, label=nm)
        ax.set_xlabel("z [mm]"); ax.set_ylabel("Phase [deg]")
        ax.set_title(f"Phase — GRAPE φ_std={m_opt['phi_std']:.1f}°")
        ax.legend(); ax.grid(alpha=0.3)

        # Row 4: 3D scatter
        ax = fig.add_subplot(gs[4, 0], projection="3d")
        ax.scatter(Mx_opt[mask2d], My_opt[mask2d], Mz_opt[mask2d], c="#2ca02c", s=8, alpha=0.5)
        ax.set_xlabel("Mx"); ax.set_ylabel("My"); ax.set_zlabel("Mz")
        ax.set_title(f"GRAPE θ={m_opt['theta_std']:.1f}° φ={m_opt['phi_std']:.1f}°")

        ax = fig.add_subplot(gs[4, 1], projection="3d")
        ax.scatter(Mx_c[mask2d], My_c[mask2d], Mz_c[mask2d], c="blue", s=8, alpha=0.5)
        ax.set_xlabel("Mx"); ax.set_ylabel("My"); ax.set_zlabel("Mz")
        ax.set_title("CPMG")

        # Row 5: controls
        t_seg = np.arange(n_steps) * dt * 1e6
        ax = fig.add_subplot(gs[5, 0])
        for seg in range(n_seg):
            b1 = np.sqrt(ctrl_opt[seg, :, 0] ** 2 + ctrl_opt[seg, :, 1] ** 2)
            ax.plot(t_seg, b1 * 1e6, lw=0.7, alpha=0.5)
        ax.set_xlabel("step [μs]"); ax.set_ylabel("|B₁| [μT]")
        ax.set_title(f"|B₁| all {n_seg} segments"); ax.grid(alpha=0.3)

        ax = fig.add_subplot(gs[5, 1])
        for seg in range(n_seg):
            ax.plot(t_seg, ctrl_opt[seg, :, 3] * 1e3, lw=0.7, alpha=0.5)
        ax.set_xlabel("step [μs]"); ax.set_ylabel("Gz [mT/m]")
        ax.set_title(f"Gz all {n_seg} segments"); ax.grid(alpha=0.3)

        # Row 6: summary
        ax = fig.add_subplot(gs[6, :]); ax.axis("off")
        txt = f"Dense grid ({Nx}×{Nz}), xy cost, {grape_total} trials\n\n"
        txt += f"  {'':>18s} {'coh':>6s} {'sig':>6s} {'φ_std':>7s} {'θ_std':>7s}\n"
        txt += "  " + "-" * 42 + "\n"
        for nm, m in [("Full GRAPE", scenario_results["Full GRAPE"]["metrics"]), ("GRAPE", m_opt),
                      ("Selective CPMG", m_cpmg), ("Basic CPMG", m_basic), ("Free", m_free)]:
            txt += f"  {nm:18s} {m['coh']:6.3f} {m['sig']:6.3f} {m['phi_std']:6.1f}° {m['theta_std']:6.1f}°\n"
        ax.text(0.05, 0.9, txt, transform=ax.transAxes, fontsize=12, va="top",
                family="monospace", bbox=dict(boxstyle="round", facecolor="wheat", alpha=0.5))

        plt.show()
    except ExitSignalError as err:
        interrupted = True
        print(f"\nReceived signal {err.signum}; exporting current results before exit.")
    finally:
        stop_flag = shared_stop_flag["flag"]
        if stop_flag is not None:
            stop_flag.set()
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        export_viewer_json(
            scenario_results=scenario_results,
            seg_meta=seg_meta,
            sw_half=sw_half,
            B0n=B0n,
            t0=t0,
            partial=interrupted,
        )


if __name__ == "__main__":
    import multiprocessing
    try:
        multiprocessing.set_start_method("spawn")
    except RuntimeError:
        pass  # already set (e.g. Python 3.14+ defaults to spawn)
    run()

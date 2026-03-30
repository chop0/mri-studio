"""
Multi-pulse reconvergence — single-stage, xy cost.

Dense non-uniform z grid with excitation-weighted mask.
Exports GRAPE iteration snapshots for the Bloch viewer.
"""

import time
import json
import numpy as np
import jax
import jax.numpy as jnp
from scipy.optimize import minimize
import matplotlib
import matplotlib.pyplot as plt
from matplotlib.gridspec import GridSpec

from mri_fast import (
    GAMMA, T1, T2, FOV_X, FOV_Z, GX_MAX, GZ_MAX, B1_MAX,
    ProblemNP, ProblemJAX,
    compute_B0, build_cpmg_warm,
    excite_sinc_numpy, rodrigues_numpy, z_step_numpy,
    make_value_and_grad,
)


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
    w_out = (~geom_in).astype(np.float32)
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
            b1x, b1y, ux, uz = ctrl[seg, step]
            if step < n_free:
                om = omega_free + GAMMA * (ux * prob_np.Gxm + uz * prob_np.Gzm)
                Mx, My, Mz = z_step_numpy(Mx, My, Mz, om, dt, E1, E2)
            else:
                Mx, My, Mz = rodrigues_numpy(
                    b1x * prob_np.B1s, b1y * prob_np.B1s,
                    prob_np.dBz + ux * prob_np.Gxm + uz * prob_np.Gzm,
                    Mx, My, Mz, dt, E1, E2,
                )
            sig.append(np.abs(np.sum(w_in * (Mx + 1j * My))))
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
        for si, (b1x, b1y, ux, uz) in enumerate(seg["steps"]):
            is_free = si < n_free_s
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

    # --- Compile ---
    print("\nCompiling...")
    vg = make_value_and_grad(prob_jax, n_seg, n_free, n_pulse, dt, 20000.0, 5e5)
    ctrl0 = build_cpmg_warm(n_seg, n_free, n_pulse, dt)
    _ = vg(jnp.asarray(ctrl0.ravel(), dtype=jnp.float32))
    jax.block_until_ready(_)
    print(f"Compiled: {time.time() - t0:.1f}s")

    # --- Optimise with snapshots ---
    print(f"\n--- GRAPE ({n_seg} segments, {n_seg*n_steps*4} variables) ---")
    bounds = [(-B1_MAX, B1_MAX), (-B1_MAX, B1_MAX),
              (-GX_MAX, GX_MAX), (-GZ_MAX, GZ_MAX)] * (n_seg * n_steps)

    def fg(xv):
        v, g = vg(jnp.asarray(xv, dtype=jnp.float32))
        return float(v), np.asarray(g, dtype=np.float64)

    ctrl = ctrl0.copy()
    snapshots = {0: ctrl.copy()}
    total = 0
    snap_every = 100

    for chunk in range(12):
        res = minimize(fg, ctrl.ravel(), method="L-BFGS-B", jac=True,
                       bounds=bounds, options={"maxiter": snap_every, "ftol": 1e-12, "gtol": 1e-8})
        ctrl = res.x.reshape(n_seg, n_steps, 4).astype(np.float32)
        total += res.nit
        snapshots[total] = ctrl.copy()

        _, Mxf, Myf, Mzf = simulate_numpy(ctrl, prob_np, n_free, dt)
        m = full_metrics(Mxf, Myf, Mzf, prob_np.w_in, prob_np.s_max)
        print(f"  iter {total:4d}  coh={m['coh']:.3f}  φ_std={m['phi_std']:.1f}°  "
              f"θ_std={m['theta_std']:.1f}°  sig={m['sig']:.3f}  [{time.time()-t0:.0f}s]")

    ctrl_opt = ctrl

    # --- Final metrics ---
    sig_opt, Mx_opt, My_opt, Mz_opt = simulate_numpy(ctrl_opt, prob_np, n_free, dt)
    m_opt = full_metrics(Mx_opt, My_opt, Mz_opt, prob_np.w_in, prob_np.s_max)
    sig_cpmg, Mx_c, My_c, Mz_c = simulate_numpy(ctrl0, prob_np, n_free, dt)
    m_cpmg = full_metrics(Mx_c, My_c, Mz_c, prob_np.w_in, prob_np.s_max)
    ctrl_free = np.zeros_like(ctrl0)
    sig_free, Mx_f, My_f, Mz_f = simulate_numpy(ctrl_free, prob_np, n_free, dt)
    m_free = full_metrics(Mx_f, My_f, Mz_f, prob_np.w_in, prob_np.s_max)

    # Basic CPMG: hard 180° y-pulse, NO gradients
    ctrl_basic = np.zeros_like(ctrl0)
    n_rf_basic = n_pulse * 2 // 3
    T_rf_basic = n_rf_basic * dt
    b1_hard = np.pi / (GAMMA * n_rf_basic * dt)  # amplitude for 180° flip
    b1_hard = min(b1_hard, B1_MAX)
    ctrl_basic[:, n_free:n_free + n_rf_basic, 1] = b1_hard  # y-pulse
    sig_basic, Mx_b, My_b, Mz_b = simulate_numpy(ctrl_basic, prob_np, n_free, dt)
    m_basic = full_metrics(Mx_b, My_b, Mz_b, prob_np.w_in, prob_np.s_max)

    print(f"\n{'':>20s} {'coh':>6s} {'sig':>6s} {'φ_std':>7s} {'θ_std':>7s}")
    print("-" * 50)
    for nm, m in [("GRAPE", m_opt), ("Selective CPMG", m_cpmg), ("Basic CPMG", m_basic), ("Free", m_free)]:
        print(f"  {nm:18s} {m['coh']:6.3f} {m['sig']:6.3f} {m['phi_std']:6.1f}° {m['theta_std']:6.1f}°")

    # --- Plots ---
    print("\nPlotting...")
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
    ax.set_title(f"Multi-pulse reconvergence ({total} iters)")
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
    # Also show excitation profile for reference
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
    # Highlight x=0
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
    txt = f"Dense grid ({Nx}×{Nz}), xy cost, {total} iters\n\n"
    txt += f"  {'':>18s} {'coh':>6s} {'sig':>6s} {'φ_std':>7s} {'θ_std':>7s}\n"
    txt += "  " + "-" * 42 + "\n"
    for nm, m in [("GRAPE", m_opt), ("Selective CPMG", m_cpmg), ("Basic CPMG", m_basic), ("Free", m_free)]:
        txt += f"  {nm:18s} {m['coh']:6.3f} {m['sig']:6.3f} {m['phi_std']:6.1f}° {m['theta_std']:6.1f}°\n"
    ax.text(0.05, 0.9, txt, transform=ax.transAxes, fontsize=12, va="top",
            family="monospace", bbox=dict(boxstyle="round", facecolor="wheat", alpha=0.5))

    plt.show()

    # =====================================================================
    # JSON export with GRAPE snapshots
    # =====================================================================
    print("\nExporting JSON...")

    Nr_f, Nz_f = 30, 300
    r_arr = np.linspace(0, FOV_X / 2, Nr_f)
    z_arr_f = np.linspace(-FOV_Z / 2, FOV_Z / 2, Nz_f)
    Rf, Zf = np.meshgrid(r_arr, z_arr_f, indexing="ij")
    Bxfg, Bzfg = compute_B0(Rf, Zf)
    dBzfg = (Bzfg - B0n) + Bxfg ** 2 / (2 * B0n)

    # Compute excitation profile on the export grid
    Gxm_f = Rf + Zf ** 2 / (2 * B0n)
    Gzm_f = Zf + (Rf / 2) ** 2 / (2 * B0n)
    B1s_f = 1 + 0.12 * (Rf / (FOV_X / 2)) ** 2 + 0.08 * (Zf / (FOV_Z / 2)) ** 2
    Mx0_f, My0_f, Mz0_f = excite_sinc_numpy(dBzfg, Gzm_f, B1s_f)

    # Build excitation segment (matches excite_sinc_numpy internals)
    dt_exc = 2e-6
    T_exc = 400e-6
    N_exc = int(T_exc / dt_exc)
    N_reph = N_exc // 2
    t_exc = np.linspace(-T_exc / 2, T_exc / 2, N_exc)
    rf_exc = np.sinc(t_exc / (T_exc / 2)) * (0.54 + 0.46 * np.cos(2 * np.pi * t_exc / T_exc))
    rf_exc *= (np.pi / 2) / (GAMMA * np.sum(rf_exc) * dt_exc)
    exc_steps = np.zeros((N_exc + N_reph, 4), dtype=np.float32)
    exc_steps[:N_exc, 0] = rf_exc          # B1x during RF
    exc_steps[:N_exc, 3] = GZ_MAX          # Gz during RF
    exc_steps[N_exc:, 3] = -GZ_MAX         # -Gz during rephase

    # Segments metadata: excitation + n_seg refocusing segments
    seg_meta = [{"dt": dt_exc, "n_free": 0, "n_pulse": N_exc + N_reph}]
    for _ in range(n_seg):
        seg_meta.append({"dt": dt, "n_free": n_free, "n_pulse": n_pulse})

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

    def export_ctrl(c):
        """Prepend excitation segment to refocusing controls."""
        out = [export_seg(exc_steps)]
        for seg in c:
            out.append(export_seg(seg))
        return out

    def build_segments(c3d):
        """Build list-of-dicts for trace_isochromat."""
        segs = [{"dt": dt_exc, "n_free": 0, "steps": exc_steps}]
        for si in range(c3d.shape[0]):
            segs.append({"dt": dt, "n_free": n_free, "steps": c3d[si]})
        return segs

    iso_specs = [
        ("In-slice centre", 0.0, 0.0, "#22c55e", True),
        ("In-slice edge", 0.0, 0.003, "#06b6d4", True),
        ("Near out-of-slice", 0.0, 0.010, "#f59e0b", False),
        ("Far out-of-slice", 0.0, 0.030, "#ef4444", False),
        ("Off-axis in-slice", 0.025, 0.0, "#a855f7", True),
    ]

    def make_trajs(c3d):
        segs = build_segments(c3d)
        trajs = []
        for i, (_, xp, zp, _, _) in enumerate(iso_specs):
            t = trace_isochromat(xp, zp, segs, B0n)
            trajs.append([i, [v for pt in t for v in pt]])
        return trajs

    # Fixed scenarios
    precomp = {}
    for scen_name, c3d in [("GRAPE", ctrl_opt), ("Selective CPMG", ctrl0), ("Basic CPMG", ctrl_basic), ("Free", ctrl_free)]:
        precomp[scen_name] = make_trajs(c3d)

    # GRAPE iteration snapshots
    grape_iters = {}
    grape_pulses = {}
    for it, csnap in sorted(snapshots.items()):
        grape_iters[str(it)] = make_trajs(csnap)
        grape_pulses[str(it)] = export_ctrl(csnap)

    output = {
        "iso": [[n, c, ins] for n, _, _, c, ins in iso_specs],
        "fixed": precomp,
        "grape": grape_iters,
        "field": field_data,
        "pulses": {
            "GRAPE": export_ctrl(ctrl_opt),
            "Selective CPMG": export_ctrl(ctrl0),
            "Basic CPMG": export_ctrl(ctrl_basic),
            "Free": export_ctrl(ctrl_free),
            "grape": grape_pulses,
        },
        "multi_segment": True,
    }

    out_json = json.dumps(output, separators=(",", ":"))
    json_path = "bloch_data.json"
    with open(json_path, "w") as f:
        f.write(out_json)
    print(f"  {json_path}  ({len(out_json)} bytes)")
    print(f"  GRAPE snapshots: {sorted(snapshots.keys())}")

    print(f"\nTotal: {time.time() - t0:.1f}s")


if __name__ == "__main__":
    run()
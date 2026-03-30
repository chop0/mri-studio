#!/usr/bin/env python3
"""
CPMG Echo Train Simulation with Modified Helmholtz B1 Coil
==========================================================
1. Biot-Savart B1 field from 4-coil modified Helmholtz pair
2. B1 histogram over FOV (reduces 3D -> 1D problem)
3. Rotation-matrix CPMG simulation per B1 bin
4. Receive-sensitivity-weighted signal summation
"""

import numpy as np
import matplotlib.pyplot as plt
from matplotlib.gridspec import GridSpec
import time

# ============================================================
# Physical Constants
# ============================================================
MU_0 = 4 * np.pi * 1e-7          # T·m/A
GAMMA = 2.675222e8                # rad/s/T (proton)
GAMMA_HZ = GAMMA / (2 * np.pi)   # Hz/T ≈ 42.576 MHz/T

# ============================================================
# Coil Geometry — Modified Helmholtz
# ============================================================
D_LARGE = 270e-3                        # m
D_SMALL = 0.764 * D_LARGE               # m ≈ 206.28 mm
R_LARGE = D_LARGE / 2                   # 135 mm
R_SMALL = D_SMALL / 2                   # ~103.14 mm
WIRE_D  = 5.3e-3                        # m
N_TURNS = 10                            # per coil
I_CURRENT = 100.0                       # A (pulsed)

# Helmholtz-like separation: coil planes at x = ±HALF_SEP
# Standard Helmholtz: separation = radius → half_sep = R/2
# Using the large-coil radius for the pair separation
HALF_SEP = R_LARGE / 2                  # 67.5 mm

# Winding layout: 2 layers × 5 turns/layer
N_LAYERS = 2
TURNS_PER_LAYER = N_TURNS // N_LAYERS

# ============================================================
# FOV
# ============================================================
FOV_X = 70e-3       # m, transverse (along coil axis)
FOV_Y = 90e-3       # m, transverse
FOV_Z = 250e-3      # m, along B0

# ============================================================
# NMR / CPMG Parameters
# ============================================================
T1 = 2.0             # s  (water)
T2 = 2.0             # s  (water)
TE = 1.0e-3          # s  echo spacing
N_ECHOES = 500


# ============================================================
# Biot-Savart Engine
# ============================================================

def make_loop(R, center, n_seg=200):
    """
    Circular current loop in the YZ-plane (axis along X).
    Returns midpoints and dl vectors.
    """
    theta = np.linspace(0, 2*np.pi, n_seg, endpoint=False)
    dt = 2 * np.pi / n_seg
    cx, cy, cz = center

    pos = np.empty((n_seg, 3))
    pos[:, 0] = cx
    pos[:, 1] = cy + R * np.cos(theta)
    pos[:, 2] = cz + R * np.sin(theta)

    dl = np.empty((n_seg, 3))
    dl[:, 0] = 0.0
    dl[:, 1] = -R * np.sin(theta) * dt
    dl[:, 2] =  R * np.cos(theta) * dt

    return pos, dl


def biot_savart_batch(loop_pos, loop_dl, current, field_pts, batch=4000):
    """
    Biot-Savart for one loop, batched over field points to control memory.
    Returns B(M,3) at field_pts(M,3).
    """
    M = field_pts.shape[0]
    B = np.zeros((M, 3))
    n_seg = loop_pos.shape[0]

    for start in range(0, M, batch):
        end = min(start + batch, M)
        fp = field_pts[start:end]          # (b, 3)
        b = fp.shape[0]

        # r_vec = field_point - source  →  (n_seg, b, 3)
        rv = fp[np.newaxis, :, :] - loop_pos[:, np.newaxis, :]
        r2 = np.sum(rv**2, axis=-1, keepdims=True)          # (n_seg, b, 1)
        r3 = np.maximum(r2, 1e-20) * np.sqrt(np.maximum(r2, 1e-20))

        # dl × r_vec
        dl_exp = loop_dl[:, np.newaxis, :]                   # (n_seg, 1, 3)
        cross = np.cross(dl_exp, rv)                          # (n_seg, b, 3)

        dB = cross / r3                                       # (n_seg, b, 3)
        B[start:end] = (MU_0 / (4*np.pi)) * current * dB.sum(axis=0)

    return B


def build_coil_system():
    """
    4-coil modified Helmholtz: large + small pair on each side.
    Each coil has N_TURNS spread over N_LAYERS.
    Returns list of (loop_positions, loop_dl, current).
    """
    loops = []
    for side in (+1, -1):                           # two sides
        for R_coil in (R_LARGE, R_SMALL):           # two radii per side
            for layer in range(N_LAYERS):
                r_off = layer * WIRE_D              # radial offset
                for t in range(TURNS_PER_LAYER):
                    ax_off = (t - (TURNS_PER_LAYER - 1) / 2) * WIRE_D
                    cx = side * HALF_SEP + ax_off
                    pos, dl = make_loop(R_coil + r_off, (cx, 0.0, 0.0))
                    loops.append((pos, dl, I_CURRENT))
    return loops


def compute_b1_on_grid(coils, x, y, z):
    """
    Total B field on a 3D grid.  Returns Bx,By,Bz each shaped (nx,ny,nz).
    """
    X, Y, Z = np.meshgrid(x, y, z, indexing='ij')
    fp = np.column_stack([X.ravel(), Y.ravel(), Z.ravel()])
    B_tot = np.zeros_like(fp)
    for i, (lp, ld, cur) in enumerate(coils):
        B_tot += biot_savart_batch(lp, ld, cur, fp)
        if (i + 1) % 10 == 0:
            print(f"    loops done: {i+1}/{len(coils)}")
    Bx = B_tot[:, 0].reshape(X.shape)
    By = B_tot[:, 1].reshape(X.shape)
    Bz = B_tot[:, 2].reshape(X.shape)
    return Bx, By, Bz


# ============================================================
# CPMG Engine — Rotation Matrices (vectorised over B1 bins)
# ============================================================

def rot_x(a):
    """Rotation about x.  a: (N,) angles. Returns (N,3,3)."""
    c, s = np.cos(a), np.sin(a)
    R = np.zeros((*a.shape, 3, 3))
    R[...,0,0] = 1;  R[...,1,1] = c;  R[...,1,2] = -s
    R[...,2,1] = s;  R[...,2,2] = c
    return R

def rot_y(a):
    """Rotation about y.  a: (N,) angles. Returns (N,3,3)."""
    c, s = np.cos(a), np.sin(a)
    R = np.zeros((*a.shape, 3, 3))
    R[...,0,0] = c;  R[...,0,2] = s;  R[...,1,1] = 1
    R[...,2,0] = -s; R[...,2,2] = c
    return R


def simulate_cpmg(b1_vals, b1_center, t90, t180, te, n_echo, t1, t2):
    """
    CPMG with Meiboom-Gill phase cycling: 90_x, then 180_y.
    b1_vals : (N,) rotating-frame B1 amplitudes at each bin.
    Returns  : echo_per_bin (N, n_echo)  — |Mxy| at each echo.
    """
    N = len(b1_vals)
    alpha = GAMMA * b1_vals * t90          # excitation angles
    beta  = GAMMA * b1_vals * t180         # refocusing angles

    # Equilibrium M = [0, 0, 1]
    M = np.zeros((N, 3));  M[:, 2] = 1.0

    # Excitation pulse (Rx)
    Rx = rot_x(alpha)
    M = np.einsum('nij,nj->ni', Rx, M)

    # Pre-compute refocusing rotation
    Ry = rot_y(beta)

    # Relaxation parameters for half-echo
    tau = te / 2
    E2 = np.exp(-tau / t2)
    E1 = np.exp(-tau / t1)
    recov = 1.0 - E1

    echoes = np.empty((N, n_echo))

    for k in range(n_echo):
        # --- free precession  TE/2 ---
        M[:, 0] *= E2;  M[:, 1] *= E2
        M[:, 2] = M[:, 2] * E1 + recov
        # --- 180_y ---
        M = np.einsum('nij,nj->ni', Ry, M)
        # --- free precession  TE/2 ---
        M[:, 0] *= E2;  M[:, 1] *= E2
        M[:, 2] = M[:, 2] * E1 + recov
        # --- echo ---
        echoes[:, k] = np.sqrt(M[:, 0]**2 + M[:, 1]**2)

    return echoes


# ============================================================
# Main
# ============================================================
def main():
    t_start = time.time()

    # ---- 1. Build coils ----
    print("Building modified Helmholtz coil system …")
    coils = build_coil_system()
    print(f"  {len(coils)} individual loops "
          f"({N_TURNS} turns × 4 coils, {N_LAYERS} layers)")

    # ---- 2. B1 field on 3D grid ----
    nx, ny, nz = 25, 25, 40
    x = np.linspace(-FOV_X/2, FOV_X/2, nx)
    y = np.linspace(-FOV_Y/2, FOV_Y/2, ny)
    z = np.linspace(-FOV_Z/2, FOV_Z/2, nz)
    print(f"Computing B1 over FOV ({nx}×{ny}×{nz} = {nx*ny*nz} pts) …")
    Bx, By, Bz = compute_b1_on_grid(coils, x, y, z)

    # B1 transverse to B0 (B0 ∥ z).  Rotating-frame = linear_peak / 2.
    B1_lin = np.sqrt(Bx**2 + By**2)
    B1_rot = B1_lin / 2.0

    ic = (nx//2, ny//2, nz//2)
    B1c = B1_rot[ic]
    print(f"  B1 at centre (rotating frame) : {B1c*1e3:.4f} mT")
    print(f"  Nutation frequency            : {GAMMA_HZ*B1c/1e3:.1f} kHz")

    t90  = (np.pi/2) / (GAMMA * B1c)
    t180 = np.pi     / (GAMMA * B1c)
    print(f"  90° pulse  : {t90*1e6:.2f} µs")
    print(f"  180° pulse : {t180*1e6:.2f} µs")

    # ---- 3. B1 histogram ----
    b1_flat = B1_rot.ravel()
    b1_norm = b1_flat / B1c                    # normalised to centre value

    n_bins = 300
    lo, hi = b1_norm.min() * 0.95, b1_norm.max() * 1.05
    edges = np.linspace(lo, hi, n_bins + 1)
    centres = 0.5 * (edges[:-1] + edges[1:])
    hist_w, _ = np.histogram(b1_norm, bins=edges)

    mask = hist_w > 0
    b1_sim  = centres[mask] * B1c              # absolute B1 for each bin
    weights = hist_w[mask].astype(float)
    weights /= weights.sum()

    flip_exc = np.degrees(GAMMA * b1_sim * t90)
    print(f"  Flip-angle range in FOV : {flip_exc.min():.1f}° – {flip_exc.max():.1f}°")
    print(f"  B1/B1₀ range            : {b1_norm.min():.3f} – {b1_norm.max():.3f}")
    print(f"  Active histogram bins   : {mask.sum()}")

    # ---- 4. CPMG ----
    print(f"Running CPMG simulation ({N_ECHOES} echoes, TE = {TE*1e3:.1f} ms) …")
    epb = simulate_cpmg(b1_sim, B1c, t90, t180, TE, N_ECHOES, T1, T2)

    # Receive weight ∝ B1 (reciprocity) × volume fraction
    rw = b1_sim * weights;  rw /= rw.sum()
    signal = (epb * rw[:, None]).sum(axis=0)

    # Ideal (uniform B1 at centre)
    epb_ideal = simulate_cpmg(np.array([B1c]), B1c, t90, t180, TE, N_ECHOES, T1, T2)

    t_echo = np.arange(1, N_ECHOES + 1) * TE
    t2_ref = np.exp(-t_echo / T2)

    eff0 = signal[0] / epb_ideal[0, 0]
    elapsed = time.time() - t_start
    print(f"  Initial signal efficiency : {eff0:.1%}")
    print(f"  Wall time                 : {elapsed:.1f} s")

    # ============================================================
    # PLOTTING — 8-panel diagnostic figure
    # ============================================================
    fig = plt.figure(figsize=(20, 15))
    gs = GridSpec(3, 3, figure=fig, hspace=0.38, wspace=0.38)

    # 1 — B1 XY plane (z = 0)
    ax = fig.add_subplot(gs[0, 0])
    d = B1_rot[:, :, nz//2].T * 1e3
    im = ax.pcolormesh(x*1e3, y*1e3, d, cmap='inferno', shading='auto')
    ax.set_xlabel('X  (mm) — coil axis');  ax.set_ylabel('Y  (mm)')
    ax.set_title('B₁ (rot. frame)  –  XY midplane')
    ax.set_aspect('equal');  plt.colorbar(im, ax=ax, label='mT')

    # 2 — B1 XZ plane (y = 0)
    ax = fig.add_subplot(gs[0, 1])
    d = B1_rot[:, ny//2, :].T * 1e3
    im = ax.pcolormesh(x*1e3, z*1e3, d, cmap='inferno', shading='auto')
    ax.set_xlabel('X  (mm) — coil axis');  ax.set_ylabel('Z  (mm) — B₀ axis')
    ax.set_title('B₁ (rot. frame)  –  XZ midplane')
    plt.colorbar(im, ax=ax, label='mT')

    # 3 — B1 YZ plane (x = 0)
    ax = fig.add_subplot(gs[0, 2])
    d = B1_rot[nx//2, :, :].T * 1e3
    im = ax.pcolormesh(y*1e3, z*1e3, d, cmap='inferno', shading='auto')
    ax.set_xlabel('Y  (mm)');  ax.set_ylabel('Z  (mm) — B₀ axis')
    ax.set_title('B₁ (rot. frame)  –  YZ midplane')
    plt.colorbar(im, ax=ax, label='mT')

    # 4 — Flip-angle histogram
    ax = fig.add_subplot(gs[1, 0])
    fa_centres = np.degrees(GAMMA * centres[mask] * B1c * t90)
    dfa = np.degrees(GAMMA * (edges[1]-edges[0]) * B1c * t90)
    ax.bar(fa_centres, hist_w[mask]/hist_w[mask].sum(), width=dfa,
           color='steelblue', edgecolor='none', alpha=0.85)
    ax.axvline(90, c='red', ls='--', lw=1.5, label='Nominal 90°')
    ax.set_xlabel('Excitation flip angle  (°)')
    ax.set_ylabel('Volume fraction')
    ax.set_title('Flip-Angle Distribution over FOV')
    ax.legend()

    # 5 — Flip-angle map XZ
    ax = fig.add_subplot(gs[1, 1])
    fa_xz = np.degrees(GAMMA * B1_rot[:, ny//2, :] * t90).T
    im = ax.pcolormesh(x*1e3, z*1e3, fa_xz, cmap='RdYlBu_r',
                       shading='auto', vmin=0, vmax=180)
    ax.set_xlabel('X  (mm) — coil axis');  ax.set_ylabel('Z  (mm) — B₀')
    ax.set_title('Excitation Flip Angle  (°)  –  XZ')
    plt.colorbar(im, ax=ax, label='degrees')

    # 6 — B1 line profiles along principal axes
    ax = fig.add_subplot(gs[1, 2])
    ax.plot(z*1e3, B1_rot[nx//2, ny//2, :]/B1c, 'b-',  lw=2, label='Along Z (B₀)')
    ax.plot(x*1e3, B1_rot[:, ny//2, nz//2]/B1c, 'r-',  lw=2, label='Along X (coil)')
    ax.plot(y*1e3, B1_rot[nx//2, :, nz//2]/B1c, 'g-',  lw=2, label='Along Y')
    ax.axhline(1.0, c='gray', ls=':', alpha=0.4)
    ax.set_xlabel('Position  (mm)');  ax.set_ylabel('B₁ / B₁(centre)')
    ax.set_title('B₁ Homogeneity – Line Profiles')
    ax.legend(fontsize=9);  ax.set_ylim(bottom=0)

    # 7 — CPMG echo train
    ax = fig.add_subplot(gs[2, 0:2])
    ax.plot(t_echo*1e3, signal/signal[0],       'b-',  lw=1.5,
            label='With B₁ inhomogeneity')
    ax.plot(t_echo*1e3, epb_ideal[0]/epb_ideal[0,0], 'r--', lw=1.5,
            label='Ideal (uniform B₁)')
    ax.plot(t_echo*1e3, t2_ref,                 'k:',  lw=1, alpha=.5,
            label=f'Pure T₂ = {T2:.1f} s')
    ax.set_xlabel('Time  (ms)');  ax.set_ylabel('Normalised signal')
    ax.set_title(f'CPMG Echo Train   (TE = {TE*1e3:.1f} ms,  {N_ECHOES} echoes)')
    ax.legend();  ax.set_xlim(0, t_echo[-1]*1e3);  ax.set_ylim(0, 1.05)

    # 8 — Per-bin echo curves (sample of bins)
    ax = fig.add_subplot(gs[2, 2])
    n_show = 8
    idx_show = np.linspace(0, len(b1_sim)-1, n_show, dtype=int)
    for j in idx_show:
        fa_j = np.degrees(GAMMA * b1_sim[j] * t90)
        ax.plot(t_echo*1e3, epb[j]/max(epb[j,0],1e-30),
                lw=1, alpha=0.7, label=f'{fa_j:.0f}°')
    ax.set_xlabel('Time  (ms)');  ax.set_ylabel('Normalised |M_xy|')
    ax.set_title('Echo Trains at Selected Flip Angles')
    ax.legend(fontsize=7, ncol=2, title='Excit. angle')
    ax.set_xlim(0, t_echo[-1]*1e3);  ax.set_ylim(0, 1.05)

    fig.suptitle(
        f'Modified Helmholtz CPMG Simulation\n'
        f'Coils {D_LARGE*1e3:.0f} / {D_SMALL*1e3:.1f} mm  ·  '
        f'{N_TURNS} turns × {I_CURRENT:.0f} A  ·  '
        f'FOV {FOV_X*1e3:.0f}×{FOV_Y*1e3:.0f}×{FOV_Z*1e3:.0f} mm  ·  '
        f'T₁ = {T1} s   T₂ = {T2} s',
        fontsize=13, fontweight='bold', y=1.01)

    plt.show()
    print(f"\nSaved → {out}")

    # ---- Summary ----
    print("\n" + "=" * 62)
    print("  SIMULATION SUMMARY")
    print("=" * 62)
    print(f"  B₁ at centre  (rotating frame) : {B1c*1e3:.4f} mT")
    print(f"  B₁ at centre  (linear peak)    : {2*B1c*1e3:.4f} mT")
    print(f"  Nutation frequency              : {GAMMA_HZ*B1c/1e3:.1f} kHz")
    print(f"  90° pulse duration              : {t90*1e6:.2f} µs")
    print(f"  180° pulse duration             : {t180*1e6:.2f} µs")
    print(f"  Flip-angle range in FOV         : {flip_exc.min():.1f}° – {flip_exc.max():.1f}°")
    print(f"  B₁ / B₁₀  range                : {b1_norm.min():.3f} – {b1_norm.max():.3f}")
    print(f"  Initial signal efficiency       : {eff0:.1%}")
    sig_last = signal[-1] / max(epb_ideal[0, -1], 1e-30)
    print(f"  Final echo efficiency           : {sig_last:.1%}")
    print("=" * 62)


if __name__ == '__main__':
    main()
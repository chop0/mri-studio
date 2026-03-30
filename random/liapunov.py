"""
GRAPE v7: Optimised selective refocusing for low-field MRI.

Architecture:
  - Fixed sinc excitation
  - Echo train: [free τ/2] → [pulse] → [free τ/2] × N_echoes
  - The SAME pulse waveform repeats at every echo
  - GRAPE optimises the pulse shape (B1x, B1y, Gx, Gz per timestep)

Gradient computation (the key optimisation):
  The cost depends on the pulse parameters u through a chain:
    u → pulse_step[pi] → M_post_pulse → free_precession → signal

  For each echo e and each pulse step pi:
    dJ/du[pi] += λ[pi+1]^T · ∂M[pi+1]/∂u[pi]

  where:
    - λ[pi+1] is the adjoint at step pi+1 (backpropagated from the cost)
    - ∂M[pi+1]/∂u[pi] is the single-step sensitivity (computed by FD on ONE step)

  This replaces O(N_pulse) rodrigues_step calls per perturbation with O(1).
  Total rodrigues_step calls: N_echoes × N_pulse × 8 (4 params × 2 for ±ε)
    = 10 × 30 × 8 = 2400  (vs 72300 before = 30× speedup)
"""

import numpy as np
from scipy.special import ellipk, ellipe
from scipy.optimize import minimize
import matplotlib
import matplotlib.pyplot as plt
from matplotlib.gridspec import GridSpec
import time as timer

# ================================================================
# CONSTANTS AND FIELD COMPUTATION
# ================================================================

GAMMA = 267.522e6; MU0 = 4*np.pi*1e-7
R_LARGE = 0.135; R_SMALL = 0.764*0.135; NI = 1000.0
COILS = [(-R_LARGE/2,R_LARGE),(-R_SMALL/2,R_SMALL),
         (+R_SMALL/2,R_SMALL),(+R_LARGE/2,R_LARGE)]
FOV_X = 0.070; FOV_Z = 0.250
GX_MAX = 30e-3; GZ_MAX = 30e-3; B1_MAX = 200e-6
T1 = 300e-3; T2 = 100e-3

def biot_savart(rho, z, z0, R):
    dz = z - z0
    a2 = np.maximum(R**2 + rho**2 + dz**2 - 2*R*rho, 1e-30)
    b2 = np.maximum(R**2 + rho**2 + dz**2 + 2*R*rho, 1e-30)
    b = np.sqrt(b2); k2 = np.clip(4*R*rho / b2, 0, 1-1e-12)
    K, E = ellipk(k2), ellipe(k2)
    C = MU0 * NI / (2*np.pi)
    Bz = C/b * (K + (R**2 - rho**2 - dz**2)/a2 * E)
    Br = np.zeros_like(rho); m = rho > 1e-9
    if np.any(m):
        Br[m] = C*dz[m]/(rho[m]*b[m]) * (
            -K[m] + (R**2 + rho[m]**2 + dz[m]**2)/a2[m] * E[m])
    return Br, Bz

def compute_B0(X, Z):
    rho = np.abs(X); Bx = np.zeros_like(X); Bz = np.zeros_like(Z)
    for z0, R in COILS:
        Br, Bzc = biot_savart(rho, Z, z0, R)
        Bx += Br * np.sign(X + 1e-30); Bz += Bzc
    return Bx, Bz


# ================================================================
# BLOCH PROPAGATION
# ================================================================

# Module-level relaxation constants (set once via set_dt)
_E2 = _E1 = None

def set_dt(dt):
    global _E2, _E1
    _E2 = np.exp(-dt / T2)
    _E1 = np.exp(-dt / T1)

def rodrigues(Bx, By, Bz, Mx, My, Mz, dt):
    """Full Rodrigues rotation + T1/T2 relaxation. One timestep."""
    Bm = np.sqrt(Bx**2 + By**2 + Bz**2)
    th = GAMMA * Bm * dt
    safe = Bm > 1e-30
    nx = np.where(safe, Bx/np.maximum(Bm, 1e-30), 0.)
    ny = np.where(safe, By/np.maximum(Bm, 1e-30), 0.)
    nz = np.where(safe, Bz/np.maximum(Bm, 1e-30), 1.)
    c, s, omc = np.cos(th), np.sin(th), 1 - np.cos(th)
    ndM = nx*Mx + ny*My + nz*Mz
    cx, cy, cz = ny*Mz - nz*My, nz*Mx - nx*Mz, nx*My - ny*Mx
    return ((Mx*c + cx*s + nx*ndM*omc) * _E2,
            (My*c + cy*s + ny*ndM*omc) * _E2,
            1 + (Mz*c + cz*s + nz*ndM*omc - 1) * _E1)

def z_precession(Mx, My, Mz, omega, dt):
    """z-rotation + relaxation. Faster than rodrigues when B1=0."""
    th = omega * dt; c, s = np.cos(th), np.sin(th)
    return (Mx*c - My*s)*_E2, (Mx*s + My*c)*_E2, 1 + (Mz-1)*_E1

def free_evolve(Mx, My, Mz, omega, dt, N):
    """Propagate N z-precession steps."""
    for _ in range(N):
        Mx, My, Mz = z_precession(Mx, My, Mz, omega, dt)
    return Mx, My, Mz

def free_evolve_adjoint(lx, ly, lz, omega, dt, N):
    """Backpropagate adjoint through N z-precession steps."""
    for _ in range(N):
        lx2, ly2 = lx*_E2, ly*_E2
        th = omega * dt; c, s = np.cos(th), np.sin(th)
        lx, ly, lz = lx2*c + ly2*s, -lx2*s + ly2*c, lz*_E1
    return lx, ly, lz

def rodrigues_adjoint_step(Bx, By, Bz, lx, ly, lz, dt):
    """Backpropagate adjoint through one Rodrigues step: relax then R^T."""
    lx2, ly2, lz2 = lx*_E2, ly*_E2, lz*_E1
    Bm = np.sqrt(Bx**2 + By**2 + Bz**2)
    th = GAMMA * Bm * dt
    safe = Bm > 1e-30
    nx = np.where(safe, Bx/np.maximum(Bm, 1e-30), 0.)
    ny = np.where(safe, By/np.maximum(Bm, 1e-30), 0.)
    nz = np.where(safe, Bz/np.maximum(Bm, 1e-30), 1.)
    c, s, omc = np.cos(th), np.sin(th), 1 - np.cos(th)
    ndL = nx*lx2 + ny*ly2 + nz*lz2
    cx, cy, cz = ny*lz2 - nz*ly2, nz*lx2 - nx*lz2, nx*ly2 - ny*lx2
    return (lx2*c - cx*s + nx*ndL*omc,
            ly2*c - cy*s + ny*ndL*omc,
            lz2*c - cz*s + nz*ndL*omc)

def signal_mag(Mx, My, w):
    return np.abs(np.sum(w * (Mx + 1j*My)))


# ================================================================
# EXCITATION
# ================================================================

def excite_sinc(dBz, Gxm, Gzm, B1s, shape, dt_exc, Gz_ss, T_exc, TBW):
    """Sinc slice-selective excitation + rephasing lobe."""
    N = int(T_exc / dt_exc)
    t = np.linspace(-T_exc/2, T_exc/2, N)
    rf = np.sinc(t / (T_exc/TBW)) * (0.54 + 0.46*np.cos(2*np.pi*t/T_exc))
    rf *= (np.pi/2) / (GAMMA * np.sum(rf) * dt_exc)

    set_dt(dt_exc)
    Mx, My, Mz = np.zeros(shape), np.zeros(shape), np.ones(shape)
    Bzf = dBz + Gz_ss * Gzm
    for k in range(N):
        Mx, My, Mz = rodrigues(rf[k]*B1s, np.zeros(shape), Bzf, Mx, My, Mz, dt_exc)
    # Rephasing lobe
    omr = GAMMA * (dBz - Gz_ss * Gzm)
    for k in range(N//2):
        Mx, My, Mz = z_precession(Mx, My, Mz, omr, dt_exc)
    return Mx, My, Mz


# ================================================================
# GRAPE COST AND GRADIENT
# ================================================================

def grape_cost_grad(pulse_flat, N_pulse, N_echoes, N_fh, dt,
                    Mx_exc, My_exc, Mz_exc,
                    dBz, Gxm, Gzm, B1s, w_in, w_out,
                    lam_out, rf_pen, omega_free):
    """
    Compute cost and gradient for the shared refocusing pulse.

    The echo train is: [free N_fh] → [pulse N_pulse] → [free N_fh] × N_echoes

    Strategy:
      Forward:  store state at each echo boundary AND within each pulse
      Backward: for each echo (reverse order):
        1. Backprop adjoint through post-pulse free precession (analytical)
        2. For each pulse step (reverse order):
           a. Single-step FD: perturb u[pi], do ONE rodrigues, get ∂M/∂u
           b. Contract with adjoint: grad[pi] += λ^T · ∂M/∂u
           c. Backprop adjoint through this step (analytical Rodrigues adjoint)
        3. Backprop adjoint through pre-pulse free precession (analytical)
        4. Add running cost contribution to adjoint
    """
    pulse = pulse_flat.reshape(N_pulse, 4)
    sh = Mx_exc.shape

    # ---- Forward pass ----
    # Store pre-pulse and post-pulse states at each echo,
    # plus intermediate states within each pulse application.
    # pulse_states[e] is a list of N_pulse+1 states (Mx,My,Mz) within echo e's pulse.

    echo_pre = []       # state just before each pulse
    pulse_states = []   # intermediate states within each pulse
    echo_post = []      # state just after each pulse + free τ/2 (echo peak)

    Mx, My, Mz = Mx_exc.copy(), My_exc.copy(), Mz_exc.copy()
    J = 0.0

    for e in range(N_echoes):
        # Free precession τ/2
        Mx, My, Mz = free_evolve(Mx, My, Mz, omega_free, dt, N_fh)
        echo_pre.append((Mx.copy(), My.copy(), Mz.copy()))

        # Pulse: store all intermediate states
        states_e = [(Mx.copy(), My.copy(), Mz.copy())]
        for pi in range(N_pulse):
            b1x, b1y, ux, uz = pulse[pi]
            Bxf = b1x * B1s; Byf = b1y * B1s
            Bzf = dBz + ux*Gxm + uz*Gzm
            Mx, My, Mz = rodrigues(Bxf, Byf, Bzf, Mx, My, Mz, dt)
            states_e.append((Mx.copy(), My.copy(), Mz.copy()))
        pulse_states.append(states_e)

        # Free precession τ/2
        Mx, My, Mz = free_evolve(Mx, My, Mz, omega_free, dt, N_fh)
        echo_post.append((Mx.copy(), My.copy(), Mz.copy()))

        # Accumulate signal cost at echo peak
        Sx, Sy = np.sum(w_in * Mx), np.sum(w_in * My)
        J += Sx**2 + Sy**2

    # Out-of-slice penalty (final state)
    J -= lam_out * np.sum(w_out * (Mx**2 + My**2))

    # RF penalty
    J -= rf_pen * np.sum(pulse[:,0]**2 + pulse[:,1]**2) * dt * N_echoes

    # ---- Backward pass ----
    grad = np.zeros((N_pulse, 4))

    # Terminal adjoint
    Sx_f, Sy_f = np.sum(w_in * Mx), np.sum(w_in * My)
    lx = 2*Sx_f*w_in - 2*lam_out*w_out*Mx
    ly = 2*Sy_f*w_in - 2*lam_out*w_out*My
    lz = np.zeros(sh)

    EPS = [5e-8, 5e-8, 5e-7, 5e-7]  # FD step sizes for B1x, B1y, Gx, Gz

    for e in range(N_echoes - 1, -1, -1):
        # 1. Backprop adjoint through post-pulse free precession
        lx, ly, lz = free_evolve_adjoint(lx, ly, lz, omega_free, dt, N_fh)

        # 2. Process each pulse step (reverse order)
        for pi in range(N_pulse - 1, -1, -1):
            # State before this step
            Mx_k, My_k, Mz_k = pulse_states[e][pi]
            b1x, b1y, ux, uz = pulse[pi]
            Bxf = b1x * B1s; Byf = b1y * B1s
            Bzf = dBz + ux*Gxm + uz*Gzm

            # 2a. Single-step FD for each control parameter
            #     Perturb ONE rodrigues step, contract with adjoint
            for p in range(4):
                eps = EPS[p]

                # Build perturbed fields (only the component being perturbed changes)
                if p == 0:    # B1x
                    Mp = rodrigues(Bxf + eps*B1s, Byf, Bzf, Mx_k, My_k, Mz_k, dt)
                    Mm = rodrigues(Bxf - eps*B1s, Byf, Bzf, Mx_k, My_k, Mz_k, dt)
                elif p == 1:  # B1y
                    Mp = rodrigues(Bxf, Byf + eps*B1s, Bzf, Mx_k, My_k, Mz_k, dt)
                    Mm = rodrigues(Bxf, Byf - eps*B1s, Bzf, Mx_k, My_k, Mz_k, dt)
                elif p == 2:  # Gx
                    Mp = rodrigues(Bxf, Byf, Bzf + eps*Gxm, Mx_k, My_k, Mz_k, dt)
                    Mm = rodrigues(Bxf, Byf, Bzf - eps*Gxm, Mx_k, My_k, Mz_k, dt)
                else:         # Gz
                    Mp = rodrigues(Bxf, Byf, Bzf + eps*Gzm, Mx_k, My_k, Mz_k, dt)
                    Mm = rodrigues(Bxf, Byf, Bzf - eps*Gzm, Mx_k, My_k, Mz_k, dt)

                # dJ/du[pi,p] = λ^T · (M+ - M-) / (2ε)
                grad[pi, p] += np.sum(
                    lx*(Mp[0]-Mm[0]) + ly*(Mp[1]-Mm[1]) + lz*(Mp[2]-Mm[2])
                ) / (2*eps)

            # 2b. Backprop adjoint through this step (Rodrigues adjoint)
            lx, ly, lz = rodrigues_adjoint_step(Bxf, Byf, Bzf, lx, ly, lz, dt)

        # 3. Backprop adjoint through pre-pulse free precession
        lx, ly, lz = free_evolve_adjoint(lx, ly, lz, omega_free, dt, N_fh)

        # 4. Add running cost contribution from previous echo peak
        if e > 0:
            Mx_ep, My_ep = echo_post[e-1][0], echo_post[e-1][1]
            Sx_ep, Sy_ep = np.sum(w_in*Mx_ep), np.sum(w_in*My_ep)
            lx += 2*Sx_ep*w_in
            ly += 2*Sy_ep*w_in

    # RF penalty gradient
    grad[:, 0] -= 2 * rf_pen * pulse[:, 0] * dt * N_echoes
    grad[:, 1] -= 2 * rf_pen * pulse[:, 1] * dt * N_echoes

    return -J, -grad.ravel()


# ================================================================
# SIMULATION (for evaluation and plotting)
# ================================================================

def simulate_full(pulse, N_echoes, N_fh, dt, Mx0, My0, Mz0,
                  dBz, Gxm, Gzm, B1s, w_in, omega_free):
    """Simulate full echo train, return signal at every substep."""
    N_pulse = pulse.shape[0]
    N_per_echo = N_fh + N_pulse + N_fh
    sig = np.zeros(N_echoes * N_per_echo + 1)
    sig[0] = signal_mag(Mx0, My0, w_in)
    Mx, My, Mz = Mx0.copy(), My0.copy(), Mz0.copy()
    idx = 1
    for e in range(N_echoes):
        for _ in range(N_fh):
            Mx, My, Mz = z_precession(Mx, My, Mz, omega_free, dt)
            sig[idx] = signal_mag(Mx, My, w_in); idx += 1
        for pi in range(N_pulse):
            b1x, b1y, ux, uz = pulse[pi]
            Mx, My, Mz = rodrigues(
                b1x*B1s, b1y*B1s, dBz+ux*Gxm+uz*Gzm, Mx, My, Mz, dt)
            sig[idx] = signal_mag(Mx, My, w_in); idx += 1
        for _ in range(N_fh):
            Mx, My, Mz = z_precession(Mx, My, Mz, omega_free, dt)
            sig[idx] = signal_mag(Mx, My, w_in); idx += 1
    return sig[:idx], Mx, My, Mz


# ================================================================
# MAIN
# ================================================================

def run():
    t0 = timer.time()
    print("=" * 65)
    print("  GRAPE v7: Optimised selective refocusing (fast gradient)")
    print("=" * 65)

    # ---- Setup ----
    Nx, Nz = 20, 80
    x = np.linspace(-FOV_X/2, FOV_X/2, Nx)
    z = np.linspace(-FOV_Z/2, FOV_Z/2, Nz)
    X, Z = np.meshgrid(x, z, indexing='ij')
    Bx0, Bz0 = compute_B0(X, Z)
    B0n = Bz0[Nx//2, Nz//2]
    dBz = (Bz0 - B0n) + Bx0**2 / (2*B0n)
    Gxm = X + Z**2 / (2*B0n)
    Gzm = Z + (X/2)**2 / (2*B0n)
    B1s = 1 + 0.12*(X/FOV_X*2)**2 + 0.08*(Z/FOV_Z*2)**2

    sw = 0.010
    mask_in = np.abs(Z) < sw/2
    w_in = mask_in.astype(float)
    w_out = (~mask_in).astype(float)
    s_max = np.sum(w_in)
    print(f"\nGrid: {Nx}×{Nz}   B0={B0n*1e3:.3f} mT   S_max={s_max:.0f}")

    # ---- Excitation ----
    Mx_exc, My_exc, Mz_exc = excite_sinc(
        dBz, Gxm, Gzm, B1s, (Nx,Nz), 2e-6, GZ_MAX, 400e-6, 2)
    Mp_exc = np.sqrt(Mx_exc**2 + My_exc**2)
    s0 = signal_mag(Mx_exc, My_exc, w_in)
    print(f"Excitation: S₀/Smax = {s0/s_max:.3f}")

    # ---- Refocusing parameters ----
    dt = 10e-6
    tau = 1e-3              # echo spacing
    N_echoes = 10
    T_pulse = 300e-6        # pulse duration
    N_pulse = int(T_pulse/dt)   # 30 steps
    N_fh = (int(tau/dt) - N_pulse) // 2  # free precession half-echo
    omega_free = GAMMA * dBz
    set_dt(dt)

    print(f"\nRefocusing: {N_echoes} echoes × {tau*1e3:.0f}ms")
    print(f"Pulse: {N_pulse} steps × {dt*1e6:.0f}μs = {T_pulse*1e6:.0f}μs")
    print(f"Free half: {N_fh} steps = {N_fh*dt*1e6:.0f}μs")
    print(f"GRAPE variables: {N_pulse * 4}")

    # ---- Build warm starts ----
    # Sinc selective refocusing pulse
    N_rf = N_pulse * 2 // 3  # 2/3 for RF, 1/3 for gradient rephase
    t_rf = np.linspace(-N_rf*dt/2, N_rf*dt/2, N_rf)
    T_rf = N_rf * dt
    env = np.sinc(t_rf / (T_rf/2)) * (0.54 + 0.46*np.cos(2*np.pi*t_rf/T_rf))
    env *= np.pi / (GAMMA * np.sum(np.abs(env)) * dt)
    env = np.clip(env, -B1_MAX, B1_MAX)

    pulse_sinc = np.zeros((N_pulse, 4))
    pulse_sinc[:N_rf, 0] = env          # B1x during RF
    pulse_sinc[:N_rf, 3] = GZ_MAX       # Gz during RF
    pulse_sinc[N_rf:, 3] = -GZ_MAX      # Gz rephasing lobe

    # Hard π (no gradient, for comparison)
    pulse_hard = np.zeros((N_pulse, 4))
    B1_pi = np.pi / (GAMMA * N_pulse * dt)
    pulse_hard[:, 0] = min(B1_pi, B1_MAX)

    # Evaluate warm starts
    sig_sinc, Mx_sinc, My_sinc, _ = simulate_full(
        pulse_sinc, N_echoes, N_fh, dt,
        Mx_exc, My_exc, Mz_exc, dBz, Gxm, Gzm, B1s, w_in, omega_free)
    sig_hard, Mx_hard, My_hard, _ = simulate_full(
        pulse_hard, N_echoes, N_fh, dt,
        Mx_exc, My_exc, Mz_exc, dBz, Gxm, Gzm, B1s, w_in, omega_free)
    sig_free, Mx_free, My_free, _ = simulate_full(
        np.zeros((N_pulse,4)), N_echoes, N_fh, dt,
        Mx_exc, My_exc, Mz_exc, dBz, Gxm, Gzm, B1s, w_in, omega_free)

    def selectivity(Mx, My):
        Mp2 = Mx**2 + My**2
        ip, op = np.sum(w_in*Mp2), np.sum(w_out*Mp2)
        return ip / (ip + op) if (ip+op) > 0 else 0

    print(f"\nWarm starts:")
    print(f"  Sinc selective: <sig>={np.mean(sig_sinc)/s_max:.4f}  sel={selectivity(Mx_sinc,My_sinc):.3f}")
    print(f"  Hard π:         <sig>={np.mean(sig_hard)/s_max:.4f}  sel={selectivity(Mx_hard,My_hard):.3f}")

    # ---- GRAPE ----
    print(f"\n--- GRAPE ---")
    bounds = [(-B1_MAX,B1_MAX), (-B1_MAX,B1_MAX),
              (-GX_MAX,GX_MAX), (-GZ_MAX,GZ_MAX)] * N_pulse
    lam_out = 200.0
    rf_pen = 1e8

    log = []
    def callback(xk):
        log.append(1)
        if len(log) % 5 == 0:
            p = xk.reshape(N_pulse, 4)
            s, Mxe, Mye, _ = simulate_full(
                p, N_echoes, N_fh, dt,
                Mx_exc, My_exc, Mz_exc, dBz, Gxm, Gzm, B1s, w_in, omega_free)
            sel = selectivity(Mxe, Mye)
            print(f"  iter {len(log):3d}  <sig>={np.mean(s)/s_max:.4f}  "
                  f"sel={sel:.3f}  [{timer.time()-t0:.0f}s]")

    t_grape = timer.time()
    result = minimize(
        grape_cost_grad, pulse_sinc.ravel(),
        args=(N_pulse, N_echoes, N_fh, dt,
              Mx_exc, My_exc, Mz_exc,
              dBz, Gxm, Gzm, B1s, w_in, w_out, lam_out, rf_pen, omega_free),
        method='L-BFGS-B', jac=True, bounds=bounds,
        options={'maxiter': 150, 'ftol': 1e-15, 'gtol': 1e-10},
        callback=callback)
    t_grape_elapsed = timer.time() - t_grape

    pulse_opt = result.x.reshape(N_pulse, 4)
    sig_grape, Mx_grape, My_grape, _ = simulate_full(
        pulse_opt, N_echoes, N_fh, dt,
        Mx_exc, My_exc, Mz_exc, dBz, Gxm, Gzm, B1s, w_in, omega_free)

    sel_grape = selectivity(Mx_grape, My_grape)
    avg_grape = np.mean(sig_grape) / s_max
    print(f"\n{result.nit} iters in {t_grape_elapsed:.1f}s "
          f"({t_grape_elapsed/max(result.nit,1):.1f}s/iter)")
    print(f"GRAPE: <sig>={avg_grape:.4f}  sel={sel_grape:.3f}")

    # ---- Results ----
    scenarios = [
        ('GRAPE', sig_grape, Mx_grape, My_grape, pulse_opt, '#2ca02c', '-', 3),
        ('Sinc selective', sig_sinc, Mx_sinc, My_sinc, pulse_sinc, 'blue', '-.', 2),
        ('Hard π', sig_hard, Mx_hard, My_hard, pulse_hard, 'orange', '--', 1.5),
        ('Free', sig_free, Mx_free, My_free, np.zeros((N_pulse,4)), 'red', '-', 1.2),
    ]

    print(f"\n{'Scenario':>18s} {'<sig>/Smax':>11s} {'final':>8s} {'sel':>6s} {'out <M⊥>':>10s}")
    print("-" * 56)
    for name, sig, Mxf, Myf, _, _, _, _ in scenarios:
        Mpf = np.sqrt(Mxf**2 + Myf**2)
        sel = selectivity(Mxf, Myf)
        print(f"  {name:16s} {np.mean(sig)/s_max:11.4f} {sig[-1]/s_max:8.4f} "
              f"{sel:6.3f} {np.mean(Mpf[~mask_in]):10.4f}")

    # ---- Plots ----
    print("\nPlotting ...")
    ix_mid = Nx // 2
    fig = plt.figure(figsize=(17, 30))
    gs = GridSpec(8, 2, figure=fig, hspace=0.42, wspace=0.3)

    # Row 0: excitation
    ax = fig.add_subplot(gs[0, 0])
    ax.plot(z*1e3, Mp_exc[ix_mid, :], 'k-', lw=2)
    ax.axvspan(-sw/2*1e3, sw/2*1e3, color='green', alpha=0.1, label='Target')
    ax.set_xlabel('z [mm]'); ax.set_ylabel('$|M_\\perp|$')
    ax.set_title('Excitation profile'); ax.legend(); ax.grid(alpha=0.3)
    ax.set_ylim(-0.05, 1.15)

    ax = fig.add_subplot(gs[0, 1])
    im = ax.pcolormesh(z*1e3, x*1e3, Mp_exc, cmap='inferno', shading='auto', vmin=0, vmax=1)
    ax.set_xlabel('z'); ax.set_ylabel('x'); ax.set_title('Excitation 2D')
    plt.colorbar(im, ax=ax)
    for zz in [-sw/2, sw/2]: ax.axvline(x=zz*1e3, color='w', ls='--', lw=1)

    # Row 1: signal comparison
    ax = fig.add_subplot(gs[1, :])
    for name, sig, _, _, _, col, ls, lw in scenarios:
        t_sig = np.arange(len(sig)) * dt * 1e3
        ax.plot(t_sig, sig/s_max, color=col, ls=ls, lw=lw, alpha=0.85, label=name)
    t_T2 = np.arange(len(sig_grape)) * dt
    ax.plot(t_T2*1e3, np.exp(-t_T2/T2), 'gray', ls='-.', lw=1, alpha=0.3, label='T₂')
    ax.set_xlabel('t [ms]'); ax.set_ylabel('signal / $S_{max}$')
    ax.set_title('In-slice signal'); ax.legend(fontsize=8); ax.grid(alpha=0.3)
    ax.set_ylim(-0.05, 1.05)

    # Row 2: final profiles + refocusing efficiency
    ax = fig.add_subplot(gs[2, 0])
    for name, _, Mxf, Myf, _, col, ls, lw in scenarios:
        Mpf = np.sqrt(Mxf**2 + Myf**2)
        ax.plot(z*1e3, Mpf[ix_mid, :], color=col, ls=ls, lw=lw, label=name)
    ax.axvspan(-sw/2*1e3, sw/2*1e3, color='green', alpha=0.1)
    ax.set_xlabel('z [mm]'); ax.set_ylabel('$|M_\\perp|$')
    ax.set_title('Final $|M_\\perp|$ profile'); ax.legend(fontsize=7); ax.grid(alpha=0.3)
    ax.set_ylim(-0.05, 1.15)

    ax = fig.add_subplot(gs[2, 1])
    for name, sig, _, _, _, col, ls, lw in scenarios[:3]:
        t_sig = np.arange(len(sig)) * dt
        T2_sig = s_max * np.exp(-t_sig/T2)
        ax.plot(t_sig*1e3, sig/np.maximum(T2_sig, 1e-10),
                color=col, ls=ls, lw=lw, label=name)
    ax.axhline(1, color='gray', ls='-.', alpha=0.5)
    ax.set_xlabel('t [ms]'); ax.set_ylabel('sig / T₂')
    ax.set_title('Refocusing efficiency'); ax.legend(fontsize=8); ax.grid(alpha=0.3)
    ax.set_ylim(-0.05, 1.15)

    # Row 3: 2D final maps
    gs3 = gs[3, :].subgridspec(1, 3, wspace=0.3)
    for j, (name, _, Mxf, Myf, _, col, _, _) in enumerate(scenarios[:3]):
        Mpf = np.sqrt(Mxf**2 + Myf**2)
        sel = selectivity(Mxf, Myf)
        ax = fig.add_subplot(gs3[0, j])
        im = ax.pcolormesh(z*1e3, x*1e3, Mpf, cmap='inferno', shading='auto', vmin=0, vmax=1)
        ax.set_xlabel('z'); ax.set_title(f'{name}\nsel={sel:.2f}', fontsize=9)
        plt.colorbar(im, ax=ax, shrink=0.85)
        if j == 0: ax.set_ylabel('x')
        for zz in [-sw/2, sw/2]: ax.axvline(x=zz*1e3, color='w', ls='--', lw=0.8)

    # Row 4: pulse waveforms
    t_p = np.arange(N_pulse) * dt * 1e6
    ax = fig.add_subplot(gs[4, 0])
    ax.plot(t_p, pulse_sinc[:, 0]*1e6, 'b--', lw=1.5, label='Sinc B1x')
    ax.plot(t_p, pulse_opt[:, 0]*1e6, 'g-', lw=2, label='GRAPE B1x')
    ax.plot(t_p, pulse_opt[:, 1]*1e6, 'r-', lw=2, label='GRAPE B1y')
    ax.set_xlabel('t [μs]'); ax.set_ylabel('B₁ [μT]')
    ax.set_title('Refocusing pulse: RF'); ax.legend(fontsize=8); ax.grid(alpha=0.3)

    ax = fig.add_subplot(gs[4, 1])
    ax.plot(t_p, pulse_sinc[:, 3]*1e3, 'b--', lw=1.5, label='Sinc Gz')
    ax.plot(t_p, pulse_opt[:, 2]*1e3, 'g-', lw=2, label='GRAPE Gx')
    ax.plot(t_p, pulse_opt[:, 3]*1e3, 'r-', lw=2, label='GRAPE Gz')
    ax.set_xlabel('t [μs]'); ax.set_ylabel('mT/m')
    ax.set_title('Refocusing pulse: gradients'); ax.legend(fontsize=8); ax.grid(alpha=0.3)

    # Row 5: inversion profile (apply pulse to Mz=1)
    ax = fig.add_subplot(gs[5, 0])
    for name, _, _, _, p, col, ls, lw in scenarios[:3]:
        set_dt(dt)
        Mxt, Myt, Mzt = np.zeros((Nx,Nz)), np.zeros((Nx,Nz)), np.ones((Nx,Nz))
        for pi in range(N_pulse):
            b1x,b1y,ux,uz = p[pi]
            Mxt,Myt,Mzt = rodrigues(b1x*B1s,b1y*B1s,dBz+ux*Gxm+uz*Gzm,Mxt,Myt,Mzt,dt)
        ax.plot(z*1e3, Mzt[ix_mid,:], color=col, ls=ls, lw=lw, label=name)
    ax.axvspan(-sw/2*1e3, sw/2*1e3, color='green', alpha=0.1)
    ax.axhline(-1, color='gray', ls=':', alpha=0.3); ax.axhline(1, color='gray', ls=':', alpha=0.3)
    ax.set_xlabel('z [mm]'); ax.set_ylabel('$M_z$')
    ax.set_title('Inversion profile (from $M_z=1$)'); ax.legend(fontsize=7); ax.grid(alpha=0.3)
    ax.set_ylim(-1.15, 1.15)

    ax = fig.add_subplot(gs[5, 1])
    for name, _, _, _, p, col, ls, lw in scenarios[:3]:
        set_dt(dt)
        Mxt, Myt, Mzt = np.zeros((Nx,Nz)), np.zeros((Nx,Nz)), np.ones((Nx,Nz))
        for pi in range(N_pulse):
            b1x,b1y,ux,uz = p[pi]
            Mxt,Myt,Mzt = rodrigues(b1x*B1s,b1y*B1s,dBz+ux*Gxm+uz*Gzm,Mxt,Myt,Mzt,dt)
        Mpt = np.sqrt(Mxt**2 + Myt**2)
        ax.plot(z*1e3, Mpt[ix_mid,:], color=col, ls=ls, lw=lw, label=name)
    ax.axvspan(-sw/2*1e3, sw/2*1e3, color='green', alpha=0.1)
    ax.set_xlabel('z [mm]'); ax.set_ylabel('$|M_\\perp|$ created')
    ax.set_title('Spurious excitation from $M_z=1$'); ax.legend(fontsize=7); ax.grid(alpha=0.3)
    ax.set_ylim(-0.05, 1.15)

    # Row 6: scatter plot + cumulative mean
    ax = fig.add_subplot(gs[6, 0])
    for name, sig, Mxf, Myf, _, col, _, _ in scenarios:
        sel = selectivity(Mxf, Myf)
        ax.scatter(sel, np.mean(sig)/s_max, c=col, s=150, zorder=5, edgecolors='k', linewidths=0.5)
        ax.annotate(name, (sel, np.mean(sig)/s_max),
                    textcoords='offset points', xytext=(8, 4), fontsize=8)
    ax.set_xlabel('Selectivity'); ax.set_ylabel('<sig> / S_max')
    ax.set_title('Signal vs Selectivity'); ax.grid(alpha=0.3)

    ax = fig.add_subplot(gs[6, 1])
    for name, sig, _, _, _, col, ls, lw in scenarios:
        t_sig = np.arange(len(sig)) * dt * 1e3
        ca = np.cumsum(sig) / (np.arange(len(sig)) + 1) / s_max
        ax.plot(t_sig, ca, color=col, ls=ls, lw=lw, label=name)
    ax.set_xlabel('t [ms]'); ax.set_ylabel('cumul mean / $S_{max}$')
    ax.set_title('Time-averaged signal'); ax.legend(fontsize=7); ax.grid(alpha=0.3)
    ax.set_ylim(0, 1.05)

    # Row 7: summary
    ax = fig.add_subplot(gs[7, :]); ax.axis('off')
    txt = f"GRAPE v7: Optimised selective refocusing\n\n"
    txt += f"Pulse: {N_pulse} steps × {dt*1e6:.0f}μs = {T_pulse*1e6:.0f}μs\n"
    txt += f"GRAPE: {result.nit} iters in {t_grape_elapsed:.0f}s "
    txt += f"({t_grape_elapsed/max(result.nit,1):.1f}s/iter)\n\n"
    txt += f"{'':>18s} {'Signal':>8s} {'Select':>8s} {'Out M⊥':>8s}\n" + "-"*46 + "\n"
    for name, sig, Mxf, Myf, _, _, _, _ in scenarios:
        Mpf = np.sqrt(Mxf**2 + Myf**2)
        sel = selectivity(Mxf, Myf)
        txt += f"{name:>18s} {np.mean(sig)/s_max:8.3f} {sel:8.3f} {np.mean(Mpf[~mask_in]):8.4f}\n"
    ax.text(0.05, 0.95, txt, transform=ax.transAxes, fontsize=11,
            va='top', family='monospace',
            bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))

    fig.suptitle(f'GRAPE v7: Optimised selective refocusing\n'
                 f'B₀={B0n*1e3:.1f}mT  {N_echoes} echoes  '
                 f'{result.nit} iters  {t_grape_elapsed:.0f}s',
                 fontsize=13, fontweight='bold', y=1.003)

    plt.show()


if __name__ == '__main__':
    run()
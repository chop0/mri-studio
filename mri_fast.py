import time
from dataclasses import dataclass

import jax
import jax.numpy as jnp
import numpy as np
from scipy.optimize import minimize
from scipy.special import ellipk, ellipe


GAMMA = 267.522e6
MU0 = 4 * np.pi * 1e-7
R_LARGE = 0.135
R_SMALL = 0.764 * 0.135
NI = 1000.0
COILS = [
    (-R_LARGE / 2, R_LARGE),
    (-R_SMALL / 2, R_SMALL),
    (+R_SMALL / 2, R_SMALL),
    (+R_LARGE / 2, R_LARGE),
]
FOV_X = 0.070
FOV_Z = 0.250
GX_MAX = 30e-3
GZ_MAX = 30e-3
B1_MAX = 200e-6
T1 = 300e-3
T2 = 100e-3


@dataclass
class ProblemNP:
    Mx0: np.ndarray
    My0: np.ndarray
    Mz0: np.ndarray
    dBz: np.ndarray
    Gxm: np.ndarray
    Gzm: np.ndarray
    B1s: np.ndarray
    w_in: np.ndarray
    w_out: np.ndarray
    s_max: float


@dataclass
class ProblemJAX:
    Mx0: jnp.ndarray
    My0: jnp.ndarray
    Mz0: jnp.ndarray
    dBz: jnp.ndarray
    Gxm: jnp.ndarray
    Gzm: jnp.ndarray
    B1s: jnp.ndarray
    w_in: jnp.ndarray
    w_out: jnp.ndarray
    omega_free: jnp.ndarray
    s_max: float
    nx: int
    nz: int


@dataclass
class MaskedOptimizationResult:
    x_full: np.ndarray
    nit: int
    nfev: int
    success: bool
    message: str
    snapshots: dict[int, np.ndarray]


def biot_savart(rho, z, z0, R):
    dz = z - z0
    a2 = np.maximum(R**2 + rho**2 + dz**2 - 2 * R * rho, 1e-30)
    b2 = np.maximum(R**2 + rho**2 + dz**2 + 2 * R * rho, 1e-30)
    b = np.sqrt(b2)
    k2 = np.clip(4 * R * rho / b2, 0.0, 1.0 - 1e-12)
    K = ellipk(k2)
    E = ellipe(k2)
    C = MU0 * NI / (2 * np.pi)
    Bz = C / b * (K + (R**2 - rho**2 - dz**2) / a2 * E)
    Br = np.zeros_like(rho)
    m = rho > 1e-9
    if np.any(m):
        Br[m] = (
            C
            * dz[m]
            / (rho[m] * b[m])
            * (-K[m] + (R**2 + rho[m] ** 2 + dz[m] ** 2) / a2[m] * E[m])
        )
    return Br, Bz


def compute_B0(X, Z):
    rho = np.abs(X)
    Bx = np.zeros_like(X)
    Bz = np.zeros_like(Z)
    for z0, R in COILS:
        Br, Bzc = biot_savart(rho, Z, z0, R)
        Bx += Br * np.sign(X + 1e-30)
        Bz += Bzc
    return Bx, Bz


def rodrigues_numpy(Bx, By, Bz, Mx, My, Mz, dt, E1, E2):
    Bm = np.sqrt(Bx * Bx + By * By + Bz * Bz + 1e-30)
    nx = Bx / Bm
    ny = By / Bm
    nz = Bz / Bm
    th = GAMMA * Bm * dt
    c = np.cos(th)
    s = np.sin(th)
    omc = 1.0 - c
    ndM = nx * Mx + ny * My + nz * Mz
    cx = ny * Mz - nz * My
    cy = nz * Mx - nx * Mz
    cz = nx * My - ny * Mx
    return (
        (Mx * c + cx * s + nx * ndM * omc) * E2,
        (My * c + cy * s + ny * ndM * omc) * E2,
        1.0 + (Mz * c + cz * s + nz * ndM * omc - 1.0) * E1,
    )


def z_step_numpy(Mx, My, Mz, omega, dt, E1, E2):
    th = omega * dt
    c = np.cos(th)
    s = np.sin(th)
    return (
        (Mx * c - My * s) * E2,
        (Mx * s + My * c) * E2,
        1.0 + (Mz - 1.0) * E1,
    )


def excite_sinc_numpy(dBz, Gzm, B1s):
    dt_exc = 2e-6
    T_exc = 400e-6
    Gz_ss = GZ_MAX
    TBW = 2
    E2 = np.exp(-dt_exc / T2)
    E1 = np.exp(-dt_exc / T1)
    N = int(T_exc / dt_exc)
    t = np.linspace(-T_exc / 2, T_exc / 2, N)
    rf = np.sinc(t / (T_exc / TBW)) * (0.54 + 0.46 * np.cos(2 * np.pi * t / T_exc))
    rf *= (np.pi / 2) / (GAMMA * np.sum(rf) * dt_exc)
    Bzf = dBz + Gz_ss * Gzm
    Mx = np.zeros_like(dBz)
    My = np.zeros_like(dBz)
    Mz = np.ones_like(dBz)
    for k in range(N):
        Mx, My, Mz = rodrigues_numpy(rf[k] * B1s, np.zeros_like(B1s), Bzf, Mx, My, Mz, dt_exc, E1, E2)
    omr = GAMMA * (dBz - Gz_ss * Gzm)
    for _ in range(N // 2):
        Mx, My, Mz = z_step_numpy(Mx, My, Mz, omr, dt_exc, E1, E2)
    return Mx, My, Mz


def build_cpmg_warm(n_seg, n_free, n_pulse, dt):
    n_rf = n_pulse * 2 // 3
    T_rf = n_rf * dt
    t_rf = np.linspace(-T_rf / 2, T_rf / 2, n_rf)
    env = np.sinc(t_rf / (T_rf / 2)) * (0.54 + 0.46 * np.cos(2 * np.pi * t_rf / T_rf))
    env *= np.pi / (GAMMA * np.sum(np.abs(env)) * dt)
    env = np.clip(env, -B1_MAX, B1_MAX)

    ctrl = np.zeros((n_seg, n_free + n_pulse, 4), dtype=np.float32)
    ctrl[:, n_free:n_free + n_rf, 0] = env
    ctrl[:, n_free:n_free + n_rf, 3] = GZ_MAX
    ctrl[:, n_free + n_rf:, 3] = -GZ_MAX
    return ctrl


def make_problem(nx=20, nz=80, sw=0.010):
    x = np.linspace(-FOV_X / 2, FOV_X / 2, nx, dtype=np.float32)
    z = np.linspace(-FOV_Z / 2, FOV_Z / 2, nz, dtype=np.float32)
    X, Z = np.meshgrid(x, z, indexing="ij")
    Bx0, Bz0 = compute_B0(X, Z)
    B0n = Bz0[nx // 2, nz // 2]
    dBz = (Bz0 - B0n) + Bx0**2 / (2 * B0n)
    Gxm = X + Z**2 / (2 * B0n)
    Gzm = Z + (X / 2) ** 2 / (2 * B0n)
    B1s = 1 + 0.12 * (X / FOV_X * 2) ** 2 + 0.08 * (Z / FOV_Z * 2) ** 2
    mask_in = np.abs(Z) < sw / 2
    w_in = mask_in.astype(np.float32)
    w_out = (~mask_in).astype(np.float32)
    s_max = float(np.sum(w_in))
    Mx0, My0, Mz0 = excite_sinc_numpy(dBz, Gzm, B1s)

    prob_np = ProblemNP(
        Mx0=Mx0.astype(np.float32),
        My0=My0.astype(np.float32),
        Mz0=Mz0.astype(np.float32),
        dBz=dBz.astype(np.float32),
        Gxm=Gxm.astype(np.float32),
        Gzm=Gzm.astype(np.float32),
        B1s=B1s.astype(np.float32),
        w_in=w_in,
        w_out=w_out,
        s_max=s_max,
    )
    flat = lambda a: jnp.asarray(a.ravel(), dtype=jnp.float32)
    prob_jax = ProblemJAX(
        Mx0=flat(prob_np.Mx0),
        My0=flat(prob_np.My0),
        Mz0=flat(prob_np.Mz0),
        dBz=flat(prob_np.dBz),
        Gxm=flat(prob_np.Gxm),
        Gzm=flat(prob_np.Gzm),
        B1s=flat(prob_np.B1s),
        w_in=flat(prob_np.w_in),
        w_out=flat(prob_np.w_out),
        omega_free=flat(GAMMA * prob_np.dBz),
        s_max=s_max,
        nx=nx,
        nz=nz,
    )
    return prob_np, prob_jax


def make_value_and_grad(prob: ProblemJAX, n_seg, n_free, n_pulse, dt, lam_out, rf_pen):
    n_steps = n_free + n_pulse
    n_total = n_seg * n_steps
    gamma_dt = jnp.float32(GAMMA * dt)
    dt32 = jnp.float32(dt)
    E2 = jnp.float32(np.exp(-dt / T2))
    E1 = jnp.float32(np.exp(-dt / T1))

    def rodrigues(Bx, By, Bz, Mx, My, Mz):
        Bm = jnp.sqrt(Bx * Bx + By * By + Bz * Bz + 1e-30)
        invBm = 1.0 / Bm
        nx = Bx * invBm
        ny = By * invBm
        nz = Bz * invBm
        th = gamma_dt * Bm
        c = jnp.cos(th)
        s = jnp.sin(th)
        omc = 1.0 - c
        ndM = nx * Mx + ny * My + nz * Mz
        cx = ny * Mz - nz * My
        cy = nz * Mx - nx * Mz
        cz = nx * My - ny * Mx
        return (
            (Mx * c + cx * s + nx * ndM * omc) * E2,
            (My * c + cy * s + ny * ndM * omc) * E2,
            1.0 + (Mz * c + cz * s + nz * ndM * omc - 1.0) * E1,
        )

    def z_step(Mx, My, Mz, omega):
        th = omega * dt32
        c = jnp.cos(th)
        s = jnp.sin(th)
        return (
            (Mx * c - My * s) * E2,
            (Mx * s + My * c) * E2,
            1.0 + (Mz - 1.0) * E1,
        )

    def loss(ctrl_flat):
        ctrl = ctrl_flat.reshape((n_total, 4))

        def body(carry, xs):
            Mx, My, Mz, running = carry
            idx, u = xs
            b1x, b1y, ux, uz = u
            is_free = (idx % n_steps) < n_free

            omega = prob.omega_free + GAMMA * (ux * prob.Gxm + uz * prob.Gzm)
            Mx_f, My_f, Mz_f = z_step(Mx, My, Mz, omega)
            Mx_r, My_r, Mz_r = rodrigues(
                b1x * prob.B1s,
                b1y * prob.B1s,
                prob.dBz + ux * prob.Gxm + uz * prob.Gzm,
                Mx,
                My,
                Mz,
            )
            Mx_n = jnp.where(is_free, Mx_f, Mx_r)
            My_n = jnp.where(is_free, My_f, My_r)
            Mz_n = jnp.where(is_free, Mz_f, Mz_r)

            Sx = jnp.sum(prob.w_in * Mx_n)
            Sy = jnp.sum(prob.w_in * My_n)
            end_seg = ((idx + 1) % n_steps) == 0
            running = running + jnp.where(end_seg, Sx * Sx + Sy * Sy, 0.0)
            return (Mx_n, My_n, Mz_n, running), 0.0

        (Mx, My, Mz, running), _ = jax.lax.scan(
            body,
            (prob.Mx0, prob.My0, prob.Mz0, jnp.float32(0.0)),
            (jnp.arange(n_total), ctrl),
        )

        Sx = jnp.sum(prob.w_in * Mx)
        Sy = jnp.sum(prob.w_in * My)
        power_out = jnp.sum(prob.w_out * (Mx * Mx + My * My))
        rf_power = jnp.sum(ctrl[:, 0] * ctrl[:, 0] + ctrl[:, 1] * ctrl[:, 1]) * dt32
        J = 2.0 * (Sx * Sx + Sy * Sy) + running - lam_out * power_out - rf_pen * rf_power
        return -J

    return jax.jit(jax.value_and_grad(loss))


def simulate_numpy(ctrl, prob: ProblemNP, n_free, dt):
    n_seg, n_steps, _ = ctrl.shape
    E2 = np.exp(-dt / T2)
    E1 = np.exp(-dt / T1)
    sig = np.zeros(n_seg * n_steps + 1, dtype=np.float32)
    Mx = prob.Mx0.copy()
    My = prob.My0.copy()
    Mz = prob.Mz0.copy()
    sig[0] = np.abs(np.sum(prob.w_in * (Mx + 1j * My)))
    omega_free = GAMMA * prob.dBz
    k = 1
    for seg in range(n_seg):
        for step in range(n_steps):
            b1x, b1y, ux, uz = ctrl[seg, step]
            if step < n_free:
                Mx, My, Mz = z_step_numpy(Mx, My, Mz, omega_free + GAMMA * (ux * prob.Gxm + uz * prob.Gzm), dt, E1, E2)
            else:
                Mx, My, Mz = rodrigues_numpy(
                    b1x * prob.B1s,
                    b1y * prob.B1s,
                    prob.dBz + ux * prob.Gxm + uz * prob.Gzm,
                    Mx,
                    My,
                    Mz,
                    dt,
                    E1,
                    E2,
                )
            sig[k] = np.abs(np.sum(prob.w_in * (Mx + 1j * My)))
            k += 1
    return sig, Mx, My, Mz


def simulate_numpy_full(ctrl_list, segments, prob: ProblemNP):
    """Simulate from thermal equilibrium through variable-dt segments.
    ctrl_list: list of (n_steps, 4) arrays, one per segment.
    segments: list of {"dt", "n_free", "n_pulse"} dicts.
    """
    Mx = np.zeros_like(prob.dBz)
    My = np.zeros_like(prob.dBz)
    Mz = np.ones_like(prob.dBz)
    omega_free = GAMMA * prob.dBz
    for si, (ctrl_seg, seg) in enumerate(zip(ctrl_list, segments)):
        dt_s = seg["dt"]
        nf = seg["n_free"]
        E2 = np.exp(-dt_s / T2)
        E1 = np.exp(-dt_s / T1)
        for j in range(ctrl_seg.shape[0]):
            b1x, b1y, ux, uz = ctrl_seg[j]
            if j < nf:
                Mx, My, Mz = z_step_numpy(
                    Mx, My, Mz,
                    omega_free + GAMMA * (ux * prob.Gxm + uz * prob.Gzm),
                    dt_s, E1, E2)
            else:
                Mx, My, Mz = rodrigues_numpy(
                    b1x * prob.B1s, b1y * prob.B1s,
                    prob.dBz + ux * prob.Gxm + uz * prob.Gzm,
                    Mx, My, Mz, dt_s, E1, E2)
    return Mx, My, Mz


def metrics(ctrl, prob: ProblemNP, n_free, dt):
    sig, Mx, My, _ = simulate_numpy(ctrl, prob, n_free, dt)
    Mp = np.sqrt(Mx * Mx + My * My)
    Sx = np.sum(prob.w_in * Mx)
    Sy = np.sum(prob.w_in * My)
    power_in = np.sum(prob.w_in * Mp * Mp)
    power_out = np.sum(prob.w_out * Mp * Mp)
    coh = 0.0 if power_in <= 0 else float((Sx * Sx + Sy * Sy) / (power_in * prob.s_max))
    sel = 0.0 if (power_in + power_out) <= 0 else float(power_in / (power_in + power_out))
    return {
        "avg_signal": float(np.mean(sig) / prob.s_max),
        "final_signal": float(sig[-1] / prob.s_max),
        "coherence": coh,
        "selectivity": sel,
    }


def optimize_lbfgs(prob_jax: ProblemJAX, ctrl0, n_seg, n_free, n_pulse, dt, lam_out=200.0, rf_pen=5e7, maxiter=100, callback_every=10):
    n_steps = n_free + n_pulse
    value_and_grad = make_value_and_grad(prob_jax, n_seg, n_free, n_pulse, dt, lam_out, rf_pen)

    x0 = np.asarray(ctrl0, dtype=np.float32).ravel()
    bounds = [(-B1_MAX, B1_MAX), (-B1_MAX, B1_MAX), (-GX_MAX, GX_MAX), (-GZ_MAX, GZ_MAX)] * (n_seg * n_steps)
    counter = {"n": 0}
    t0 = time.time()

    def fg(x):
        v, g = value_and_grad(jnp.asarray(x, dtype=jnp.float32))
        return float(v), np.asarray(g, dtype=np.float64)

    def cb(xk):
        counter["n"] += 1
        if callback_every > 0 and counter["n"] % callback_every == 0:
            print(f"iter={counter['n']:4d}  elapsed={time.time() - t0:7.2f}s")

    res = minimize(
        fg,
        x0,
        method="L-BFGS-B",
        jac=True,
        bounds=bounds,
        options={"maxiter": maxiter, "ftol": 1e-12, "gtol": 1e-8},
        callback=cb,
    )
    return res


# ---------------------------------------------------------------------------
# Full-sequence optimisation (variable-dt, including excitation)
# ---------------------------------------------------------------------------

def make_value_and_grad_full(prob, segments, lam_out, rf_pen):
    """
    Build JIT-compiled value+grad for variable-dt multi-segment optimisation.
    Starts from thermal equilibrium; excitation is part of the optimised controls.

    segments: list of {"dt": float, "n_free": int, "n_pulse": int}
    """
    # Build per-step metadata arrays
    gamma_dt_list = []
    E1_list = []
    E2_list = []
    is_free_list = []
    seg_end_list = []

    for seg in segments:
        dt_s = seg["dt"]
        nf = seg["n_free"]
        ns = nf + seg["n_pulse"]
        for j in range(ns):
            gamma_dt_list.append(GAMMA * dt_s)
            E1_list.append(np.exp(-dt_s / T1))
            E2_list.append(np.exp(-dt_s / T2))
            is_free_list.append(j < nf)
            seg_end_list.append(j == ns - 1)

    n_total = len(gamma_dt_list)
    gamma_dt_arr = jnp.array(gamma_dt_list, dtype=jnp.float32)
    E1_arr = jnp.array(E1_list, dtype=jnp.float32)
    E2_arr = jnp.array(E2_list, dtype=jnp.float32)
    is_free_arr = jnp.array(is_free_list, dtype=jnp.bool_)
    seg_end_arr = jnp.array(seg_end_list, dtype=jnp.bool_)
    # Per-step dt for RF power penalty
    dt_list = []
    for seg in segments:
        ns = seg["n_free"] + seg["n_pulse"]
        dt_list.extend([seg["dt"]] * ns)
    dt_arr = jnp.array(dt_list, dtype=jnp.float32)

    N = prob.Gxm.shape[0]  # number of spatial points (flattened)

    def loss(ctrl_flat):
        ctrl = ctrl_flat.reshape((n_total, 4))

        def body(carry, xs):
            Mx, My, Mz, running = carry
            u, gdt, e1, e2, free, seg_e = xs
            b1x, b1y, ux, uz = u

            # Free precession (z-rotation only)
            th_free = gdt * (prob.dBz + ux * prob.Gxm + uz * prob.Gzm)
            c_f = jnp.cos(th_free)
            s_f = jnp.sin(th_free)
            Mx_f = (Mx * c_f - My * s_f) * e2
            My_f = (Mx * s_f + My * c_f) * e2
            Mz_f = 1.0 + (Mz - 1.0) * e1

            # Rodrigues rotation (full 3D)
            bx = b1x * prob.B1s
            by = b1y * prob.B1s
            bz = prob.dBz + ux * prob.Gxm + uz * prob.Gzm
            Bm = jnp.sqrt(bx * bx + by * by + bz * bz + 1e-30)
            invBm = 1.0 / Bm
            nx = bx * invBm
            ny = by * invBm
            nz = bz * invBm
            th = gdt * Bm
            cv = jnp.cos(th)
            sv = jnp.sin(th)
            omc = 1.0 - cv
            ndM = nx * Mx + ny * My + nz * Mz
            cx = ny * Mz - nz * My
            cy = nz * Mx - nx * Mz
            cz = nx * My - ny * Mx
            Mx_r = (Mx * cv + cx * sv + nx * ndM * omc) * e2
            My_r = (My * cv + cy * sv + ny * ndM * omc) * e2
            Mz_r = 1.0 + (Mz * cv + cz * sv + nz * ndM * omc - 1.0) * e1

            Mx_n = jnp.where(free, Mx_f, Mx_r)
            My_n = jnp.where(free, My_f, My_r)
            Mz_n = jnp.where(free, Mz_f, Mz_r)

            Sx = jnp.sum(prob.w_in * Mx_n)
            Sy = jnp.sum(prob.w_in * My_n)
            running = running + jnp.where(seg_e, Sx * Sx + Sy * Sy, 0.0)

            return (Mx_n, My_n, Mz_n, running), 0.0

        # Start from thermal equilibrium
        Mx0 = jnp.zeros(N, dtype=jnp.float32)
        My0 = jnp.zeros(N, dtype=jnp.float32)
        Mz0 = jnp.ones(N, dtype=jnp.float32)

        (Mx, My, Mz, running), _ = jax.lax.scan(
            body,
            (Mx0, My0, Mz0, jnp.float32(0.0)),
            (ctrl, gamma_dt_arr, E1_arr, E2_arr, is_free_arr, seg_end_arr),
        )

        Sx = jnp.sum(prob.w_in * Mx)
        Sy = jnp.sum(prob.w_in * My)
        power_out = jnp.sum(prob.w_out * (Mx * Mx + My * My))
        rf_power = jnp.sum((ctrl[:, 0] ** 2 + ctrl[:, 1] ** 2) * dt_arr)
        J = 2.0 * (Sx * Sx + Sy * Sy) + running - lam_out * power_out - rf_pen * rf_power
        return -J

    return jax.jit(jax.value_and_grad(loss))


def optimize_lbfgs_full(prob_jax, segments, ctrl0_list, lam_out=200.0,
                        rf_pen=5e7, maxiter=100, callback_every=10):
    """
    Full-sequence L-BFGS-B optimisation with variable-dt segments.
    ctrl0_list: list of np arrays, one per segment, each (n_steps, 4).
    """
    value_and_grad = make_value_and_grad_full(prob_jax, segments, lam_out, rf_pen)

    x0 = np.concatenate([c.ravel() for c in ctrl0_list]).astype(np.float32)
    n_total = sum(s["n_free"] + s["n_pulse"] for s in segments)
    bounds = [(-B1_MAX, B1_MAX), (-B1_MAX, B1_MAX),
              (-GX_MAX, GX_MAX), (-GZ_MAX, GZ_MAX)] * n_total
    counter = {"n": 0}
    t0 = time.time()

    def fg(x):
        v, g = value_and_grad(jnp.asarray(x, dtype=jnp.float32))
        return float(v), np.asarray(g, dtype=np.float64)

    def cb(xk):
        counter["n"] += 1
        if callback_every > 0 and counter["n"] % callback_every == 0:
            print(f"iter={counter['n']:4d}  elapsed={time.time() - t0:7.2f}s")

    res = minimize(
        fg, x0, method="L-BFGS-B", jac=True, bounds=bounds,
        options={"maxiter": maxiter, "ftol": 1e-12, "gtol": 1e-8},
        callback=cb,
    )
    return res


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


def optimize_lbfgs_masked(value_and_grad, ctrl0_flat, free_mask_flat, bounds_full,
                          maxiter=100, callback_every=10, snapshot_every=None):
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
        )

    x0 = ctrl0_flat[free_idx].astype(np.float32)
    bounds = [bounds_full[i] for i in free_idx]
    counter = {"n": 0}
    t0 = time.time()

    def merge_free(x_free):
        x_full = ctrl0_flat.copy()
        x_full[free_idx] = np.asarray(x_free, dtype=np.float32)
        return x_full

    def fg(x_free):
        x_full = merge_free(x_free)
        v, g_full = value_and_grad(jnp.asarray(x_full, dtype=jnp.float32))
        g_full = np.asarray(g_full, dtype=np.float64)
        return float(v), g_full[free_idx]

    def cb(xk):
        counter["n"] += 1
        if snapshot_every and counter["n"] % snapshot_every == 0:
            snapshots[counter["n"]] = merge_free(xk)
        if callback_every > 0 and counter["n"] % callback_every == 0:
            print(f"iter={counter['n']:4d}  elapsed={time.time() - t0:7.2f}s")

    res = minimize(
        fg,
        x0,
        method="L-BFGS-B",
        jac=True,
        bounds=bounds,
        options={"maxiter": maxiter, "ftol": 1e-12, "gtol": 1e-8},
        callback=cb,
    )
    x_full = merge_free(res.x)
    snapshots[res.nit] = x_full.copy()
    return MaskedOptimizationResult(
        x_full=x_full.astype(np.float32),
        nit=int(res.nit),
        nfev=int(res.nfev),
        success=bool(res.success),
        message=str(res.message),
        snapshots=snapshots,
    )


def run_demo():
    dt = 10e-6
    n_seg = 10
    n_free = 35
    n_pulse = 29

    fine_np, fine_jax = make_problem(20, 80)
    ctrl0 = build_cpmg_warm(n_seg, n_free, n_pulse, dt)

    t = time.time()
    value_and_grad = make_value_and_grad(fine_jax, n_seg, n_free, n_pulse, dt, 200.0, 5e7)
    _ = value_and_grad(jnp.asarray(ctrl0.ravel(), dtype=jnp.float32))
    jax.block_until_ready(_)
    compile_time = time.time() - t

    t = time.time()
    _ = value_and_grad(jnp.asarray(ctrl0.ravel(), dtype=jnp.float32))
    jax.block_until_ready(_)
    steady_eval = time.time() - t

    print("Warm-start metrics:", metrics(ctrl0, fine_np, n_free, dt))
    print(f"JAX compile+first-eval: {compile_time:.3f}s")
    print(f"JAX steady-state value+grad: {steady_eval:.3f}s")

    res = optimize_lbfgs(
        fine_jax,
        ctrl0,
        n_seg=n_seg,
        n_free=n_free,
        n_pulse=n_pulse,
        dt=dt,
        maxiter=25,
        callback_every=5,
    )
    ctrl_opt = res.x.reshape(n_seg, n_free + n_pulse, 4).astype(np.float32)
    print("Optimized metrics:", metrics(ctrl_opt, fine_np, n_free, dt))
    print(f"Optimizer status: nit={res.nit}, nfev={res.nfev}, success={res.success}")


if __name__ == "__main__":
    run_demo()

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
        Sz = jnp.sum(prob.w_in * Mz)
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

import time
from dataclasses import dataclass

import jax
import jax.numpy as jnp
import numpy as np
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


def _normalized_objective_terms(
    prob,
    J_in,
    J_out,
    power_out,
    rf_power,
    rf_smooth,
    gate_switch,
    gate_binary,
    dt_arr,
):
    signal_ref = jnp.float32(max(prob.s_max * prob.s_max, 1.0))
    power_ref = jnp.float32(max(prob.s_max, 1.0))
    rf_time_ref = jnp.maximum(jnp.sum(dt_arr), jnp.float32(1e-12))
    rf_power_ref = jnp.float32(B1_MAX * B1_MAX) * rf_time_ref
    return (
        J_in / signal_ref,
        J_out / signal_ref,
        power_out / power_ref,
        rf_power / rf_power_ref,
        rf_smooth / rf_power_ref,
        gate_switch / rf_time_ref,
        gate_binary / rf_time_ref,
    )


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

    ctrl = np.zeros((n_seg, n_free + n_pulse, 5), dtype=np.float32)
    ctrl[:, n_free:n_free + n_rf, 0] = env
    ctrl[:, n_free:n_free + n_rf, 3] = GZ_MAX
    ctrl[:, n_free + n_rf:, 3] = -GZ_MAX
    ctrl[:, n_free:, 4] = 1.0
    return ctrl


def outside_slice_weights(z, slice_half, power=2.0):
    slice_half = max(float(slice_half), 1e-9)
    dist = np.maximum(np.abs(z) - slice_half, 0.0) / slice_half
    weights = np.where(dist > 0.0, 1.0 + dist ** power, 0.0).astype(np.float32)
    outside = weights > 0.0
    if np.any(outside):
        weights[outside] /= np.mean(weights[outside], dtype=np.float64)
    return weights


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
    w_out = outside_slice_weights(Z, sw / 2)
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


def make_value_and_grad(
    prob: ProblemJAX,
    n_seg,
    n_free,
    n_pulse,
    dt,
    lam_out,
    lam_pow,
    rf_pen,
    rf_smooth_pen=0.0,
    gate_switch_pen=0.0,
    gate_binary_pen=0.0,
):
    n_steps = n_free + n_pulse
    n_total = n_seg * n_steps
    gamma_dt = jnp.float32(GAMMA * dt)
    dt32 = jnp.float32(dt)
    dt_arr = jnp.full((n_total,), dt32, dtype=jnp.float32)
    legacy_gate = jnp.concatenate([
        jnp.zeros((n_free,), dtype=jnp.float32),
        jnp.ones((n_pulse,), dtype=jnp.float32),
    ])
    legacy_gate = jnp.tile(legacy_gate, n_seg)
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
        ctrl_dim = 5 if ctrl_flat.shape[0] == n_total * 5 else 4
        ctrl = ctrl_flat.reshape((n_total, ctrl_dim))
        gate = ctrl[:, 4] if ctrl_dim >= 5 else legacy_gate

        def body(carry, xs):
            Mx, My, Mz, running_in, running_out = carry
            idx, u, rf_gate = xs
            b1x, b1y, ux, uz = u

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
            rf_gate = jnp.clip(rf_gate, 0.0, 1.0)
            sig_gate = 1.0 - rf_gate
            Mx_n = sig_gate * Mx_f + rf_gate * Mx_r
            My_n = sig_gate * My_f + rf_gate * My_r
            Mz_n = sig_gate * Mz_f + rf_gate * Mz_r

            Sx = jnp.sum(prob.w_in * Mx_n)
            Sy = jnp.sum(prob.w_in * My_n)
            Sx_out = jnp.sum(prob.w_out * Mx_n)
            Sy_out = jnp.sum(prob.w_out * My_n)
            running_in = running_in + sig_gate * (Sx * Sx + Sy * Sy)
            running_out = running_out + sig_gate * (Sx_out * Sx_out + Sy_out * Sy_out)
            return (Mx_n, My_n, Mz_n, running_in, running_out), 0.0

        (Mx, My, Mz, running_in, running_out), _ = jax.lax.scan(
            body,
            (prob.Mx0, prob.My0, prob.Mz0, jnp.float32(0.0), jnp.float32(0.0)),
            (jnp.arange(n_total), ctrl[:, :4], gate),
        )

        power_out = jnp.float32(0.0)
        rf_power = jnp.sum((ctrl[:, 0] * ctrl[:, 0] + ctrl[:, 1] * ctrl[:, 1]) * dt_arr)
        rf_xy = ctrl[:, :2].reshape((n_seg, n_steps, 2))
        rf_second_diff = rf_xy[:, 2:, :] - 2.0 * rf_xy[:, 1:-1, :] + rf_xy[:, :-2, :]
        rf_smooth = jnp.sum(rf_second_diff * rf_second_diff) * dt32
        gate_seq = gate.reshape((n_seg, n_steps))
        gate_diff = gate_seq[:, 1:] - gate_seq[:, :-1]
        gate_switch = jnp.sum(gate_diff * gate_diff) * dt32
        gate_binary = jnp.sum(gate_seq * (1.0 - gate_seq)) * dt32
        J_in = running_in
        J_out = running_out
        J_in, J_out, power_out, rf_power, rf_smooth, gate_switch, gate_binary = _normalized_objective_terms(
            prob, J_in, J_out, power_out, rf_power, rf_smooth, gate_switch, gate_binary, dt_arr
        )
        J = (
            J_in
            - lam_out * J_out
            - lam_pow * power_out
            - rf_pen * rf_power
            - rf_smooth_pen * rf_smooth
            - gate_switch_pen * gate_switch
            - gate_binary_pen * gate_binary
        )
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
            b1x, b1y, ux, uz = ctrl[seg, step, :4]
            rf_gate = float(ctrl[seg, step, 4]) if ctrl.shape[-1] > 4 else (0.0 if step < n_free else 1.0)
            if rf_gate < 0.5:
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
            sig[k] = (1.0 - rf_gate) * np.abs(np.sum(prob.w_in * (Mx + 1j * My)))
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
            b1x, b1y, ux, uz = ctrl_seg[j, :4]
            rf_gate = float(ctrl_seg[j, 4]) if ctrl_seg.shape[-1] > 4 else (0.0 if j < nf else 1.0)
            if rf_gate < 0.5:
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


# ---------------------------------------------------------------------------
# Full-sequence objective (variable-dt, including excitation)
# ---------------------------------------------------------------------------

def make_value_and_grad_full(
    prob,
    segments,
    lam_out,
    lam_pow,
    rf_pen,
    rf_smooth_pen=0.0,
    gate_switch_pen=0.0,
    gate_binary_pen=0.0,
):
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
    legacy_gate_list = []

    for seg in segments:
        dt_s = seg["dt"]
        nf = seg["n_free"]
        ns = nf + seg["n_pulse"]
        for j in range(ns):
            gamma_dt_list.append(GAMMA * dt_s)
            E1_list.append(np.exp(-dt_s / T1))
            E2_list.append(np.exp(-dt_s / T2))
            is_free_list.append(j < nf)
            legacy_gate_list.append(0.0 if j < nf else 1.0)

    n_total = len(gamma_dt_list)
    control_dim = max(seg.get("n_ctrl", 4) for seg in segments)
    gamma_dt_arr = jnp.array(gamma_dt_list, dtype=jnp.float32)
    E1_arr = jnp.array(E1_list, dtype=jnp.float32)
    E2_arr = jnp.array(E2_list, dtype=jnp.float32)
    is_free_arr = jnp.array(is_free_list, dtype=jnp.bool_)
    legacy_gate_arr = jnp.array(legacy_gate_list, dtype=jnp.float32)
    # Per-step dt for RF power penalty
    dt_list = []
    for seg in segments:
        ns = seg["n_free"] + seg["n_pulse"]
        dt_list.extend([seg["dt"]] * ns)
    dt_arr = jnp.array(dt_list, dtype=jnp.float32)
    seg_id_list = []
    local_step_list = []
    for si, seg in enumerate(segments):
        ns = seg["n_free"] + seg["n_pulse"]
        seg_id_list.extend([si] * ns)
        local_step_list.extend(list(range(ns)))
    seg_id_arr = jnp.array(seg_id_list, dtype=jnp.int32)
    local_step_arr = jnp.array(local_step_list, dtype=jnp.int32)

    N = prob.Gxm.shape[0]  # number of spatial points (flattened)

    def loss(ctrl_flat):
        ctrl = ctrl_flat.reshape((n_total, control_dim))
        gate = ctrl[:, 4] if control_dim >= 5 else legacy_gate_arr

        def body(carry, xs):
            Mx, My, Mz, running_in, running_out, running_power_out = carry
            u, gdt, e1, e2, free, rf_gate = xs
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

            rf_gate = jnp.clip(rf_gate, 0.0, 1.0)
            default_gate = jnp.where(free, 0.0, 1.0)
            rf_gate = jnp.where(control_dim >= 5, rf_gate, default_gate)
            sig_gate = 1.0 - rf_gate
            Mx_n = sig_gate * Mx_f + rf_gate * Mx_r
            My_n = sig_gate * My_f + rf_gate * My_r
            Mz_n = sig_gate * Mz_f + rf_gate * Mz_r

            Sx = jnp.sum(prob.w_in * Mx_n)
            Sy = jnp.sum(prob.w_in * My_n)
            Sx_out = jnp.sum(prob.w_out * Mx_n)
            Sy_out = jnp.sum(prob.w_out * My_n)
            power_out_step = jnp.sum(prob.w_out * (Mx_n * Mx_n + My_n * My_n))
            running_in = running_in + sig_gate * (Sx * Sx + Sy * Sy)
            running_out = running_out + sig_gate * (Sx_out * Sx_out + Sy_out * Sy_out)
            running_power_out = running_power_out + sig_gate * power_out_step

            return (Mx_n, My_n, Mz_n, running_in, running_out, running_power_out), 0.0

        # Start from thermal equilibrium
        Mx0 = jnp.zeros(N, dtype=jnp.float32)
        My0 = jnp.zeros(N, dtype=jnp.float32)
        Mz0 = jnp.ones(N, dtype=jnp.float32)

        (Mx, My, Mz, running_in, running_out, running_power_out), _ = jax.lax.scan(
            body,
            (Mx0, My0, Mz0, jnp.float32(0.0), jnp.float32(0.0), jnp.float32(0.0)),
            (ctrl[:, :4], gamma_dt_arr, E1_arr, E2_arr, is_free_arr, gate),
        )

        power_out = running_power_out
        rf_power = jnp.sum((ctrl[:, 0] ** 2 + ctrl[:, 1] ** 2) * dt_arr)
        rf_xy = ctrl[:, :2]
        prev1 = jnp.concatenate([rf_xy[:1], rf_xy[:-1]], axis=0)
        prev2 = jnp.concatenate([rf_xy[:1], rf_xy[:1], rf_xy[:-2]], axis=0)
        valid_second = local_step_arr >= 2
        same_seg_prev1 = seg_id_arr == jnp.concatenate([seg_id_arr[:1], seg_id_arr[:-1]], axis=0)
        same_seg_prev2 = seg_id_arr == jnp.concatenate([seg_id_arr[:1], seg_id_arr[:1], seg_id_arr[:-2]], axis=0)
        valid_second = valid_second & same_seg_prev1 & same_seg_prev2
        rf_second_diff = rf_xy - 2.0 * prev1 + prev2
        rf_second_diff = jnp.where(valid_second[:, None], rf_second_diff, 0.0)
        rf_smooth = jnp.sum(rf_second_diff * rf_second_diff * dt_arr[:, None])
        gate1 = gate
        prev_gate = jnp.concatenate([gate1[:1], gate1[:-1]], axis=0)
        valid_gate = seg_id_arr == jnp.concatenate([seg_id_arr[:1], seg_id_arr[:-1]], axis=0)
        gate_diff = jnp.where(valid_gate, gate1 - prev_gate, 0.0)
        gate_switch = jnp.sum(gate_diff * gate_diff * dt_arr)
        gate_binary = jnp.sum(gate1 * (1.0 - gate1) * dt_arr)
        J_in = running_in
        J_out = running_out
        J_in, J_out, power_out, rf_power, rf_smooth, gate_switch, gate_binary = _normalized_objective_terms(
            prob, J_in, J_out, power_out, rf_power, rf_smooth, gate_switch, gate_binary, dt_arr
        )
        J = (
            J_in
            - lam_out * J_out
            - lam_pow * power_out
            - rf_pen * rf_power
            - rf_smooth_pen * rf_smooth
            - gate_switch_pen * gate_switch
            - gate_binary_pen * gate_binary
        )
        return -J

    return jax.jit(jax.value_and_grad(loss))


def make_value_and_grad_full_fast(
    prob,
    segments,
    lam_out,
    lam_pow,
    rf_pen,
    rf_smooth_pen=0.0,
    gate_switch_pen=0.0,
    gate_binary_pen=0.0,
):
    """
    Like make_value_and_grad_full but uses associative_scan for O(log T)
    parallel depth instead of O(T) sequential scan.

    Each Bloch timestep is expressed as a 4×4 affine matrix (rotation +
    relaxation + recovery in homogeneous coords).  Matrix composition is
    associative, so jax.lax.associative_scan computes all prefix products
    in ~log2(T) parallel rounds, enabling massive parallelism.
    """
    # ── per-step metadata (same as make_value_and_grad_full) ──
    gamma_dt_list, E1_list, E2_list = [], [], []
    is_free_list, legacy_gate_list, dt_list = [], [], []
    seg_id_list, local_step_list = [], []

    for si, seg in enumerate(segments):
        dt_s = seg["dt"]
        nf = seg["n_free"]
        ns = nf + seg["n_pulse"]
        for j in range(ns):
            gamma_dt_list.append(GAMMA * dt_s)
            E1_list.append(np.exp(-dt_s / T1))
            E2_list.append(np.exp(-dt_s / T2))
            is_free_list.append(j < nf)
            legacy_gate_list.append(0.0 if j < nf else 1.0)
            dt_list.append(dt_s)
            seg_id_list.append(si)
            local_step_list.append(j)

    n_total = len(gamma_dt_list)
    control_dim = max(seg.get("n_ctrl", 4) for seg in segments)
    gdt = jnp.array(gamma_dt_list, dtype=jnp.float32)
    E1 = jnp.array(E1_list, dtype=jnp.float32)
    E2 = jnp.array(E2_list, dtype=jnp.float32)
    is_free = jnp.array(is_free_list, dtype=jnp.bool_)
    legacy_gate = jnp.array(legacy_gate_list, dtype=jnp.float32)
    dt_arr = jnp.array(dt_list, dtype=jnp.float32)
    seg_id_arr = jnp.array(seg_id_list, dtype=jnp.int32)
    local_step_arr = jnp.array(local_step_list, dtype=jnp.int32)

    N = prob.Gxm.shape[0]

    # Pre-broadcast spatial fields to (1, N) for later (T, N) ops
    dBz = prob.dBz[None, :]    # (1, N)
    Gxm = prob.Gxm[None, :]
    Gzm = prob.Gzm[None, :]
    B1s = prob.B1s[None, :]
    w_in = prob.w_in[None, :]  # (1, N) for signal sums
    w_out = prob.w_out[None, :]

    def loss(ctrl_flat):
        ctrl = ctrl_flat.reshape((n_total, control_dim))
        gate_raw = ctrl[:, 4] if control_dim >= 5 else legacy_gate

        b1x = ctrl[:, 0:1]  # (T, 1)
        b1y = ctrl[:, 1:2]
        ux  = ctrl[:, 2:3]
        uz  = ctrl[:, 3:4]

        # ── resolve gating ──
        rf_gate = jnp.clip(gate_raw, 0.0, 1.0)
        default_gate = jnp.where(is_free, 0.0, 1.0)
        rf_gate = jnp.where(control_dim >= 5, rf_gate, default_gate)
        sig_gate = 1.0 - rf_gate   # (T,)

        # ── build 4×4 affine matrices for every (timestep, spatial-point) ──
        # Shapes: scalars are (T,1), fields are (1,N), products broadcast to (T,N)
        gdt2 = gdt[:, None]   # (T, 1)
        e1 = E1[:, None] * jnp.ones((1, N))   # (T, N)
        e2 = E2[:, None] * jnp.ones((1, N))

        # off-resonance frequency
        omega = dBz + ux * Gxm + uz * Gzm   # (T, N)

        # ── free-precession matrix ──
        th_f = gdt2 * omega
        cf = jnp.cos(th_f) * e2
        sf = jnp.sin(th_f) * e2
        z = jnp.zeros_like(cf)
        o = jnp.ones_like(cf)

        # A_f: rows = [[cf, -sf, 0, 0], [sf, cf, 0, 0], [0, 0, e1, 1-e1], [0,0,0,1]]
        row0_f = jnp.stack([cf, -sf, z, z], axis=-1)
        row1_f = jnp.stack([sf, cf, z, z], axis=-1)
        row2_f = jnp.stack([z, z, e1, 1.0 - e1], axis=-1)
        row3   = jnp.stack([z, z, z, o], axis=-1)
        A_f = jnp.stack([row0_f, row1_f, row2_f, row3], axis=-2)  # (T,N,4,4)

        # ── Rodrigues rotation matrix ──
        bx = b1x * B1s           # (T, N)
        by = b1y * B1s
        bz = omega
        Bm = jnp.sqrt(bx*bx + by*by + bz*bz + 1e-30)
        invBm = 1.0 / Bm
        nx, ny, nz_ = bx * invBm, by * invBm, bz * invBm
        th = gdt2 * Bm
        cv = jnp.cos(th)
        sv = jnp.sin(th)
        omc = 1.0 - cv

        R00 = cv + nx*nx*omc;  R01 = nx*ny*omc - nz_*sv; R02 = nx*nz_*omc + ny*sv
        R10 = ny*nx*omc + nz_*sv; R11 = cv + ny*ny*omc;  R12 = ny*nz_*omc - nx*sv
        R20 = nz_*nx*omc - ny*sv; R21 = nz_*ny*omc + nx*sv; R22 = cv + nz_*nz_*omc

        row0_r = jnp.stack([e2*R00, e2*R01, e2*R02, z], axis=-1)
        row1_r = jnp.stack([e2*R10, e2*R11, e2*R12, z], axis=-1)
        row2_r = jnp.stack([e1*R20, e1*R21, e1*R22, 1.0 - e1], axis=-1)
        A_r = jnp.stack([row0_r, row1_r, row2_r, row3], axis=-2)

        # ── gated blend: A = sig_gate·A_f + rf_gate·A_r ──
        sg = sig_gate[:, None, None, None]  # (T, 1, 1, 1)
        rg = rf_gate[:, None, None, None]
        A = sg * A_f + rg * A_r             # (T, N, 4, 4)

        # ── associative scan: prefix[t] = A_t @ … @ A_0 ──
        def compose(a, b):
            return jnp.matmul(b, a)          # b applied after a

        prefix = jax.lax.associative_scan(compose, A, axis=0)  # (T, N, 4, 4)

        # ── magnetisation at every timestep ──
        init = jnp.array([0.0, 0.0, 1.0, 1.0], dtype=jnp.float32)
        M_all = jnp.einsum('tnij,j->tni', prefix, init)   # (T, N, 4)
        Mx = M_all[:, :, 0]   # (T, N)
        My = M_all[:, :, 1]

        # ── signal accumulation (fully parallel over T) ──
        Sx     = jnp.sum(w_in * Mx, axis=1)         # (T,)
        Sy     = jnp.sum(w_in * My, axis=1)
        Sx_out = jnp.sum(w_out * Mx, axis=1)
        Sy_out = jnp.sum(w_out * My, axis=1)
        power_out_steps = jnp.sum(w_out * (Mx*Mx + My*My), axis=1)

        J_in  = jnp.sum(sig_gate * (Sx*Sx + Sy*Sy))
        J_out = jnp.sum(sig_gate * (Sx_out*Sx_out + Sy_out*Sy_out))
        power_out = jnp.sum(sig_gate * power_out_steps)

        # ── penalty terms (already vectorised, same as original) ──
        rf_power = jnp.sum((ctrl[:, 0]**2 + ctrl[:, 1]**2) * dt_arr)
        rf_xy = ctrl[:, :2]
        prev1 = jnp.concatenate([rf_xy[:1], rf_xy[:-1]], axis=0)
        prev2 = jnp.concatenate([rf_xy[:1], rf_xy[:1], rf_xy[:-2]], axis=0)
        valid_second = local_step_arr >= 2
        same1 = seg_id_arr == jnp.concatenate([seg_id_arr[:1], seg_id_arr[:-1]])
        same2 = seg_id_arr == jnp.concatenate([seg_id_arr[:1], seg_id_arr[:1], seg_id_arr[:-2]])
        valid_second = valid_second & same1 & same2
        rf_d2 = rf_xy - 2.0 * prev1 + prev2
        rf_d2 = jnp.where(valid_second[:, None], rf_d2, 0.0)
        rf_smooth = jnp.sum(rf_d2 * rf_d2 * dt_arr[:, None])
        prev_gate = jnp.concatenate([gate_raw[:1], gate_raw[:-1]])
        valid_gate = seg_id_arr == jnp.concatenate([seg_id_arr[:1], seg_id_arr[:-1]])
        gd = jnp.where(valid_gate, gate_raw - prev_gate, 0.0)
        gate_switch = jnp.sum(gd * gd * dt_arr)
        gate_binary = jnp.sum(gate_raw * (1.0 - gate_raw) * dt_arr)

        J_in, J_out, power_out, rf_power, rf_smooth, gate_switch, gate_binary = \
            _normalized_objective_terms(
                prob, J_in, J_out, power_out, rf_power, rf_smooth,
                gate_switch, gate_binary, dt_arr,
            )

        J = (J_in
             - lam_out * J_out
             - lam_pow * power_out
             - rf_pen * rf_power
             - rf_smooth_pen * rf_smooth
             - gate_switch_pen * gate_switch
             - gate_binary_pen * gate_binary)
        return -J

    return jax.jit(jax.value_and_grad(loss))


def run_demo():
    from mri_opt import SearchConfig, default_bounds_for_steps, flatten_ctrl_list, optimize_masked_controls

    dt = 10e-6
    n_seg = 10
    n_free = 35
    n_pulse = 29

    fine_np, fine_jax = make_problem(20, 80)
    ctrl0 = build_cpmg_warm(n_seg, n_free, n_pulse, dt)

    t = time.time()
    value_and_grad = make_value_and_grad(fine_jax, n_seg, n_free, n_pulse, dt, 200.0, 2.0, 5e7, gate_switch_pen=1.5, gate_binary_pen=0.15)
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

    res = optimize_masked_controls(
        value_and_grad=value_and_grad,
        ctrl0_flat=flatten_ctrl_list([ctrl0[si] for si in range(n_seg)]),
        free_mask_flat=np.ones(ctrl0.size, dtype=bool),
        bounds_full=default_bounds_for_steps(n_seg * (n_free + n_pulse)),
        config=SearchConfig(n_trials=4, opt_steps=10),
    )
    ctrl_opt = res.x_full.reshape(n_seg, n_free + n_pulse, 4).astype(np.float32)
    print("Optimized metrics:", metrics(ctrl_opt, fine_np, n_free, dt))
    print(f"Optimizer status: nit={res.nit}, nfev={res.nfev}, success={res.success}")


if __name__ == "__main__":
    run_demo()

import { BlochData, PulseSegment, SignalTracePoint } from "./types";

function clampGate(v: number) {
  return Math.max(0, Math.min(1, v));
}

// ── Field interpolation ───────────────────────────────────────────────────────

/** Bilinear interpolation into a 2-D grid indexed by [r][z]. */
export function bilerp(
  grid: number[][],
  rArr: number[], zArr: number[],
  r: number, z: number,
): number {
  let ri = (r - rArr[0]) / (rArr[rArr.length - 1] - rArr[0]) * (rArr.length - 1);
  let zi = (z - zArr[0]) / (zArr[zArr.length - 1] - zArr[0]) * (zArr.length - 1);
  ri = Math.max(0, Math.min(rArr.length - 1.001, ri));
  zi = Math.max(0, Math.min(zArr.length - 1.001, zi));
  const r0 = Math.floor(ri), z0 = Math.floor(zi);
  const fr = ri - r0, fz = zi - z0;
  const r1 = Math.min(r0 + 1, rArr.length - 1);
  const z1 = Math.min(z0 + 1, zArr.length - 1);
  return (1 - fr) * (1 - fz) * grid[r0][z0]
       + fr       * (1 - fz) * grid[r1][z0]
       + (1 - fr) * fz       * grid[r0][z1]
       + fr       * fz       * grid[r1][z1];
}

/**
 * Evaluate local field properties at a Cartesian position (xm, zm) in metres.
 * Returns off-resonance frequency shift, initial magnetisation, and scale factors.
 */
export function getFieldAt(data: BlochData, xm: number, zm: number) {
  const f    = data.field;
  const B    = f.B0n;
  const rm   = Math.abs(xm) * 1e3;  // m → mm (field grid uses mm)
  const zmm  = zm * 1e3;

  const dBz = bilerp(f.dBz_uT, f.r_mm, f.z_mm, rm, zmm) * 1e-6;

  let mx0 = 0, my0 = 0, mz0 = 1;
  if (f.Mx0) {
    mx0 = bilerp(f.Mx0,  f.r_mm, f.z_mm, rm, zmm);
    my0 = bilerp(f.My0!, f.r_mm, f.z_mm, rm, zmm);
    mz0 = bilerp(f.Mz0!, f.r_mm, f.z_mm, rm, zmm);
  }

  return {
    dBz,
    mx0, my0, mz0,
    // Effective gradient positions (including field curvature)
    gxm: xm + zm * zm / (2 * B),
    gzm: zm + (xm / 2) ** 2 / (2 * B),
    // B1 inhomogeneity scale factor (increases toward FOV edge)
    b1s: 1 + 0.12 * (xm / (f.FOV_X / 2)) ** 2 + 0.08 * (zm / (f.FOV_Z / 2)) ** 2,
  };
}

// ── Bloch simulation ──────────────────────────────────────────────────────────

/**
 * Simulate the full Bloch trajectory from equilibrium (Mz=1) for all pulse segments.
 *
 * Returns a flat Float32-like array in groups of 5:
 *   [t_us, Mx, My, Mz, isRF,  t_us, Mx, My, Mz, isRF, …]
 * where isRF=0 for free-precession steps and isRF=1 for RF steps.
 */
export function sim(
  data: BlochData,
  r_mm: number, z_mm: number,
  pulse: PulseSegment[],
): number[] | null {
  if (!data?.field?.segments || !pulse) return null;

  const f   = data.field;
  const fl  = getFieldAt(data, r_mm * 1e-3, z_mm * 1e-3);
  const ga  = f.gamma;
  const om0 = ga * fl.dBz;

  let mx = 0, my = 0, mz = 1;
  let t  = 0;
  const out: number[] = [];

  for (let si = 0; si < f.segments.length && si < pulse.length; si++) {
    const seg   = f.segments[si];
    const steps = pulse[si];
    const { dt, n_free: nf } = seg;
    const E2 = Math.exp(-dt / f.T2);
    const E1 = Math.exp(-dt / f.T1);

    for (let j = 0; j < steps.length; j++) {
      const u = steps[j];
      const rfGate = clampGate(u[4]);
      const isRF = rfGate >= 0.5;
      out.push(+(t * 1e6).toFixed(1), +mx.toFixed(5), +my.toFixed(5), +mz.toFixed(5), isRF ? 1 : 0);

      if (!isRF) {
        // Free precession: rotate around z
        const om = om0 + ga * (u[2] * fl.gxm + u[3] * fl.gzm);
        const th = om * dt;
        const c = Math.cos(th), s = Math.sin(th);
        const newMx = (mx * c - my * s) * E2;
        const newMy = (mx * s + my * c) * E2;
        mx = newMx; my = newMy; mz = 1 + (mz - 1) * E1;
      } else {
        // RF pulse: rotate around effective B field (Rodrigues formula)
        const bx = u[0] * fl.b1s, by = u[1] * fl.b1s;
        const bz = fl.dBz + u[2] * fl.gxm + u[3] * fl.gzm;
        const Bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
        const th = ga * Bm * dt;
        const nx = bx / Bm, ny = by / Bm, nz = bz / Bm;
        const c = Math.cos(th), s = Math.sin(th), oc = 1 - c;
        const nd = nx * mx + ny * my + nz * mz;
        const cx_ = ny * mz - nz * my;
        const cy_ = nz * mx - nx * mz;
        const cz_ = nx * my - ny * mx;
        const newMx = (mx * c + cx_ * s + nx * nd * oc) * E2;
        const newMy = (my * c + cy_ * s + ny * nd * oc) * E2;
        mx = newMx; my = newMy;
        mz = 1 + (mz * c + cz_ * s + nz * nd * oc - 1) * E1;
      }
      t += dt;
    }
  }

  out.push(+(t * 1e6).toFixed(1), +mx.toFixed(5), +my.toFixed(5), +mz.toFixed(5), 2);
  return out;
}

/**
 * Same as sim() but exits early once t ≥ tC_us, returning [Mx, My, Mz] at that point.
 * Used for real-time cross-section rendering at the cursor time.
 */
export function simTo(
  data: BlochData,
  r_mm: number, z_mm: number,
  pulse: PulseSegment[],
  tC_us: number,
): [number, number, number] {
  if (!data?.field?.segments || !pulse) return [0, 0, 1];

  const f   = data.field;
  const fl  = getFieldAt(data, r_mm * 1e-3, z_mm * 1e-3);
  const ga  = f.gamma;
  const om0 = ga * fl.dBz;

  let mx = 0, my = 0, mz = 1;
  let t  = 0;

  for (let si = 0; si < f.segments.length && si < pulse.length; si++) {
    const seg   = f.segments[si];
    const steps = pulse[si];
    const { dt, n_free: nf } = seg;
    const E2 = Math.exp(-dt / f.T2);
    const E1 = Math.exp(-dt / f.T1);

    for (let j = 0; j < steps.length; j++) {
      if (t * 1e6 >= tC_us) return [mx, my, mz];

      const u = steps[j];
      const rfGate = clampGate(u[4]);
      if (rfGate < 0.5) {
        const om = om0 + ga * (u[2] * fl.gxm + u[3] * fl.gzm);
        const th = om * dt;
        const c = Math.cos(th), s = Math.sin(th);
        const newMx = (mx * c - my * s) * E2;
        const newMy = (mx * s + my * c) * E2;
        mx = newMx; my = newMy; mz = 1 + (mz - 1) * E1;
      } else {
        const bx = u[0] * fl.b1s, by = u[1] * fl.b1s;
        const bz = fl.dBz + u[2] * fl.gxm + u[3] * fl.gzm;
        const Bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
        const th = ga * Bm * dt;
        const nx = bx / Bm, ny = by / Bm, nz = bz / Bm;
        const c = Math.cos(th), s = Math.sin(th), oc = 1 - c;
        const nd = nx * mx + ny * my + nz * mz;
        const cx_ = ny * mz - nz * my;
        const cy_ = nz * mx - nx * mz;
        const cz_ = nx * my - ny * mx;
        const newMx = (mx * c + cx_ * s + nx * nd * oc) * E2;
        const newMy = (my * c + cy_ * s + ny * nd * oc) * E2;
        mx = newMx; my = newMy;
        mz = 1 + (mz * c + cz_ * s + nz * nd * oc - 1) * E1;
      }
      t += dt;
    }
  }

  return [mx, my, mz];
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Look up the active pulse waveform given current UI scenario/mode. */
export function getPulse(
  data: BlochData | null,
  scen: string,
  iterKey: string | null,
): PulseSegment[] | null {
  if (!data?.scenarios?.[scen]) return null;
  const pulses = data.scenarios[scen].pulses;
  if (iterKey && pulses[iterKey]) return pulses[iterKey] ?? null;
  const keys = Object.keys(pulses).sort((a, b) => Number(a) - Number(b));
  return keys.length > 0 ? pulses[keys[keys.length - 1]] ?? null : null;
}

export function rfGateAtTime(
  data: BlochData | null,
  pulse: PulseSegment[] | null,
  tc_us: number,
): number {
  if (!data?.field?.segments || !pulse) return 0;
  let t = 0;
  for (let si = 0; si < data.field.segments.length && si < pulse.length; si++) {
    const seg = data.field.segments[si];
    const steps = pulse[si];
    for (let j = 0; j < steps.length; j++) {
      if (t * 1e6 >= tc_us) return clampGate(steps[j][4]);
      t += seg.dt;
    }
  }
  const lastSeg = pulse[pulse.length - 1];
  const last = lastSeg?.[lastSeg.length - 1];
  return last ? clampGate(last[4]) : 0;
}

export function compSignalTrace(
  data: BlochData,
  pulse: PulseSegment[],
): SignalTracePoint[] | null {
  if (!data?.field?.segments || !pulse) return null;
  const f = data.field;
  const rArr = f.r_mm;
  const zArr = f.z_mm;
  const sliceHalfMm = (f.slice_half ?? 0.005) * 1e3;
  const points: Array<{ dBz: number; gxm: number; gzm: number; b1s: number; w: number }> = [];

  for (let ir = 0; ir < rArr.length; ir++) {
    for (let iz = 0; iz < zArr.length; iz++) {
      const zmm = zArr[iz];
      if (Math.abs(zmm) > sliceHalfMm) continue;
      const r_m = rArr[ir] * 1e-3;
      const z_m = zmm * 1e-3;
      const B = f.B0n;
      points.push({
        dBz: f.dBz_uT[ir][iz] * 1e-6,
        gxm: r_m + z_m * z_m / (2 * B),
        gzm: z_m + (r_m / 2) ** 2 / (2 * B),
        b1s: 1 + 0.12 * (r_m / (f.FOV_X / 2)) ** 2 + 0.08 * (z_m / (f.FOV_Z / 2)) ** 2,
        w: 1,
      });
    }
  }
  if (points.length === 0) return null;

  const mx = new Float64Array(points.length);
  const my = new Float64Array(points.length);
  const mz = new Float64Array(points.length).fill(1);
  const trace: SignalTracePoint[] = [{ t: 0, sig: 0 }];

  let t = 0;
  for (let si = 0; si < f.segments.length && si < pulse.length; si++) {
    const seg = f.segments[si];
    const steps = pulse[si];
    const E2 = Math.exp(-seg.dt / f.T2);
    const E1 = Math.exp(-seg.dt / f.T1);
    for (let j = 0; j < steps.length; j++) {
      const u = steps[j];
      const rfGate = clampGate(u[4]);
      for (let p = 0; p < points.length; p++) {
        const pt = points[p];
        if (rfGate < 0.5) {
          const om = f.gamma * (pt.dBz + u[2] * pt.gxm + u[3] * pt.gzm);
          const th = om * seg.dt;
          const c = Math.cos(th), s = Math.sin(th);
          const newMx = (mx[p] * c - my[p] * s) * E2;
          const newMy = (mx[p] * s + my[p] * c) * E2;
          mx[p] = newMx;
          my[p] = newMy;
          mz[p] = 1 + (mz[p] - 1) * E1;
        } else {
          const bx = u[0] * pt.b1s, by = u[1] * pt.b1s;
          const bz = pt.dBz + u[2] * pt.gxm + u[3] * pt.gzm;
          const Bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
          const th = f.gamma * Bm * seg.dt;
          const nx = bx / Bm, ny = by / Bm, nz = bz / Bm;
          const c = Math.cos(th), s = Math.sin(th), oc = 1 - c;
          const nd = nx * mx[p] + ny * my[p] + nz * mz[p];
          const cx = ny * mz[p] - nz * my[p];
          const cy = nz * mx[p] - nx * mz[p];
          const cz = nx * my[p] - ny * mx[p];
          const newMx = (mx[p] * c + cx * s + nx * nd * oc) * E2;
          const newMy = (my[p] * c + cy * s + ny * nd * oc) * E2;
          mx[p] = newMx;
          my[p] = newMy;
          mz[p] = 1 + (mz[p] * c + cz * s + nz * nd * oc - 1) * E1;
        }
      }

      t += seg.dt;
      let sx = 0;
      let sy = 0;
      if (rfGate < 0.5) {
        for (let p = 0; p < points.length; p++) {
          sx += mx[p];
          sy += my[p];
        }
      }
      trace.push({ t: +(t * 1e6).toFixed(1), sig: Math.sqrt(sx * sx + sy * sy) / points.length });
    }
  }
  return trace;
}

/**
 * Interpolate magnetisation from a flat trajectory array at time tc (μs).
 * Returns [Mx, My, Mz] or null if the trajectory is too short.
 */
export function stateAt(traj: number[], tc: number): [number, number, number] | null {
  if (!traj || traj.length < 10) return null;
  for (let i = 0; i < traj.length - 5; i += 5) {
    const tA = traj[i], tB = traj[i + 5];
    if (tB >= tc) {
      const f = tB === tA ? 0 : (tc - tA) / (tB - tA);
      return [
        traj[i + 1] + f * (traj[i + 6] - traj[i + 1]),
        traj[i + 2] + f * (traj[i + 7] - traj[i + 2]),
        traj[i + 3] + f * (traj[i + 8] - traj[i + 3]),
      ];
    }
  }
  const j = traj.length - 5;
  return [traj[j + 1], traj[j + 2], traj[j + 3]];
}

// ── Phase-map computation ─────────────────────────────────────────────────────

/** Compute phase vs time at fixed r=0 for a range of z positions. */
export function compPhaseZ(data: BlochData, pulse: PulseSegment[]) {
  if (!data?.field || !pulse) return null;
  const nZ = 50;
  const zArr = Array.from({ length: nZ }, (_, i) => -6 + 12 * i / (nZ - 1));
  const step = 4;
  const rows = zArr.map(z => {
    const tr = sim(data, 0, z, pulse);
    if (!tr) return [];
    const row = [];
    for (let it = 0; it * step * 5 < tr.length - 4; it++) {
      const j = it * step * 5;
      row.push({ t: tr[j], ph: Math.atan2(tr[j + 2], tr[j + 1]) * 180 / Math.PI, mp: Math.sqrt(tr[j + 1] ** 2 + tr[j + 2] ** 2) });
    }
    return row;
  });
  return { yArr: zArr, data: rows, nY: nZ };
}

/** Compute phase vs time at fixed z=0 for a range of r positions. */
export function compPhaseR(data: BlochData, pulse: PulseSegment[]) {
  if (!data?.field || !pulse) return null;
  const nR = 20;
  const rArr = Array.from({ length: nR }, (_, i) => i / (nR - 1) * 30);
  const step = 4;
  const rows = rArr.map(r => {
    const tr = sim(data, r, 0, pulse);
    if (!tr) return [];
    const row = [];
    for (let it = 0; it * step * 5 < tr.length - 4; it++) {
      const j = it * step * 5;
      row.push({ t: tr[j], ph: Math.atan2(tr[j + 2], tr[j + 1]) * 180 / Math.PI, mp: Math.sqrt(tr[j + 1] ** 2 + tr[j + 2] ** 2) });
    }
    return row;
  });
  return { yArr: rArr, data: rows, nY: nR, label: "r [mm]", ticks: [0, 10, 20, 30] };
}

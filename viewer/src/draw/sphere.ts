import { setupCanvas, project, clamp } from "../canvas";
import { stateAt } from "../physics";
import { BG } from "../constants";
import { DrawState } from "../types";

export function drawSphere(canvas: HTMLCanvasElement | null, state: DrawState) {
  const s = setupCanvas(canvas);
  if (!s) return;
  const { ctx: x, w, h } = s;
  const cx = w / 2, cy = h / 2;
  const { th, ph, zm } = state.cam;
  const scale = Math.min(w, h) * 0.37 * zm;
  const pr = (mx: number, my: number, mz: number) => project(mx, my, mz, th, ph, scale, cx, cy);

  x.fillStyle = BG;
  x.fillRect(0, 0, w, h);

  // ── Sphere wireframe (latitude + longitude rings) ─────────────────────────
  const ringFns = [
    (a: number) => pr(Math.cos(a), Math.sin(a), 0),
    (a: number) => pr(Math.cos(a), 0, Math.sin(a)),
    (a: number) => pr(0, Math.cos(a), Math.sin(a)),
  ];
  for (const fn of ringFns) {
    for (let pass = 0; pass < 2; pass++) {
      x.beginPath();
      let started = false;
      for (let i = 0; i <= 80; i++) {
        const p = fn(i / 80 * Math.PI * 2);
        const inFront = p[2] > 0;
        if ((pass === 0 && !inFront) || (pass === 1 && inFront)) {
          if (!started) { x.moveTo(p[0], p[1]); started = true; }
          else x.lineTo(p[0], p[1]);
        } else {
          started = false;
        }
      }
      x.strokeStyle = pass ? "rgba(255,255,255,.1)" : "rgba(255,255,255,.03)";
      x.lineWidth   = pass ? 0.6 : 0.4;
      x.stroke();
    }
  }

  // ── Axis arrows ───────────────────────────────────────────────────────────
  const axes: [[number, number, number], string, string][] = [
    [[1.15, 0, 0], "Mx", "#ef4444"],
    [[0, 1.15, 0], "My", "#22c55e"],
    [[0, 0, 1.15], "Mz", "#3b82f6"],
  ];
  for (const [dir, label, color] of axes) {
    const p0 = pr(0, 0, 0);
    const p1 = pr(...dir);
    const depth = (1 + p1[2]) / 2;
    x.strokeStyle  = color;
    x.lineWidth    = 0.5 + 0.5 * depth;
    x.globalAlpha  = 0.15 + 0.35 * depth;
    x.beginPath(); x.moveTo(p0[0], p0[1]); x.lineTo(p1[0], p1[1]); x.stroke();
    x.fillStyle    = color;
    x.font         = `600 ${10 + depth}px monospace`;
    x.globalAlpha  = 0.35 + 0.4 * depth;
    x.fillText(label, p1[0] + 4, p1[1] - 3);
    x.globalAlpha  = 1;
  }

  // ── Isochromat trajectories ───────────────────────────────────────────────
  for (const iso of state.isos) {
    if (!iso.v || !iso.t) continue;

    // Collect points within the current time window
    const pts: number[][] = [];
    for (let i = 0; i < iso.t.length; i += 5) pts.push(iso.t.slice(i, i + 5));
    const visible = pts.filter(p => p[0] >= state.tS && p[0] <= state.tE);
    if (!visible.length) continue;

    // Draw contiguous sub-segments (free vs RF differ in line style)
    let ss = 0;
    for (let i = 1; i <= visible.length; i++) {
      if (i === visible.length || visible[i][4] !== visible[i - 1][4]) {
        const seg = visible.slice(ss, i);
        if (seg.length >= 2) {
          const isPulse = seg[0][4] === 1;
          const avgDepth = seg.reduce((sum, v) => sum + pr(v[1], v[2], v[3])[2], 0) / seg.length;
          const fade     = 0.3 + 0.7 * (1 + avgDepth) / 2;
          x.strokeStyle  = iso.c;
          x.lineWidth    = isPulse ? 1.8 : 1;
          x.globalAlpha  = (isPulse ? 0.8 : 0.1) * fade;
          x.beginPath();
          seg.forEach((v, j) => {
            const p = pr(v[1], v[2], v[3]);
            j ? x.lineTo(p[0], p[1]) : x.moveTo(p[0], p[1]);
          });
          x.stroke();
          x.globalAlpha = 1;
        }
        ss = i;
      }
    }

    // Dot at cursor time
    const st = stateAt(iso.t, state.tC);
    if (!st) continue;
    const [mx, my, mz] = st;
    const mag   = Math.sqrt(mx * mx + my * my + mz * mz);
    const mPerp = Math.sqrt(mx * mx + my * my);
    const ux = mag > 1e-6 ? mx / mag : 0;
    const uy = mag > 1e-6 ? my / mag : 0;
    const uz = mag > 1e-6 ? mz / mag : 0;
    const pS   = pr(ux, uy, uz);
    const fade = 0.5 + 0.5 * (1 + pS[2]) / 2;

    x.fillStyle   = iso.c;
    x.globalAlpha = fade;
    x.beginPath(); x.arc(pS[0], pS[1], 3 + 2 * fade, 0, Math.PI * 2); x.fill();
    x.strokeStyle = "rgba(0,0,0,.4)";
    x.lineWidth   = 1;
    x.beginPath(); x.arc(pS[0], pS[1], 3 + 2 * fade, 0, Math.PI * 2); x.stroke();
    x.globalAlpha = 1;

    // |M⊥| projection circle (optional)
    if (state.showMp && mPerp > 0.01) {
      const pA = pr(mx, my, 0);
      x.setLineDash([3, 3]);
      x.strokeStyle = iso.c; x.globalAlpha = 0.35; x.lineWidth = 1;
      x.beginPath(); x.moveTo(pS[0], pS[1]); x.lineTo(pA[0], pA[1]); x.stroke();
      x.setLineDash([]);
      x.strokeStyle = iso.c; x.lineWidth = 1.5; x.globalAlpha = 0.5 + 0.3 * mPerp;
      x.beginPath(); x.arc(pA[0], pA[1], 2 + 4 * mPerp, 0, Math.PI * 2); x.stroke();
      x.fillStyle   = iso.c; x.font = "8px monospace"; x.globalAlpha = 0.45;
      x.fillText("|M⊥|=" + mPerp.toFixed(2), pA[0] + 8, pA[1] + 3);
      x.globalAlpha = 1;
    }
  }

  void clamp; // imported for potential future use; suppress lint warning
}

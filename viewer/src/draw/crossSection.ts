import { setupCanvas, hue2rgb } from "../canvas";
import { simTo } from "../physics";
import { BG, TX2 } from "../constants";
import { DrawState } from "../types";

export function drawCrossSection(canvas: HTMLCanvasElement | null, state: DrawState) {
  const s = setupCanvas(canvas);
  if (!s || !state.D?.field) return;
  const { ctx: x, w, h } = s;

  const f    = state.D.field;
  const rMax = f.r_mm[f.r_mm.length - 1];
  const xsH  = state.xsH;

  x.fillStyle = BG;
  x.fillRect(0, 0, w, h);

  const pad = { l: 28, r: 8, t: 8, b: 20 };
  const pW  = w - pad.l - pad.r;
  const pH  = h - pad.t - pad.b;

  const zToY = (z: number) => pad.t + pH * (1 - (z - (-xsH)) / (2 * xsH));
  const rToX = (r: number) => pad.l + (r / rMax) * pW;

  // ── Phase-coloured heatmap (simulated to cursor time) ─────────────────────
  if (state.pulse) {
    const nR    = 15;
    const zMax  = Math.min(xsH, f.z_mm[f.z_mm.length - 1]);

    // Dense z samples near slice, sparse elsewhere
    const zSamples: number[] = [];
    for (let z = -6; z <= 6; z += 0.5) zSamples.push(z);
    for (let z = -zMax; z < -6; z += Math.max(2, zMax / 15)) zSamples.push(z);
    for (let z = 6.5; z <= zMax; z += Math.max(2, zMax / 15)) zSamples.push(z);
    zSamples.sort((a, b) => a - b);

    x.save();
    x.beginPath(); x.rect(pad.l, pad.t, pW, pH); x.clip();

    for (let ir = 0; ir < nR; ir++) {
      const r_mm = ir / (nR - 1) * rMax;
      const x0   = rToX(r_mm);
      const x1   = ir < nR - 1 ? rToX((ir + 1) / (nR - 1) * rMax) : pad.l + pW;

      for (let iz = 0; iz < zSamples.length; iz++) {
        const z_mm = zSamples[iz];
        if (z_mm < -xsH || z_mm > xsH) continue;

        const y0    = zToY(z_mm);
        const zNext = iz < zSamples.length - 1 ? zSamples[iz + 1] : z_mm + 1;
        const y1    = zToY(Math.min(zNext, xsH));

        const [smx, smy] = simTo(state.D!, r_mm, z_mm, state.pulse!, state.tC);
        const mp          = Math.sqrt(smx * smx + smy * smy);
        const phaseDeg    = Math.atan2(smy, smx) * 180 / Math.PI;

        const rx = Math.min(x0, x1), ry = Math.min(y0, y1);
        const rw = Math.abs(x1 - x0) + 1, rh = Math.abs(y1 - y0) + 1;
        x.fillStyle = hue2rgb(phaseDeg, mp);
        x.fillRect(rx, ry, rw, rh);
      }
    }
    x.restore();
  }

  // ── Slice boundary dashed lines ───────────────────────────────────────────
  const sh    = (f.slice_half ?? 0.005) * 1e3;
  const yTop  = zToY(sh);
  const yBot  = zToY(-sh);
  x.strokeStyle = "rgba(34,197,94,.6)"; x.lineWidth = 1; x.setLineDash([4, 3]);
  x.beginPath(); x.moveTo(pad.l, yTop); x.lineTo(pad.l + pW, yTop); x.stroke();
  x.beginPath(); x.moveTo(pad.l, yBot); x.lineTo(pad.l + pW, yBot); x.stroke();
  x.setLineDash([]);
  x.fillStyle = "rgba(34,197,94,.6)"; x.font = "bold 7px monospace";
  x.fillText("slice", pad.l + 2, (yTop + yBot) / 2 + 3);

  // ── Axes frame ────────────────────────────────────────────────────────────
  x.strokeStyle = "rgba(255,255,255,.12)"; x.lineWidth = 0.5;
  x.beginPath(); x.moveTo(pad.l, pad.t); x.lineTo(pad.l, pad.t + pH); x.lineTo(pad.l + pW, pad.t + pH); x.stroke();

  // Z ticks (left axis)
  x.fillStyle = TX2; x.font = "7px monospace"; x.textAlign = "right"; x.globalAlpha = 0.7;
  const zTickStep = xsH > 50 ? 50 : xsH > 20 ? 10 : xsH > 8 ? 5 : 2;
  for (let z = -Math.floor(xsH / zTickStep) * zTickStep; z <= xsH; z += zTickStep) {
    const y = zToY(z);
    if (y < pad.t + 2 || y > pad.t + pH - 2) continue;
    x.fillText(String(z), pad.l - 3, y + 3);
    x.strokeStyle = "rgba(255,255,255,.05)"; x.lineWidth = 0.3;
    x.beginPath(); x.moveTo(pad.l, y); x.lineTo(pad.l + pW, y); x.stroke();
  }

  // R ticks (bottom axis)
  x.textAlign = "center";
  const rTickStep = rMax > 60 ? 20 : rMax > 30 ? 10 : 5;
  for (let r = 0; r <= rMax; r += rTickStep) {
    const px = rToX(r);
    if (px < pad.l + 2 || px > pad.l + pW - 2) continue;
    x.fillText(String(r), px, pad.t + pH + 11);
    x.strokeStyle = "rgba(255,255,255,.05)"; x.lineWidth = 0.3;
    x.beginPath(); x.moveTo(px, pad.t); x.lineTo(px, pad.t + pH); x.stroke();
  }

  // Axis titles
  x.fillStyle = TX2; x.font = "bold 7px monospace"; x.globalAlpha = 0.5;
  x.textAlign = "center";
  x.fillText("r [mm]", pad.l + pW / 2, h - 2);
  x.save(); x.translate(8, pad.t + pH / 2); x.rotate(-Math.PI / 2); x.fillText("z [mm]", 0, 0); x.restore();
  x.globalAlpha = 1; x.textAlign = "left";

  // ── Isochromat dots ───────────────────────────────────────────────────────
  for (const iso of state.isos) {
    const px = rToX(iso.r), py = zToY(iso.z);
    if (py < pad.t - 5 || py > pad.t + pH + 5 || px < pad.l - 5 || px > pad.l + pW + 5) continue;
    x.fillStyle   = iso.c; x.globalAlpha = iso.v ? 0.9 : 0.1;
    x.beginPath(); x.arc(px, py, 4, 0, Math.PI * 2); x.fill();
    x.strokeStyle = "rgba(0,0,0,.6)"; x.lineWidth = 1; x.globalAlpha = iso.v ? 0.7 : 0.1;
    x.beginPath(); x.arc(px, py, 4, 0, Math.PI * 2); x.stroke();
    x.globalAlpha = 1;
  }
}

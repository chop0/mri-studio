import { setupCanvas } from "../canvas";
import { stateAt } from "../physics";
import { BG, GR, TX, TX2, CUR } from "../constants";
import { DrawState } from "../types";

interface PlotDef {
  title: string;
  unit: string;
  min: number;
  max: number;
  ticks: number[];
  fn: (mx: number, my: number, mz: number) => number;
}

const PLOTS: PlotDef[] = [
  {
    title: "Phase φ", unit: "°",
    min: -180, max: 180,
    ticks: [-180, -90, 0, 90, 180],
    fn: (mx, my) => {
      const m = Math.sqrt(mx * mx + my * my);
      return m > 0.01 ? Math.atan2(my, mx) * 180 / Math.PI : NaN;
    },
  },
  {
    title: "Polar θ", unit: "°",
    min: 0, max: 180,
    ticks: [0, 45, 90, 135, 180],
    fn: (mx, my, mz) => Math.atan2(Math.sqrt(mx * mx + my * my), mz) * 180 / Math.PI,
  },
  {
    title: "|M⊥|", unit: "",
    min: 0, max: 1,
    ticks: [0, 0.25, 0.5, 0.75, 1],
    fn: (mx, my) => Math.sqrt(mx * mx + my * my),
  },
];

function niceTick(span: number) {
  return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
}

export function drawPlots(canvas: HTMLCanvasElement | null, state: DrawState) {
  const s = setupCanvas(canvas);
  if (!s || !state.D) return;
  const { ctx: x, w, h } = s;

  x.fillStyle = BG;
  x.fillRect(0, 0, w, h);

  const vis = state.isos.filter(o => o.v && o.t);
  const pad  = { l: 40, r: 8, t: 14, b: 18, gap: 10 };
  const pW   = (w - pad.l - pad.r - pad.gap * 2) / 3;
  const pH   = h - pad.t - pad.b;
  const tMin = state.tS;
  const tMax = Math.max(state.tE, state.tS + 1);
  const tSpan = tMax - tMin;

  PLOTS.forEach((plot, pi) => {
    const ox   = pad.l + pi * (pW + pad.gap);
    const oy   = pad.t;
    const yP   = (v: number) => oy + pH - (v - plot.min) / (plot.max - plot.min) * pH;
    const xP   = (t: number) => ox + (t - tMin) / tSpan * pW;

    // Y grid lines + labels
    x.textAlign = "right";
    for (const v of plot.ticks) {
      const y = yP(v);
      x.strokeStyle = GR; x.lineWidth = v === 0 ? 0.6 : 0.3; x.globalAlpha = v === 0 ? 0.5 : 0.2;
      x.beginPath(); x.moveTo(ox, y); x.lineTo(ox + pW, y); x.stroke(); x.globalAlpha = 1;
      x.fillStyle = TX; x.font = "8px monospace";
      const lbl = plot.unit === "°" ? v + "°" : (v % 1 ? v.toFixed(2) : String(v));
      x.fillText(lbl, ox - 4, y + 3);
    }
    x.textAlign = "left";

    // Axis frame
    x.strokeStyle = "rgba(255,255,255,.1)"; x.lineWidth = 0.5;
    x.beginPath(); x.moveTo(ox, oy); x.lineTo(ox, oy + pH); x.lineTo(ox + pW, oy + pH); x.stroke();

    // X ticks
    const xTickStep = niceTick(tSpan);
    x.fillStyle = TX2; x.font = "7px monospace"; x.textAlign = "center"; x.globalAlpha = 0.5;
    for (let t = Math.ceil(tMin / xTickStep) * xTickStep; t <= tMax; t += xTickStep) {
      const px = xP(t);
      if (px > ox + 4 && px < ox + pW - 4) {
        x.fillText(tSpan > 2000 ? (t / 1000).toFixed(t % 1000 ? 1 : 0) : String(t), px, oy + pH + 10);
        x.strokeStyle = "rgba(255,255,255,.04)"; x.lineWidth = 0.3;
        x.beginPath(); x.moveTo(px, oy); x.lineTo(px, oy + pH); x.stroke();
      }
    }
    if (pi === 2) {
      x.textAlign = "right";
      x.fillText(tSpan > 2000 ? "ms" : "μs", ox + pW, oy + pH + 10);
      x.textAlign = "left";
    }
    x.globalAlpha = 1;

    // Cursor line
    const xC = xP(state.tC);
    x.strokeStyle = CUR; x.lineWidth = 1; x.globalAlpha = 0.5;
    x.beginPath(); x.moveTo(xC, oy); x.lineTo(xC, oy + pH); x.stroke(); x.globalAlpha = 1;

    // Title
    x.fillStyle = TX; x.font = "bold 10px monospace"; x.textAlign = "center";
    x.fillText(plot.title, ox + pW / 2, oy - 3); x.textAlign = "left";

    // Traces (clipped)
    x.save(); x.beginPath(); x.rect(ox, oy - 1, pW, pH + 2); x.clip();
    for (const iso of vis) {
      x.strokeStyle = iso.c; x.lineWidth = 1.2; x.globalAlpha = 0.8;
      x.beginPath();
      let started = false;
      for (let i = 0; i < iso.t!.length; i += 5) {
        const t = iso.t![i];
        if (t < tMin - tSpan * 0.02 || t > tMax + tSpan * 0.02) continue;
        const v = plot.fn(iso.t![i + 1], iso.t![i + 2], iso.t![i + 3]);
        if (isNaN(v)) { started = false; continue; }
        const px = xP(t), py = yP(v);
        if (!started) { x.moveTo(px, py); started = true; } else x.lineTo(px, py);
      }
      x.stroke(); x.globalAlpha = 1;

      // Cursor dot
      const sa = stateAt(iso.t!, state.tC);
      if (sa) {
        const ve = plot.fn(...sa);
        if (!isNaN(ve)) {
          x.fillStyle = iso.c;
          x.beginPath(); x.arc(xP(state.tC), yP(ve), 3, 0, Math.PI * 2); x.fill();
        }
      }
    }
    x.restore();
  });
}

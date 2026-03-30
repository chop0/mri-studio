import { setupCanvas, hue2rgb, clamp } from "../canvas";
import { BG, TX, TX2, CUR } from "../constants";
import { DrawState, PhaseMapData } from "../types";

export interface PhaseMapConfig {
  title: string;
  ticks?: number[];
  sliceBounds?: boolean;
}

function niceTick(span: number) {
  return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
}

export function drawPhaseMap(
  canvas: HTMLCanvasElement | null,
  pm: PhaseMapData | null,
  state: DrawState,
  cfg: PhaseMapConfig,
) {
  const s = setupCanvas(canvas);
  if (!s || !pm) return;
  const { ctx: x, w, h } = s;

  x.fillStyle = BG;
  x.fillRect(0, 0, w, h);

  const { yArr, data, nY } = pm;
  const tMin  = state.tS;
  const tMax  = Math.max(state.tE, state.tS + 1);
  const tSpan = tMax - tMin;
  const pad   = { l: 34, r: 4, t: 14, b: 16 };
  const pW    = w - pad.l - pad.r;
  const pH    = h - pad.t - pad.b;

  // Title
  x.fillStyle = TX; x.font = "bold 9px monospace"; x.textAlign = "center";
  x.fillText(cfg.title, pad.l + pW / 2, pad.t - 3); x.textAlign = "left";

  // Y-axis labels and grid lines
  x.fillStyle = TX; x.font = "8px monospace"; x.textAlign = "right";
  for (const v of (cfg.ticks ?? [])) {
    const y = pad.t + pH * (1 - (v - yArr[0]) / (yArr[yArr.length - 1] - yArr[0]));
    x.fillText(String(v), pad.l - 3, y + 2);
    x.strokeStyle = "rgba(255,255,255,.04)"; x.lineWidth = 0.3;
    x.beginPath(); x.moveTo(pad.l, y); x.lineTo(pad.l + pW, y); x.stroke();
  }
  x.textAlign = "left";

  // Heatmap cells
  const cellH = pH / nY;
  for (let iy = 0; iy < nY; iy++) {
    const row = data[iy];
    const y   = pad.t + pH - ((iy + 0.5) / nY) * pH;
    for (let it = 0; it < row.length; it++) {
      const d = row[it];
      if (d.t < tMin || d.t > tMax) continue;
      const xPos  = pad.l + (d.t - tMin) / tSpan * pW;
      const nextT = row[it + 1]?.t ?? d.t + 40;
      const cellW = Math.max(1, (nextT - d.t) / tSpan * pW + 1);
      const hue   = ((d.ph % 360) + 360) % 360;
      x.fillStyle = hue2rgb(hue, clamp(d.mp, 0, 1));
      x.fillRect(xPos, y - cellH / 2, cellW, cellH + 1);
    }
  }

  // Slice-boundary dashed lines (z heatmap only)
  if (cfg.sliceBounds && state.D?.field) {
    const sh = (state.D.field.slice_half ?? 0.005) * 1e3;
    for (const zv of [-sh, sh]) {
      const y = pad.t + pH * (1 - (zv - yArr[0]) / (yArr[yArr.length - 1] - yArr[0]));
      x.strokeStyle = "rgba(34,197,94,.3)"; x.lineWidth = 0.5; x.setLineDash([3, 3]);
      x.beginPath(); x.moveTo(pad.l, y); x.lineTo(pad.l + pW, y); x.stroke();
    }
    x.setLineDash([]);
  }

  // Time cursor + triangle handle
  const xC = pad.l + (state.tC - tMin) / tSpan * pW;
  x.strokeStyle = CUR; x.lineWidth = 1.5; x.globalAlpha = 0.8;
  x.beginPath(); x.moveTo(xC, pad.t); x.lineTo(xC, pad.t + pH); x.stroke(); x.globalAlpha = 1;
  x.fillStyle = CUR; x.globalAlpha = 0.7;
  x.beginPath(); x.moveTo(xC, pad.t + pH); x.lineTo(xC - 4, pad.t + pH + 6); x.lineTo(xC + 4, pad.t + pH + 6); x.fill();
  x.globalAlpha = 1;

  // X-axis time ticks
  const tickStep = niceTick(tSpan);
  x.fillStyle = TX2; x.font = "7px monospace"; x.textAlign = "center"; x.globalAlpha = 0.5;
  for (let t = Math.ceil(tMin / tickStep) * tickStep; t <= tMax; t += tickStep) {
    const px = pad.l + (t - tMin) / tSpan * pW;
    if (px > pad.l + 4 && px < pad.l + pW - 4) {
      x.fillText(tSpan > 2000 ? (t / 1000).toFixed(t % 1000 ? 1 : 0) + "ms" : t + "μs", px, h - 2);
    }
  }
  x.textAlign = "left"; x.globalAlpha = 1;
}

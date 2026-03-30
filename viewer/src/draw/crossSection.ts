import { clamp, hue2rgb, setupCanvas } from "../canvas";
import { simTo } from "../physics";
import { BG, TX2 } from "../constants";
import { DrawState } from "../types";

interface CellSample {
  mx: number;
  my: number;
  mp: number;
  phaseDeg: number;
  r_mm: number;
  z_mm: number;
}

const CROSS_SECTION_RADIAL_SAMPLES = 18;
const INNER_Z_STEP_MM = 0.5;
const MID_Z_STEP_MM = 1;
const OUTER_Z_BANDS = 24;

function buildZSamples(zMax: number) {
  const zSamples = new Set<number>();
  const inner = Math.min(8, zMax);
  const mid = Math.min(20, zMax);

  for (let z = -inner; z <= inner + 1e-6; z += INNER_Z_STEP_MM) zSamples.add(+z.toFixed(3));
  for (let z = -mid; z <= mid + 1e-6; z += MID_Z_STEP_MM) zSamples.add(+z.toFixed(3));

  if (zMax > mid) {
    const step = (zMax - mid) / OUTER_Z_BANDS;
    for (let i = 0; i <= OUTER_Z_BANDS; i++) {
      const z = mid + i * step;
      zSamples.add(+z.toFixed(3));
      zSamples.add(+(-z).toFixed(3));
    }
  }

  zSamples.add(-zMax);
  zSamples.add(zMax);
  return Array.from(zSamples).sort((a, b) => a - b);
}

export function drawCrossSection(canvas: HTMLCanvasElement | null, state: DrawState) {
  const s = setupCanvas(canvas);
  if (!s || !state.D?.field) return;
  const { ctx: x, w, h } = s;

  const f = state.D.field;
  const rMax = f.r_mm[f.r_mm.length - 1];
  const xsH = state.xsH;
  const zFullMax = f.z_mm[f.z_mm.length - 1];
  const zSamples = buildZSamples(zFullMax);

  x.fillStyle = BG;
  x.fillRect(0, 0, w, h);

  const pad = { l: 28, r: 8, t: 8, b: 20 };
  const pW = w - pad.l - pad.r;
  const pH = h - pad.t - pad.b;

  const zToY = (z: number) => pad.t + pH * (1 - (z - (-xsH)) / (2 * xsH));
  const rToX = (r: number) => pad.l + (r / rMax) * pW;

  if (state.pulse) {
    const cells: CellSample[] = [];
    let sumMx = 0;
    let sumMy = 0;

    for (let ir = 0; ir < CROSS_SECTION_RADIAL_SAMPLES; ir++) {
      const r_mm = (ir / (CROSS_SECTION_RADIAL_SAMPLES - 1)) * rMax;
      for (const z_mm of zSamples) {
        const [mx, my] = simTo(state.D, r_mm, z_mm, state.pulse, state.tC);
        const mp = Math.sqrt(mx * mx + my * my);
        const phaseDeg = Math.atan2(my, mx) * 180 / Math.PI;
        sumMx += mx;
        sumMy += my;
        cells.push({ mx, my, mp, phaseDeg, r_mm, z_mm });
      }
    }

    const sumNorm = Math.sqrt(sumMx * sumMx + sumMy * sumMy);
    const ux = sumNorm > 1e-9 ? sumMx / sumNorm : 0;
    const uy = sumNorm > 1e-9 ? sumMy / sumNorm : 0;

    x.save();
    x.beginPath();
    x.rect(pad.l, pad.t, pW, pH);
    x.clip();

    for (let ir = 0; ir < CROSS_SECTION_RADIAL_SAMPLES; ir++) {
      const r0 = (ir / (CROSS_SECTION_RADIAL_SAMPLES - 1)) * rMax;
      const r1 = ir < CROSS_SECTION_RADIAL_SAMPLES - 1
        ? ((ir + 1) / (CROSS_SECTION_RADIAL_SAMPLES - 1)) * rMax
        : rMax;
      const x0 = rToX(r0);
      const x1 = ir < CROSS_SECTION_RADIAL_SAMPLES - 1 ? rToX(r1) : pad.l + pW;

      for (let iz = 0; iz < zSamples.length; iz++) {
        const cell = cells[ir * zSamples.length + iz];
        const z0 = zSamples[iz];
        const zPrev = iz > 0 ? 0.5 * (zSamples[iz - 1] + z0) : z0;
        const zNext = iz < zSamples.length - 1 ? 0.5 * (z0 + zSamples[iz + 1]) : z0;
        const zTop = iz < zSamples.length - 1 ? zNext : z0 + (z0 - zPrev);
        const zBot = iz > 0 ? zPrev : z0 - (zNext - z0);

        if (zTop < -xsH || zBot > xsH) continue;

        let brightness = cell.mp;
        if (state.xsShadeMode === "signal") {
          brightness = Math.max(0, cell.mx * ux + cell.my * uy);
        }

        const y0 = zToY(Math.min(zTop, xsH));
        const y1 = zToY(Math.max(zBot, -xsH));
        x.fillStyle = hue2rgb(cell.phaseDeg, clamp(brightness, 0, 1));
        x.fillRect(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0) + 1, Math.abs(y1 - y0) + 1);
      }
    }

    x.restore();
  }

  const sh = (f.slice_half ?? 0.005) * 1e3;
  const yTop = zToY(sh);
  const yBot = zToY(-sh);
  x.strokeStyle = "rgba(34,197,94,.6)";
  x.lineWidth = 1;
  x.setLineDash([4, 3]);
  x.beginPath(); x.moveTo(pad.l, yTop); x.lineTo(pad.l + pW, yTop); x.stroke();
  x.beginPath(); x.moveTo(pad.l, yBot); x.lineTo(pad.l + pW, yBot); x.stroke();
  x.setLineDash([]);
  x.fillStyle = "rgba(34,197,94,.6)";
  x.font = "bold 7px monospace";
  x.fillText("slice", pad.l + 2, (yTop + yBot) / 2 + 3);

  x.strokeStyle = "rgba(255,255,255,.12)";
  x.lineWidth = 0.5;
  x.beginPath();
  x.moveTo(pad.l, pad.t);
  x.lineTo(pad.l, pad.t + pH);
  x.lineTo(pad.l + pW, pad.t + pH);
  x.stroke();

  x.fillStyle = TX2;
  x.font = "7px monospace";
  x.textAlign = "right";
  x.globalAlpha = 0.7;
  const zTickStep = xsH > 50 ? 50 : xsH > 20 ? 10 : xsH > 8 ? 5 : 2;
  for (let z = -Math.floor(xsH / zTickStep) * zTickStep; z <= xsH; z += zTickStep) {
    const y = zToY(z);
    if (y < pad.t + 2 || y > pad.t + pH - 2) continue;
    x.fillText(String(z), pad.l - 3, y + 3);
    x.strokeStyle = "rgba(255,255,255,.05)";
    x.lineWidth = 0.3;
    x.beginPath();
    x.moveTo(pad.l, y);
    x.lineTo(pad.l + pW, y);
    x.stroke();
  }

  x.textAlign = "center";
  const rTickStep = rMax > 60 ? 20 : rMax > 30 ? 10 : 5;
  for (let r = 0; r <= rMax; r += rTickStep) {
    const px = rToX(r);
    if (px < pad.l + 2 || px > pad.l + pW - 2) continue;
    x.fillText(String(r), px, pad.t + pH + 11);
    x.strokeStyle = "rgba(255,255,255,.05)";
    x.lineWidth = 0.3;
    x.beginPath();
    x.moveTo(px, pad.t);
    x.lineTo(px, pad.t + pH);
    x.stroke();
  }

  x.fillStyle = TX2;
  x.font = "bold 7px monospace";
  x.globalAlpha = 0.5;
  x.textAlign = "center";
  x.fillText("r [mm]", pad.l + pW / 2, h - 2);
  x.save();
  x.translate(8, pad.t + pH / 2);
  x.rotate(-Math.PI / 2);
  x.fillText("z [mm]", 0, 0);
  x.restore();
  x.globalAlpha = 1;
  x.textAlign = "left";

  for (const iso of state.isos) {
    const px = rToX(iso.r);
    const py = zToY(iso.z);
    if (py < pad.t - 5 || py > pad.t + pH + 5 || px < pad.l - 5 || px > pad.l + pW + 5) continue;
    x.fillStyle = iso.c;
    x.globalAlpha = iso.v ? 0.9 : 0.1;
    x.beginPath();
    x.arc(px, py, 4, 0, Math.PI * 2);
    x.fill();
    x.strokeStyle = "rgba(0,0,0,.6)";
    x.lineWidth = 1;
    x.globalAlpha = iso.v ? 0.7 : 0.1;
    x.beginPath();
    x.arc(px, py, 4, 0, Math.PI * 2);
    x.stroke();
    x.globalAlpha = 1;
  }
}

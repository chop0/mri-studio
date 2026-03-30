import { setupCanvas, clamp } from "../canvas";
import { BG, BG2, GR, TX2, AC, CUR } from "../constants";
import { DrawState } from "../types";

interface SegmentBounds {
  t0: number;  // segment start (μs)
  tF: number;  // end of free-precession region (μs)
  tE: number;  // segment end (μs)
}

function niceTick(span: number) {
  return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 500 : 200;
}

export function drawTimeline(canvas: HTMLCanvasElement | null, state: DrawState) {
  const s = setupCanvas(canvas);
  if (!s || !state.D?.field?.segments || !state.pulse) return;
  const { ctx: x, w, h } = s;
  const { segments } = state.D.field;

  // Build segment boundary table
  const segBounds: SegmentBounds[] = [];
  let tAcc = 0;
  for (const seg of segments) {
    const nTotal = seg.n_free + seg.n_pulse;
    segBounds.push({ t0: tAcc, tF: tAcc + seg.n_free * seg.dt * 1e6, tE: tAcc + nTotal * seg.dt * 1e6 });
    tAcc += nTotal * seg.dt * 1e6;
  }

  const pad = { l: 40, r: 6, t: 2, b: 12 };
  const pW  = w - pad.l - pad.r;
  const pH  = h - pad.t - pad.b;

  x.fillStyle = BG;
  x.fillRect(0, 0, w, h);

  const { vS, vE } = state;
  const vSpan = vE - vS;
  const tPx   = (t: number) => pad.l + (t - vS) / vSpan * pW;

  // Waveform tracks: |B₁|, Gz, Gx
  const tracks = [
    { label: "|B₁|", max: 250e-6, fn: (u: number[]) => Math.sqrt(u[0] ** 2 + u[1] ** 2), color: "#f59e0b", fill: true,  centred: false },
    { label: "Gz",   max: 0.035,  fn: (u: number[]) => u[3],                               color: "#3b82f6", fill: false, centred: true  },
    { label: "Gx",   max: 0.035,  fn: (u: number[]) => u[2],                               color: "#ef4444", fill: false, centred: true  },
  ];

  const tH = pH / tracks.length;

  tracks.forEach((track, ti) => {
    const y0 = pad.t + ti * tH;

    // Background stripe
    x.fillStyle = ti % 2 ? BG2 : BG;
    x.fillRect(pad.l, y0, pW, tH);

    // Row divider
    x.strokeStyle = GR; x.lineWidth = 0.5;
    x.beginPath(); x.moveTo(pad.l, y0 + tH); x.lineTo(pad.l + pW, y0 + tH); x.stroke();

    // Label
    x.fillStyle = TX2; x.font = "bold 8px monospace"; x.textAlign = "right";
    x.fillText(track.label, pad.l - 4, y0 + tH / 2 + 3);
    x.textAlign = "left";

    // Centre line for signed tracks
    if (track.centred) {
      x.strokeStyle = "rgba(255,255,255,.04)"; x.lineWidth = 0.5;
      x.beginPath(); x.moveTo(pad.l, y0 + tH / 2); x.lineTo(pad.l + pW, y0 + tH / 2); x.stroke();
    }

    // Segment shading (RF region slightly lighter)
    segBounds.forEach((sb, si) => {
      if (sb.tE < vS || sb.t0 > vE) return;
      const xF = Math.max(pad.l, tPx(sb.tF));
      const xE = Math.min(pad.l + pW, tPx(sb.tE));
      x.fillStyle = "rgba(255,255,255,.02)";
      x.fillRect(xF, y0, xE - xF, tH);
      if (si > 0) {
        const xD = tPx(sb.t0);
        x.strokeStyle = "rgba(255,255,255,.06)"; x.lineWidth = 0.5;
        x.beginPath(); x.moveTo(xD, y0); x.lineTo(xD, y0 + tH); x.stroke();
      }
    });

    // Waveform path
    x.save();
    x.beginPath(); x.rect(pad.l, y0, pW, tH); x.clip();
    x.beginPath(); x.strokeStyle = track.color; x.lineWidth = 1.2; x.globalAlpha = 0.8;
    let started = false;
    for (let si = 0; si < segments.length && si < state.pulse!.length; si++) {
      const seg   = segments[si];
      const steps = state.pulse![si];
      let t       = segBounds[si].t0;
      for (const step of steps) {
        if (t >= vS - vSpan * 0.01 && t <= vE + vSpan * 0.01) {
          const v   = track.fn(step);
          const px  = tPx(t);
          const py  = track.centred
            ? y0 + tH / 2 - (v / track.max) * tH / 2
            : y0 + tH - (v / track.max) * tH * 0.85;
          if (!started) { x.moveTo(px, py); started = true; } else x.lineTo(px, py);
        }
        t += seg.dt * 1e6;
      }
    }
    x.stroke(); x.globalAlpha = 1; x.restore();
  });

  // ── Time-window highlight ─────────────────────────────────────────────────
  const xS = clamp(tPx(state.tS), pad.l, pad.l + pW);
  const xE = clamp(tPx(state.tE), pad.l, pad.l + pW);
  x.fillStyle = AC; x.globalAlpha = 0.06;
  x.fillRect(xS, pad.t, xE - xS, pH);
  x.globalAlpha = 1;
  for (const xh of [xS, xE]) {
    x.fillStyle = AC; x.globalAlpha = 0.7;
    x.fillRect(xh - 1.5, pad.t, 3, pH);
    x.globalAlpha = 1;
  }

  // ── Time cursor ───────────────────────────────────────────────────────────
  const xC = clamp(tPx(state.tC), pad.l, pad.l + pW);
  x.strokeStyle = CUR; x.lineWidth = 1.5; x.globalAlpha = 0.8;
  x.beginPath(); x.moveTo(xC, pad.t); x.lineTo(xC, pad.t + pH); x.stroke();
  x.globalAlpha = 1;

  // ── Segment index labels ──────────────────────────────────────────────────
  x.font = "bold 7px monospace"; x.textAlign = "center";
  x.fillStyle = TX2; x.globalAlpha = 0.3;
  segBounds.forEach((sb, si) => {
    if (sb.tE < vS || sb.t0 > vE) return;
    x.fillText(String(si), (tPx(sb.t0) + tPx(sb.tE)) / 2, pad.t + 8);
  });
  x.textAlign = "left"; x.globalAlpha = 1;

  // ── Time axis ticks ───────────────────────────────────────────────────────
  x.fillStyle = TX2; x.font = "7px monospace"; x.textAlign = "center"; x.globalAlpha = 0.4;
  const tickStep = niceTick(vSpan);
  for (let t = Math.ceil(vS / tickStep) * tickStep; t <= vE; t += tickStep) {
    const px = tPx(t);
    if (px > pad.l + 4 && px < pad.l + pW - 4) {
      x.fillText((t / 1000).toFixed(t % 1000 ? 1 : 0) + "ms", px, h - 2);
    }
  }
  x.textAlign = "left"; x.globalAlpha = 1;
}

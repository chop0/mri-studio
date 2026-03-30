/** Set up a canvas for the current device pixel ratio. Returns null if not ready. */
export function setupCanvas(canvas: HTMLCanvasElement | null) {
  if (!canvas) return null;
  const dpr = devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width  = rect.width  * dpr;
  canvas.height = rect.height * dpr;
  const ctx = canvas.getContext("2d")!;
  ctx.scale(dpr, dpr);
  return { ctx, w: rect.width, h: rect.height };
}

/**
 * Project a 3-D point onto the 2-D canvas plane given camera angles (th, ph),
 * a uniform scale, and the canvas centre (cx, cy).
 * Returns [screenX, screenY, depth] — depth > 0 means in front.
 */
export function project(
  mx: number, my: number, mz: number,
  theta: number, phi: number,
  scale: number,
  cx: number, cy: number,
): [number, number, number] {
  const ct = Math.cos(theta), st = Math.sin(theta);
  const cp = Math.cos(phi),   sp = Math.sin(phi);
  return [
    cx + (mx * ct - my * st) * scale,
    cy + (mx * st * sp + my * ct * sp - mz * cp) * scale,
    -(mx * st * cp + my * ct * cp + mz * sp),
  ];
}

export function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v));
}

export function hue2rgbBytes(phaseDeg: number, brightness: number): [number, number, number] {
  const hh = (((phaseDeg % 360) + 360) % 360) / 60;
  const hi = hh | 0;
  const f  = hh - hi;
  const q  = 1 - f;
  let r: number, g: number, b: number;
  switch (hi % 6) {
    case 0: r = 1; g = f; b = 0; break;
    case 1: r = q; g = 1; b = 0; break;
    case 2: r = 0; g = 1; b = f; break;
    case 3: r = 0; g = q; b = 1; break;
    case 4: r = f; g = 0; b = 1; break;
    default: r = 1; g = 0; b = q;
  }
  return [~~(r * brightness * 255), ~~(g * brightness * 255), ~~(b * brightness * 255)];
}

/** Convert HSV-like (phase hue + |M⊥| brightness) to an rgb() string. */
export function hue2rgb(phaseDeg: number, brightness: number): string {
  const [r, g, b] = hue2rgbBytes(phaseDeg, brightness);
  return `rgb(${r},${g},${b})`;
}

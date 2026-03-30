// ── Field / pulse data shapes (mirrors bloch_data.json) ──────────────────────

export interface Segment {
  dt: number;
  n_free: number;
  n_pulse: number;
}

export interface FieldData {
  r_mm: number[];
  z_mm: number[];
  B0n: number;
  dBz_uT: number[][];
  Mx0?: number[][];
  My0?: number[][];
  Mz0?: number[][];
  FOV_X: number;
  FOV_Z: number;
  gamma: number;
  T1: number;
  T2: number;
  segments: Segment[];
  slice_half?: number;
}

/** One time-step: [Bx, By, Gx, Gz] */
export type PulseStep = [number, number, number, number];

/** All steps in one segment */
export type PulseSegment = PulseStep[];

/**
 * `pulses` maps scenario names to PulseSegment arrays, EXCEPT for the special
 * "grape" key which maps iteration-number strings to PulseSegment arrays.
 */
export interface Pulses {
  grape?: Record<string, PulseSegment[]>;
  [key: string]: PulseSegment[] | Record<string, PulseSegment[]> | undefined;
}

export interface BlochData {
  field: FieldData;
  fixed: Record<string, PulseSegment[]>;
  /** Top-level grape data (trajectory snapshots, keyed by iteration number string). */
  grape?: Record<string, unknown>;
  pulses: Pulses;
}

// ── UI state ──────────────────────────────────────────────────────────────────

export interface Isochromat {
  r: number;          // radial position (mm)
  z: number;          // axial position (mm)
  c: string;          // CSS colour
  v: boolean;         // visible
  t: number[] | null; // flat trajectory: [t, mx, my, mz, isRF, …]
  n: string;          // display name
}

export interface CamState {
  th: number;  // azimuth (radians)
  ph: number;  // elevation (radians)
  zm: number;  // zoom factor
}

export interface PhaseMapData {
  yArr: number[];
  data: Array<Array<{ t: number; ph: number; mp: number }>>;
  nY: number;
  label?: string;
  ticks?: number[];
}

// ── Shared draw-state passed to every canvas renderer ────────────────────────

export interface DrawState {
  D: BlochData | null;
  pulse: PulseSegment[] | null;
  isos: Isochromat[];
  cam: CamState;
  tS: number;      // window start  (μs)
  tE: number;      // window end    (μs)
  vS: number;      // viewport start (μs)
  vE: number;      // viewport end   (μs)
  tC: number;      // cursor time   (μs)
  showMp: boolean;
  xsH: number;     // cross-section half-height (mm)
}

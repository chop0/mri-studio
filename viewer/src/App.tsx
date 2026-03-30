import { useState, useRef, useEffect, useCallback, useMemo } from "react";
import type { Dispatch, SetStateAction } from "react";

import { COLORS, BG, BG2, GR, TX, TX2, AC, CUR } from "./constants";
import { BlochData, CamState, DrawState, Isochromat, PhaseMapData, PulseSegment } from "./types";
import { clamp } from "./canvas";
import { sim, getPulse, compPhaseZ, compPhaseR } from "./physics";
import { drawSphere }       from "./draw/sphere";
import { drawTimeline }     from "./draw/timeline";
import { drawPlots }        from "./draw/plots";
import { drawPhaseMap }     from "./draw/phaseMap";
import { drawCrossSection } from "./draw/crossSection";

// ── Typed shared ref (avoids stale closures in event handlers) ────────────────

interface SharedRef {
  data:    BlochData | null;
  pulse:   PulseSegment[] | null;
  isos:    Isochromat[];
  setIsos: Dispatch<SetStateAction<Isochromat[]>>;
  cam:     CamState;
  setCam:  Dispatch<SetStateAction<CamState>>;
  tS: number;  setTS: Dispatch<SetStateAction<number>>;
  tE: number;  setTE: Dispatch<SetStateAction<number>>;
  vS: number;  setVS: Dispatch<SetStateAction<number>>;
  vE: number;  setVE: Dispatch<SetStateAction<number>>;
  tC: number;  setTC: Dispatch<SetStateAction<number>>;
  mt:   number;
  xsH:  number;  setXsH: Dispatch<SetStateAction<number>>;
  showMp: boolean;
  nCI:  number;  setNCI: Dispatch<SetStateAction<number>>;
}

// ── Default isochromats ───────────────────────────────────────────────────────

const DEFAULT_ISOS = [
  { r: 0,  z: 0,  n: "Centre"      },
  { r: 0,  z: 2,  n: "z=2"         },
  { r: 0,  z: 4,  n: "z=4 (edge)"  },
  { r: 0,  z: 10, n: "z=10 (out)"  },
  { r: 15, z: 0,  n: "r=15"        },
];

function makeIsos(data: BlochData, pulse: PulseSegment[]): Isochromat[] {
  const defs = data.iso?.length
    ? data.iso.map(([n], i) => DEFAULT_ISOS[i] ? { ...DEFAULT_ISOS[i], n } : { r: 0, z: 0, n })
    : DEFAULT_ISOS;
  return defs.map((def, i) => ({
    r: def.r, z: def.z,
    c: COLORS[i % COLORS.length],
    v: true,
    t: sim(data, def.r, def.z, pulse),
    n: def.n,
  }));
}

function totalTime(data: BlochData): number {
  return data.field.segments.reduce(
    (s, seg) => s + (seg.n_free + seg.n_pulse) * seg.dt * 1e6, 0
  );
}

function defaultIsoCount(data: BlochData): number {
  return data.iso?.length ?? DEFAULT_ISOS.length;
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function App() {
  const [data,     setData]     = useState<BlochData | null>(null);
  const [scen,     setScen]     = useState("");
  const [gIdx,     setGIdx]     = useState(0);
  const [tS,       setTS]       = useState(0);
  const [tE,       setTE]       = useState(1000);
  const [vS,       setVS]       = useState(0);
  const [vE,       setVE]       = useState(1000);
  const [tC,       setTC]       = useState(1000);
  const [cam,      setCam]      = useState<CamState>({ th: 0.72, ph: 0.38, zm: 1 });
  const [isos,     setIsos]     = useState<Isochromat[]>([]);
  const [showMp,   setShowMp]   = useState(false);
  const [xsH,      setXsH]      = useState(20);
  const [nCI,      setNCI]      = useState(0);
  const [pmZ,      setPmZ]      = useState<PhaseMapData | null>(null);
  const [pmR,      setPmR]      = useState<PhaseMapData | null>(null);
  const [dragOver, setDragOver] = useState(false);

  const canvases = {
    sphere: useRef<HTMLCanvasElement>(null),
    tl:     useRef<HTMLCanvasElement>(null),
    plots:  useRef<HTMLCanvasElement>(null),
    pmZ:    useRef<HTMLCanvasElement>(null),
    pmR:    useRef<HTMLCanvasElement>(null),
    xs:     useRef<HTMLCanvasElement>(null),
  };

  // Shared ref that event handlers read from to avoid stale closures
  const R = useRef<SharedRef>({} as SharedRef);

  const mt     = data ? totalTime(data) : 1000;
  const iterKeys = useMemo(
    () => scen && data?.scenarios?.[scen]
      ? Object.keys(data.scenarios[scen].pulses).sort((a, b) => Number(a) - Number(b))
      : [],
    [data, scen]
  );
  const iterNums = useMemo(() => iterKeys.map(Number), [iterKeys]);
  const pulse = useMemo(
    () => getPulse(data, scen, iterKeys[gIdx] ?? null),
    [data, scen, gIdx, iterKeys]
  );

  R.current = { data, pulse, isos, setIsos, cam, setCam, tS, setTS, tE, setTE, vS, setVS, vE, setVE, tC, setTC, mt, xsH, setXsH, showMp, nCI, setNCI };

  // Recompute trajectories + phase maps when pulse changes
  useEffect(() => {
    if (!data || !pulse) return;
    setIsos(prev => prev.map(o => ({ ...o, t: sim(data, o.r, o.z, pulse) })));
    setPmZ(compPhaseZ(data, pulse));
    setPmR(compPhaseR(data, pulse));
  }, [data, pulse]);

  // Redraw all canvases on every render
  const state: DrawState = { D: data, pulse, isos, cam, tS, tE, vS, vE, tC, showMp, xsH };
  useEffect(() => {
    drawSphere(canvases.sphere.current, state);
    drawTimeline(canvases.tl.current, state);
    drawPlots(canvases.plots.current, state);
    drawPhaseMap(canvases.pmZ.current, pmZ, state, { title: "φ(z, t) at r=0", ticks: [-4, -2, 0, 2, 4], sliceBounds: true });
    drawPhaseMap(canvases.pmR.current, pmR, state, { title: "φ(r, t) at z=0", ticks: [0, 10, 20, 30] });
    drawCrossSection(canvases.xs.current, state);
  });

  // ── Canvas event handlers ─────────────────────────────────────────────────

  // Sphere: drag to rotate, scroll to zoom
  useEffect(() => {
    const cv = canvases.sphere.current;
    if (!cv) return;
    let dragging = false, lastX = 0, lastY = 0;
    const onDown = (e: PointerEvent) => { dragging = true; lastX = e.clientX; lastY = e.clientY; };
    const onMove = (e: PointerEvent) => {
      if (!dragging) return;
      const dx = e.clientX - lastX, dy = e.clientY - lastY;
      lastX = e.clientX; lastY = e.clientY;
      R.current.setCam(c => ({ ...c, th: c.th + dx * 0.008, ph: clamp(c.ph + dy * 0.008, -1.4, 1.4) }));
    };
    const onUp    = () => { dragging = false; };
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      R.current.setCam(c => ({ ...c, zm: clamp(c.zm * (e.deltaY > 0 ? 0.9 : 1.1), 0.5, 5) }));
    };
    cv.addEventListener("pointerdown", onDown);
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    cv.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      cv.removeEventListener("pointerdown", onDown);
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      cv.removeEventListener("wheel", onWheel);
    };
  }, [!!data]);

  // Timeline: drag window handles and cursor, scroll to zoom viewport
  useEffect(() => {
    const cv = canvases.tl.current;
    if (!cv) return;
    const PAD_L = 40, PAD_R = 6;
    let drag: { type: string; x0: number; oS: number; oE: number; oTC: number } | null = null;

    const tAt = (e: PointerEvent) => {
      const rr = cv.getBoundingClientRect();
      const fx = (e.clientX - rr.left - PAD_L) / (rr.width - PAD_L - PAD_R);
      const { vS, vE } = R.current;
      return vS + fx * (vE - vS);
    };

    const onDown = (e: PointerEvent) => {
      const t = tAt(e);
      const { tS, tE, vS, vE, tC, setTS, setTE } = R.current;
      const vSpan = vE - vS, hw = vSpan * 0.02;
      if      (Math.abs(t - tC) < hw + 8)   drag = { type: "C", x0: e.clientX, oS: tS, oE: tE, oTC: tC };
      else if (Math.abs(t - tS) < hw + 10)  drag = { type: "L", x0: e.clientX, oS: tS, oE: tE, oTC: tC };
      else if (Math.abs(t - tE) < hw + 10)  drag = { type: "R", x0: e.clientX, oS: tS, oE: tE, oTC: tC };
      else if (t > tS && t < tE)            drag = { type: "P", x0: e.clientX, oS: tS, oE: tE, oTC: tC };
      else {
        if (Math.abs(t - tS) < Math.abs(t - tE)) setTS(clamp(t, vS, tE - 10));
        else                                      setTE(clamp(t, tS + 10, vE));
      }
      cv.setPointerCapture(e.pointerId);
    };

    const onMove = (e: PointerEvent) => {
      if (!drag) return;
      const rr = cv.getBoundingClientRect();
      const { vS, vE, tS, tE, setTS, setTE, setTC } = R.current;
      const vSpan = vE - vS;
      const dx    = (e.clientX - drag.x0) / (rr.width - PAD_L - PAD_R) * vSpan;
      const minG  = Math.max(10, vSpan * 0.01);
      if      (drag.type === "C") setTC(clamp(drag.oTC + dx, tS, tE));
      else if (drag.type === "L") setTS(clamp(drag.oS + dx, vS, tE - minG));
      else if (drag.type === "R") setTE(clamp(drag.oE + dx, tS + minG, vE));
      else if (drag.type === "P") {
        const span = drag.oE - drag.oS;
        const ns = clamp(drag.oS + dx, vS, vE - span);
        setTS(ns); setTE(ns + span);
      }
    };

    const onUp = () => { drag = null; };

    const onCursor = (e: PointerEvent) => {
      const t = tAt(e);
      const { tS, tE, tC, vS, vE } = R.current;
      const hw = (vE - vS) * 0.02;
      if (Math.abs(t - tC) < hw + 8 || Math.abs(t - tS) < hw + 10 || Math.abs(t - tE) < hw + 10)
        cv.style.cursor = "col-resize";
      else if (t > tS && t < tE)
        cv.style.cursor = "grab";
      else
        cv.style.cursor = "default";
    };

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const rr = cv.getBoundingClientRect();
      const fx = (e.clientX - rr.left - PAD_L) / (rr.width - PAD_L - PAD_R);
      const { vS, vE, mt, setVS, setVE, setTS, setTE } = R.current;
      const vSpan = vE - vS;
      const f     = e.deltaY > 0 ? 1.3 : 1 / 1.3;
      const ns    = clamp(vSpan * f, 100, mt);
      const ctr   = vS + fx * vSpan;
      const nS    = clamp(ctr - fx * ns, 0, mt - ns);
      setVS(nS); setVE(nS + ns);
      setTS(s => clamp(s, nS, nS + ns));
      setTE(s => clamp(s, nS + 10, nS + ns));
    };

    cv.addEventListener("pointerdown", onDown);
    cv.addEventListener("pointermove", onCursor);
    cv.addEventListener("pointermove", onMove);
    cv.addEventListener("pointerup", onUp);
    cv.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      cv.removeEventListener("pointerdown", onDown);
      cv.removeEventListener("pointermove", onCursor);
      cv.removeEventListener("pointermove", onMove);
      cv.removeEventListener("pointerup", onUp);
      cv.removeEventListener("wheel", onWheel);
    };
  }, [!!data]);

  // Phase maps: drag cursor
  useEffect(() => {
    for (const ref of [canvases.pmZ, canvases.pmR]) {
      const cv = ref.current;
      if (!cv) continue;
      const update = (e: PointerEvent) => {
        const rr = cv.getBoundingClientRect();
        const fx = (e.clientX - rr.left - 34) / (rr.width - 38);
        const { tS, tE, setTC } = R.current;
        setTC(clamp(tS + fx * (tE - tS), tS, tE));
      };
      const onDown = (e: PointerEvent) => { update(e); cv.setPointerCapture(e.pointerId); };
      const onMove = (e: PointerEvent) => { if (e.buttons) update(e); };
      cv.addEventListener("pointerdown", onDown);
      cv.addEventListener("pointermove", onMove);
    }
  }, [!!data]);

  // Cross-section: click=add, drag=move, right-click=delete, scroll=zoom
  useEffect(() => {
    const cv = canvases.xs.current;
    if (!cv) return;
    const pad = { l: 28, r: 8, t: 8, b: 20 };
    let xd: { i: number } | null = null;

    const canvasToWorld = (e: PointerEvent | MouseEvent) => {
      const rr   = cv.getBoundingClientRect();
      const { xsH, data: d } = R.current;
      if (!d) return { r: 0, z: 0 };
      const rMax = d.field.r_mm[d.field.r_mm.length - 1];
      return {
        r: (e.clientX - rr.left  - pad.l) / (rr.width  - pad.l - pad.r) * rMax,
        z:  xsH - (e.clientY - rr.top - pad.t) / (rr.height - pad.t - pad.b) * 2 * xsH,
      };
    };

    const nearestIso = (r: number, z: number): number => {
      const rr = cv.getBoundingClientRect();
      const { isos: isos_, xsH, data: d } = R.current;
      if (!d) return -1;
      const rMax   = d.field.r_mm[d.field.r_mm.length - 1];
      const rScale = (rr.width  - pad.l - pad.r) / rMax;
      const zScale = (rr.height - pad.t - pad.b) / (2 * xsH);
      let best = -1, bestDist = 1e9;
      isos_.forEach((o, i) => {
        const dist = Math.sqrt(((o.r - r) * rScale) ** 2 + ((o.z - z) * zScale) ** 2);
        if (dist < 10 && dist < bestDist) { bestDist = dist; best = i; }
      });
      return best;
    };

    const onDown = (e: PointerEvent) => {
      const p = canvasToWorld(e), h = nearestIso(p.r, p.z);
      if (h >= 0) { xd = { i: h }; cv.setPointerCapture(e.pointerId); } else xd = null;
    };

    const onMove = (e: PointerEvent) => {
      if (!xd) return;
      const p  = canvasToWorld(e);
      const { data: d, pulse: pl, setIsos } = R.current;
      const r  = +Math.max(0, p.r).toFixed(1), z = +p.z.toFixed(1);
      setIsos(prev => {
        const next = [...prev];
        next[xd!.i] = { ...next[xd!.i], r, z, n: `r=${r} z=${z}`, t: sim(d!, r, z, pl!) };
        return next;
      });
    };

    const onUp = (e: PointerEvent) => {
      if (xd) { xd = null; return; }
      const p  = canvasToWorld(e);
      const { data: d, pulse: pl, nCI, setNCI, setIsos } = R.current;
      const r  = +Math.max(0, p.r).toFixed(1), z = +p.z.toFixed(1);
      const c  = COLORS[nCI % COLORS.length];
      setNCI(n => n + 1);
      setIsos(prev => [...prev, { r, z, c, v: true, t: sim(d!, r, z, pl!), n: `r=${r} z=${z}` }]);
    };

    const onContextMenu = (e: MouseEvent) => {
      e.preventDefault();
      const p = canvasToWorld(e), h = nearestIso(p.r, p.z);
      if (h >= 0) R.current.setIsos(prev => prev.filter((_, i) => i !== h));
    };

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      R.current.setXsH(h => clamp(h * (e.deltaY > 0 ? 0.8 : 1.25), 1, 125));
    };

    cv.addEventListener("pointerdown", onDown);
    cv.addEventListener("pointermove", onMove);
    cv.addEventListener("pointerup", onUp);
    cv.addEventListener("contextmenu", onContextMenu);
    cv.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      cv.removeEventListener("pointerdown", onDown);
      cv.removeEventListener("pointermove", onMove);
      cv.removeEventListener("pointerup", onUp);
      cv.removeEventListener("contextmenu", onContextMenu);
      cv.removeEventListener("wheel", onWheel);
    };
  }, [!!data]);

  // ── File loading ──────────────────────────────────────────────────────────

  const handleFile = useCallback((e: React.ChangeEvent<HTMLInputElement> | { target: { files: FileList } }) => {
    const file = e.target?.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = ev => {
      try {
        const d: BlochData = JSON.parse(ev.target!.result as string);
        const mt2 = totalTime(d);
        setData(d);
        setTS(0); setTE(mt2); setVS(0); setVE(mt2); setTC(mt2);
        const firstScen = Object.keys(d.scenarios)[0] ?? "";
        setScen(firstScen);
        const firstIterKeys = firstScen ? Object.keys(d.scenarios[firstScen].pulses).sort((a, b) => Number(a) - Number(b)) : [];
        setGIdx(Math.max(firstIterKeys.length - 1, 0));
        const initPulse = firstIterKeys.length > 0 ? d.scenarios[firstScen].pulses[firstIterKeys[firstIterKeys.length - 1]] : null;
        if (!initPulse) throw new Error(`No pulse data for scenario "${firstScen}"`);
        setIsos(makeIsos(d, initPulse));
        setNCI(defaultIsoCount(d));
        setXsH(20);
      } catch (err) {
        alert("Bad JSON: " + err);
      }
    };
    reader.readAsText(file);
  }, []);

  // ── Drop-zone (no data loaded yet) ───────────────────────────────────────

  if (!data) {
    return (
      <div className="flex items-center justify-center min-h-screen" style={{ background: BG }}>
        <label
          className="cursor-pointer border-2 border-dashed rounded-xl p-16 text-center transition-colors"
          style={{ borderColor: dragOver ? AC : GR, color: TX, background: dragOver ? "rgba(59,130,246,.08)" : "transparent" }}
          onDragOver={e => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={e => { e.preventDefault(); setDragOver(false); handleFile({ target: { files: e.dataTransfer.files } }); }}
        >
          <p className="text-base mb-1">
            {dragOver ? "Release to load" : "Drop"}{" "}
            <code className="text-blue-400">bloch_data.json</code>
            {dragOver ? "" : " or click"}
          </p>
          <input type="file" accept=".json" className="hidden" onChange={handleFile} />
        </label>
      </div>
    );
  }

  // ── Main viewer ───────────────────────────────────────────────────────────

  const scenarios = Object.keys(data.scenarios);
  const resetIsos = () => { setIsos(makeIsos(data, pulse!)); setNCI(defaultIsoCount(data)); };
  const clearIsos = () => { setIsos([]); setNCI(0); };

  return (
    <div className="min-h-screen p-2" style={{ background: BG, color: TX, fontFamily: "ui-monospace,monospace", fontSize: 12 }}>

      {/* Controls row */}
      <div className="flex items-center gap-2 mb-2 flex-wrap text-xs">
        <select
          className="px-2 py-1 rounded border text-xs"
          style={{ background: BG2, borderColor: GR, color: TX }}
          value={scen}
          onChange={e => {
            const nextScen = e.target.value;
            const nextKeys = Object.keys(data.scenarios[nextScen].pulses).sort((a, b) => Number(a) - Number(b));
            setScen(nextScen);
            setGIdx(Math.max(nextKeys.length - 1, 0));
          }}
        >
          {scenarios.map(k => <option key={k}>{k}</option>)}
        </select>

        {iterKeys.length > 1 && (
          <>
            <input
              type="range" min={0} max={iterKeys.length - 1} value={gIdx}
              onChange={e => setGIdx(+e.target.value)}
              className="w-32" style={{ accentColor: AC }}
            />
            <span className="font-bold" style={{ color: CUR }}>iter {iterNums[gIdx]}</span>
          </>
        )}

        <label className="ml-auto flex items-center gap-1 cursor-pointer" style={{ color: TX2 }}>
          <input type="checkbox" checked={showMp} onChange={e => setShowMp(e.target.checked)} className="accent-blue-500" />
          |M⊥| radius
        </label>
      </div>

      {/* Sphere + cross-section */}
      <div className="grid gap-2 mb-1" style={{ gridTemplateColumns: "1fr 280px" }}>
        <canvas ref={canvases.sphere} className="w-full rounded-lg cursor-grab" style={{ height: 360, border: `1px solid ${GR}` }} />

        <div className="flex flex-col gap-1" style={{ height: 360 }}>
          <div className="flex gap-1">
            <input
              type="range" min={2} max={125} value={xsH}
              onChange={e => setXsH(+e.target.value)}
              style={{
                accentColor: AC,
                writingMode: "vertical-lr" as const,
                direction: "rtl" as const,
                width: 14, height: 240,
              }}
            />
            <canvas ref={canvases.xs} className="rounded cursor-crosshair" style={{ width: "100%", height: 240, border: `1px solid ${GR}` }} />
          </div>
          <p className="text-[8px]" style={{ color: TX2 }}>click=add · drag=move · r-click=del · scroll=zoom · z ±{xsH}mm</p>
          <div className="flex gap-1">
            <button className="px-2 py-0.5 rounded text-[9px] border" style={{ borderColor: GR, color: TX2 }} onClick={resetIsos}>Defaults</button>
            <button className="px-2 py-0.5 rounded text-[9px] border" style={{ borderColor: GR, color: TX2 }} onClick={clearIsos}>Clear</button>
          </div>
          <div className="overflow-y-auto" style={{ maxHeight: 100 }}>
            {isos.map((iso, i) => (
              <div key={i} className="flex items-center gap-1 py-0.5" style={{ borderBottom: `1px solid ${GR}` }}>
                <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: iso.c, opacity: iso.v ? 1 : 0.15 }} />
                <span className="flex-1 truncate text-[9px]" style={{ color: TX2 }}>{iso.n}</span>
                <button className="text-[8px] px-1" style={{ color: TX2 }}
                  onClick={() => setIsos(p => { const n = [...p]; n[i] = { ...n[i], v: !n[i].v }; return n; })}>
                  {iso.v ? "hide" : "show"}
                </button>
                <button className="text-[8px] px-1 hover:text-red-400" style={{ color: TX2 }}
                  onClick={() => setIsos(p => p.filter((_, j) => j !== i))}>
                  ×
                </button>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Waveform timeline */}
      <canvas ref={canvases.tl} className="w-full rounded mb-1" style={{ height: 100, border: `1px solid ${GR}`, cursor: "default" }} />

      {/* Phase heatmaps */}
      <div className="grid gap-1 mb-1" style={{ gridTemplateColumns: "1fr 1fr" }}>
        <canvas ref={canvases.pmZ} className="w-full rounded cursor-col-resize" style={{ height: 110, border: `1px solid ${GR}` }} />
        <canvas ref={canvases.pmR} className="w-full rounded cursor-col-resize" style={{ height: 110, border: `1px solid ${GR}` }} />
      </div>

      {/* Angle plots */}
      <canvas ref={canvases.plots} className="w-full rounded" style={{ height: 150 }} />

      <div className="flex items-center gap-3 mt-1 text-[9px]" style={{ color: TX2 }}>
        <span>
          Scroll timeline to zoom · drag <span style={{ color: AC }}>blue</span> handles for time window ·
          drag <span style={{ color: CUR }}>gold</span> cursor on phase maps · cross-section shaded at cursor time
        </span>
      </div>
    </div>
  );
}

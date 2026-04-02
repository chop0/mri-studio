package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipEvaluator;
import ax.xz.mri.model.sequence.SignalClip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU cache for pre-sampled clip waveforms, avoiding repeated
 * {@link ClipEvaluator#evaluate} calls on every frame.
 *
 * <p>Keyed by (clipId, clip hashCode, sample count). The clip's hashCode
 * changes when any field changes (it's a record), so stale entries are
 * never served. Access-order eviction keeps the most recently used entries.
 */
public final class WaveformCache {
    private static final int MAX_ENTRIES = 512;

    private record CacheKey(String clipId, int clipHash, int sampleCount) {}

    private final Map<CacheKey, double[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, double[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /**
     * Get or compute sampled waveform values for a clip.
     *
     * @param clip        the clip to evaluate
     * @param sampleCount number of evenly-spaced samples over the clip duration
     * @return array of {@code sampleCount + 1} signal values
     */
    public double[] getOrCompute(SignalClip clip, int sampleCount) {
        var key = new CacheKey(clip.id(), clip.hashCode(), sampleCount);
        var cached = cache.get(key);
        if (cached != null) return cached;

        var samples = new double[sampleCount + 1];
        for (int i = 0; i <= sampleCount; i++) {
            double u = (double) i / sampleCount;
            double t = clip.startTime() + u * clip.duration();
            samples[i] = ClipEvaluator.evaluate(clip, t);
        }
        cache.put(key, samples);
        return samples;
    }

    /** Discard all cached entries. Call when the editing session changes. */
    public void clear() {
        cache.clear();
    }
}

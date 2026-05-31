package ax.xz.mri.ui.timeline.element.clip;

import ax.xz.mri.model.sequence.ClipEvaluator;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SignalClip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU cache for pre-sampled clip waveforms.
 *
 * <p>Keyed on the fields that determine the wave SHAPE only —
 * {@code (clipId, shape, duration, amplitude, mediaOffset, mediaDuration,
 * sampleCount)}. {@code startTime} is deliberately excluded: the waveform
 * samples computed by {@link ClipEvaluator#evaluate} are functions of
 * {@code mediaU = (mediaOffset + (t − startTime)) / mediaDuration}, and the
 * sampler walks {@code t = startTime + u·duration} for {@code u ∈ [0,1]} —
 * so {@code startTime} cancels out and the resulting samples are identical
 * regardless of clip position. Translating a clip during a drag must NOT
 * invalidate this cache; pre-2026 the key was {@code clip.hashCode()} which
 * flipped on every record mutation, so dragging a 100-clip arrangement
 * re-ran ~32 000 evaluator calls per frame.
 *
 * <p>Access-order eviction keeps the most-recently-used 512 entries.
 */
public final class WaveformCache {
    private static final int MAX_ENTRIES = 512;

    private record Key(String clipId, ClipShape shape, double duration, double amplitude,
                       double mediaOffset, double mediaDuration, int sampleCount) {}

    private final Map<Key, double[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, double[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** Get or compute {@code sampleCount + 1} evenly-spaced samples across the clip. */
    public double[] getOrCompute(SignalClip clip, int sampleCount) {
        var key = new Key(clip.id(), clip.shape(), clip.duration(), clip.amplitude(),
            clip.mediaOffset(), clip.mediaDuration(), sampleCount);
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

    public void clear() { cache.clear(); }

    /** Live entry count. Exposed for tests pinning cache stability under drag. */
    public int size() { return cache.size(); }
}

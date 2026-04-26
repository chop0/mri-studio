package ax.xz.mri.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Thread-safe size-bounded LRU cache backed by a synchronised {@link LinkedHashMap}. */
public final class LruCache<K, V> {
    private final Map<K, V> map;

    public LruCache(int maxEntries) {
        this.map = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    public V get(K key) {
        synchronized (map) {
            return map.get(key);
        }
    }

    public void put(K key, V value) {
        synchronized (map) {
            map.put(key, value);
        }
    }

    public V getOrCreate(K key, Supplier<V> supplier) {
        synchronized (map) {
            var value = map.get(key);
            if (value == null) {
                value = supplier.get();
                map.put(key, value);
            }
            return value;
        }
    }

    public void clear() {
        synchronized (map) {
            map.clear();
        }
    }
}

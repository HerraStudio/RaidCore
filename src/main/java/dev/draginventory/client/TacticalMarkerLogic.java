package dev.draginventory.client;

import java.util.SequencedMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Marker rules and screen-space calculations without Minecraft state. */
public final class TacticalMarkerLogic {
    public static final long LIFETIME_MS = 60_000;
    public static final int MAX_MARKERS = 5;
    private TacticalMarkerLogic() {}

    public static boolean expired(long now, long createdAt) { return now - createdAt >= LIFETIME_MS; }

    /** Refreshes retain their original FIFO position; only a new target displaces the oldest. */
    static <K, V> void putMarker(SequencedMap<K, V> markers, K key, V marker) {
        markers.put(key, marker);
        while (markers.size() > MAX_MARKERS) markers.pollFirstEntry();
    }

    /**
     * 外部标点容量控制（v2.5.7）：外部条目由 {@code isExternal} 谓词判定，数量超过
     * {@code maxExternal} 时按插入序逐出<b>最旧的外部条目</b>（非外部条目 = 玩家手动
     * 名额，绝不受影响）。返回被逐出的条目（已从表中移除；容量未超返回空列表），
     * 供调用方触发 {@code TacticalMarkerListener.Cause.EXPIRED} 事件。
     */
    public static <K, V> List<Map.Entry<K, V>> evictOldestExternal(
            SequencedMap<K, V> markers, Predicate<K> isExternal, int maxExternal) {
        List<K> externalKeys = new ArrayList<>();
        for (K key : markers.sequencedKeySet()) if (isExternal.test(key)) externalKeys.add(key);
        int excess = externalKeys.size() - maxExternal;
        if (excess <= 0) return List.of();
        List<Map.Entry<K, V>> evicted = new ArrayList<>(excess);
        for (int i = 0; i < excess; i++) {
            K key = externalKeys.get(i); // 插入序最旧在前
            evicted.add(Map.entry(key, markers.remove(key)));
        }
        return evicted;
    }

    record MarkerWrite<K,V>(K key, V previous, V written, Map.Entry<K,V> evicted) {}

    /** A receipt lets a double click upgrade the immediate first click as one FIFO operation. */
    static <K,V> MarkerWrite<K,V> writeImmediate(SequencedMap<K,V> markers, K key, V marker) {
        V previous = markers.get(key);
        Map.Entry<K,V> evicted = previous == null && markers.size() >= MAX_MARKERS
                ? Map.entry(markers.firstEntry().getKey(), markers.firstEntry().getValue()) : null;
        putMarker(markers, key, marker);
        return new MarkerWrite<>(key, previous, marker, evicted);
    }

    static <K,V> void upgrade(SequencedMap<K,V> markers, MarkerWrite<K,V> first,
                              K key, V marker, Predicate<V> valid) {
        if (first != null && !Objects.equals(first.key(), key) && markers.get(first.key()) == first.written()) {
            if (first.previous() != null && valid.test(first.previous())) markers.put(first.key(), first.previous());
            else markers.remove(first.key());
            var evicted = first.evicted();
            if (evicted != null && !markers.containsKey(evicted.getKey()) && markers.size() < MAX_MARKERS
                    && valid.test(evicted.getValue())) markers.putFirst(evicted.getKey(), evicted.getValue());
        }
        putMarker(markers, key, marker);
    }

    public record Appearance(float scale, float offsetY, float opacity) {}

    public static Appearance appearance(long ageMillis) {
        float t = (float) Math.clamp(ageMillis / 320.0, 0, 1);
        float remaining = 1 - t;
        // Ease-out-back gives a small overshoot; translation decelerates with ease-out-cubic.
        float back = 1 - 2.70158f * remaining * remaining * remaining + 1.70158f * remaining * remaining;
        float fade = (float) Math.clamp(ageMillis / 130.0, 0, 1);
        return new Appearance(0.55f + 0.45f * back, 8 * remaining * remaining * remaining,
                fade * fade * (3 - 2 * fade));
    }

    public static int distanceMeters(double dx, double dy, double dz) {
        return (int) Math.floor(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    public static float alpha(double screenDistance) {
        float t = (float) Math.clamp(screenDistance / 75.0, 0, 1);
        return 0.20f + 0.80f * t * t * (3 - 2 * t);
    }

    public record ScreenPoint(float x, float y, boolean edge, float angle) {}

    public static ScreenPoint project(Matrix4f viewProjection, double x, double y, double z, int width, int height) {
        Vector4f clip = viewProjection.transform(new Vector4f((float) x, (float) y, (float) z, 1));
        float divisor = Math.max(Math.abs(clip.w), 0.001f);
        float dx = clip.x / divisor * width * 0.5f;
        float dy = -clip.y / divisor * height * 0.5f;
        boolean behind = clip.w <= 0.001f;
        if (behind && Math.abs(dx) + Math.abs(dy) < 0.01f) dy = 1;
        float halfW = Math.max(1, width / 2f - 30);
        float halfH = Math.max(1, height / 2f - 30);
        boolean edge = behind || Math.abs(dx) > halfW || Math.abs(dy) > halfH;
        if (edge) {
            float scale = Math.min(halfW / Math.max(Math.abs(dx), 0.0001f), halfH / Math.max(Math.abs(dy), 0.0001f));
            dx *= scale;
            dy *= scale;
        }
        return new ScreenPoint(width / 2f + dx, height / 2f + dy, edge, (float) Math.atan2(dy, dx));
    }
}

package dev.draginventory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TacticalMarkerTest {
    private record Click(String target, boolean doubled) {}
    private final List<Click> emitted = new ArrayList<>();
    private final TacticalClickGesture<String> gesture = new TacticalClickGesture<>();
    private void press(String hit, long time) { gesture.press(hit, time, (h, d) -> emitted.add(new Click(h, d))); }
    private void flush(long time) { gesture.flush(time); }

    @Test void singleClickAppearsImmediatelyAndExpiryDoesNotEmitAgain() {
        press("ground", 1_000);
        assertEquals(List.of(new Click("ground", false)), emitted);
        assertTrue(gesture.waiting());
        flush(1_450);
        assertTrue(gesture.waiting());
        flush(1_451);
        assertFalse(gesture.waiting());
        flush(1_600);
        assertEquals(List.of(new Click("ground", false)), emitted);
    }

    @Test void exactThresholdSecondClickUpgradesAtTheCurrentTarget() {
        press("ground", 1_000);
        press("enemy", 1_450);
        flush(2_000);
        assertEquals(List.of(new Click("ground", false), new Click("enemy", true)), emitted);
        assertFalse(gesture.waiting());
    }

    @Test void delayedTickDoesNotMergeTwoSlowClicks() {
        press("one", 1_000);
        press("two", 1_451);
        flush(2_000);
        assertEquals(List.of(new Click("one", false), new Click("two", false)), emitted);
    }

    @Test void thirdClickStartsANewGesture() {
        press("enemy", 1_000);
        press("enemy", 1_100);
        press("item", 1_200);
        flush(1_651);
        assertEquals(List.of(new Click("enemy", false), new Click("enemy", true), new Click("item", false)), emitted);
    }

    @Test void skySecondClickStillRequestsAnEnemyMarker() {
        press("ground", 1_000);
        press(null, 1_100);
        assertEquals(List.of(new Click("ground", false), new Click(null, true)), emitted);
        assertFalse(gesture.waiting());
    }

    @Test void firstSkyClickCanBeFollowedByAnotherSkyEnemyDoubleClick() {
        press(null, 1_000);
        press(null, 1_100);
        assertEquals(List.of(new Click(null, false), new Click(null, true)), emitted);
    }

    @Test void openingMenuKeepsTheImmediateMarkerAndResetsDoubleClick() {
        press("ground", 1_000);
        gesture.cancel();
        assertFalse(gesture.waiting());
        assertEquals(List.of(new Click("ground", false)), emitted);
        press("enemy", 1_100);
        flush(2_000);
        assertEquals(List.of(new Click("ground", false), new Click("enemy", false)), emitted);
    }

    @Test void lifetimeUsesElapsedTimeWithExactSixtySecondBoundary() {
        assertFalse(TacticalMarkerLogic.expired(60_999, 1_000));
        assertTrue(TacticalMarkerLogic.expired(61_000, 1_000));
        assertFalse(TacticalMarkerLogic.expired(61_000, 60_000));
    }

    @Test void distanceUsesEuclideanBlocksAndRoundsDown() {
        assertEquals(13, TacticalMarkerLogic.distanceMeters(3, 4, 12));
        assertEquals(9, TacticalMarkerLogic.distanceMeters(-9.7, 0, 0));
        assertEquals(0, TacticalMarkerLogic.distanceMeters(0, 0, 0));
    }

    @Test void opacityIsSmoothMonotonicAndClamped() {
        assertEquals(0.2f, TacticalMarkerLogic.alpha(0));
        assertEquals(1f, TacticalMarkerLogic.alpha(75));
        assertEquals(1f, TacticalMarkerLogic.alpha(1000));
        float previous = 0.2f;
        for (int d = 1; d <= 100; d++) {
            float alpha = TacticalMarkerLogic.alpha(d);
            assertTrue(alpha >= previous && alpha <= 1);
            previous = alpha;
        }
    }

    private static Matrix4f projection() { return new Matrix4f().perspective((float) Math.toRadians(90), 2, 0.05f, 512); }

    @Test void visibleProjectionAndDistanceHaveKnownCoordinates() {
        var center = TacticalMarkerLogic.project(projection(), 0, 0, -10, 800, 400);
        assertFalse(center.edge());
        assertEquals(400, center.x(), 0.001);
        assertEquals(200, center.y(), 0.001);
        var right = TacticalMarkerLogic.project(projection(), 10, 0, -10, 800, 400);
        assertEquals(600, right.x(), 0.001);
    }

    @Test void aimZoomChangesProjection() {
        var wide = TacticalMarkerLogic.project(projection(), 1, 0, -10, 800, 400);
        var zoomed = TacticalMarkerLogic.project(new Matrix4f().perspective((float) Math.toRadians(30), 2, 0.05f, 512), 1, 0, -10, 800, 400);
        assertTrue(zoomed.x() > wide.x());
    }

    @Test void offscreenTargetStaysInsidePaddedScreen() {
        var point = TacticalMarkerLogic.project(projection(), 200, 30, -1, 800, 400);
        assertTrue(point.edge());
        assertEquals(770, point.x(), 0.001);
        assertTrue(point.y() >= 30 && point.y() <= 370);
    }

    @Test void behindRightTargetPointsRightInsteadOfFlipping() {
        var point = TacticalMarkerLogic.project(projection(), 10, 0, 10, 800, 400);
        assertTrue(point.edge());
        assertEquals(770, point.x(), 0.001);
    }

    @Test void directlyBehindAndAtCameraPlaneRemainFinite() {
        var behind = TacticalMarkerLogic.project(projection(), 0, 0, 10, 800, 400);
        assertTrue(behind.edge());
        assertEquals(370, behind.y(), 0.001);
        var plane = TacticalMarkerLogic.project(projection(), 10, 0, 0, 800, 400);
        assertTrue(plane.edge());
        assertTrue(Float.isFinite(plane.x()) && Float.isFinite(plane.y()));
    }

    @Test void cameraRotationKeepsFrontTargetAtCrosshair() {
        Matrix4f matrix = projection().rotateY((float) Math.PI / 2);
        var point = TacticalMarkerLogic.project(matrix, 10, 0, 0, 800, 400);
        assertEquals(400, point.x(), 0.001);
        assertEquals(200, point.y(), 0.001);
        assertFalse(point.edge());
    }

    @Test void relaxedDoubleClickAcceptsFourHundredMilliseconds() {
        press("enemy", 1_000);
        flush(1_350);
        assertEquals(List.of(new Click("enemy", false)), emitted);
        press("enemy", 1_400);
        flush(2_000);
        assertEquals(List.of(new Click("enemy", false), new Click("enemy", true)), emitted);
    }

    @Test void expiredWindowStartsAnImmediateNewMarker() {
        press("first", 1_000);
        flush(1_451);
        press("second", 1_452);
        assertEquals(List.of(new Click("first", false), new Click("second", false)), emitted);
        assertTrue(gesture.waiting());
    }

    @Test void doubleClickAtCapacityOnlyDisplacesOneOldMarker() {
        var markers = new LinkedHashMap<String, String>();
        for (String key : List.of("a", "b", "c", "d", "e"))
            TacticalMarkerLogic.putMarker(markers, key, "old-" + key);
        var first = TacticalMarkerLogic.writeImmediate(markers, "location", "new-location");
        TacticalMarkerLogic.upgrade(markers, first, "enemy", "new-enemy", value -> true);
        assertEquals(List.of("b", "c", "d", "e", "enemy"), List.copyOf(markers.keySet()));
        assertEquals(List.of("old-b", "old-c", "old-d", "old-e", "new-enemy"), List.copyOf(markers.values()));
    }

    @Test void doubleClickAfterRefreshingAnotherMarkerPreservesItsOriginalValueAndFifoPosition() {
        var markers = new LinkedHashMap<String, String>();
        for (String key : List.of("a", "b", "c", "d", "e"))
            TacticalMarkerLogic.putMarker(markers, key, "old-" + key);
        var first = TacticalMarkerLogic.writeImmediate(markers, "c", "temporary-location");
        TacticalMarkerLogic.upgrade(markers, first, "enemy", "new-enemy", value -> true);
        assertEquals(List.of("b", "c", "d", "e", "enemy"), List.copyOf(markers.keySet()));
        assertEquals(List.of("old-b", "old-c", "old-d", "old-e", "new-enemy"), List.copyOf(markers.values()));
    }

    private record StoredMarker(String kind, long createdAt) {}

    @Test void aMarkerChangedAfterTheFirstClickIsNotRolledBackEvenWhenItsValueIsEqual() {
        var markers = new LinkedHashMap<String, StoredMarker>();
        var firstValue = new StoredMarker("location", 1_000);
        var first = TacticalMarkerLogic.writeImmediate(markers, "location", firstValue);
        var independentlyWritten = new StoredMarker("location", 1_000);
        markers.put("location", independentlyWritten);
        var enemy = new StoredMarker("enemy", 1_100);
        TacticalMarkerLogic.upgrade(markers, first, "enemy", enemy, value -> true);
        assertEquals(List.of("location", "enemy"), List.copyOf(markers.keySet()));
        assertSame(independentlyWritten, markers.get("location"));
        assertSame(enemy, markers.get("enemy"));
    }

    @Test void upgradingDoesNotReviveAnExpiredPreviousMarker() {
        var markers = new LinkedHashMap<String, StoredMarker>();
        markers.put("old", new StoredMarker("location", 0));
        markers.put("retained", new StoredMarker("item", 60_000));
        var first = TacticalMarkerLogic.writeImmediate(markers, "old", new StoredMarker("location", 60_000));
        var enemy = new StoredMarker("enemy", 60_100);
        TacticalMarkerLogic.upgrade(markers, first, "enemy", enemy,
                value -> !TacticalMarkerLogic.expired(60_100, value.createdAt()));
        assertEquals(List.of("retained", "enemy"), List.copyOf(markers.keySet()));
        assertEquals(List.of(new StoredMarker("item", 60_000), enemy), List.copyOf(markers.values()));
    }

    @Test void upgradingDoesNotReviveAnInvalidEvictedMarkerWhenAnotherMarkerWasRemoved() {
        var markers = new LinkedHashMap<String, String>();
        for (String key : List.of("a", "b", "c", "d", "e"))
            TacticalMarkerLogic.putMarker(markers, key, "old-" + key);
        var first = TacticalMarkerLogic.writeImmediate(markers, "location", "temporary-location");
        markers.remove("c");
        TacticalMarkerLogic.upgrade(markers, first, "enemy", "new-enemy", value -> !value.equals("old-a"));
        assertEquals(List.of("b", "d", "e", "enemy"), List.copyOf(markers.keySet()));
        assertEquals(List.of("old-b", "old-d", "old-e", "new-enemy"), List.copyOf(markers.values()));
    }

    @Test void sameTargetUpgradeKeepsItsFifoPositionAndOtherMarkers() {
        var markers = new LinkedHashMap<String, String>();
        for (String key : List.of("a", "target", "c", "d", "e"))
            TacticalMarkerLogic.putMarker(markers, key, "old-" + key);
        var first = TacticalMarkerLogic.writeImmediate(markers, "target", "location");
        TacticalMarkerLogic.upgrade(markers, first, "target", "enemy", value -> true);
        assertEquals(List.of("a", "target", "c", "d", "e"), List.copyOf(markers.keySet()));
        assertEquals(List.of("old-a", "enemy", "old-c", "old-d", "old-e"), List.copyOf(markers.values()));
    }

    @Test void fiveMarkersRemainAndSixthEvictsFirstAcrossTypes() {
        var markers = new LinkedHashMap<String, String>();
        for (String key : List.of("locationA", "enemyA", "itemA", "locationB", "enemyB"))
            TacticalMarkerLogic.putMarker(markers, key, key);
        assertEquals(5, markers.size());
        TacticalMarkerLogic.putMarker(markers, "itemB", "itemB");
        assertEquals(List.of("enemyA", "itemA", "locationB", "enemyB", "itemB"), List.copyOf(markers.keySet()));
        TacticalMarkerLogic.putMarker(markers, "locationC", "locationC");
        assertEquals(List.of("itemA", "locationB", "enemyB", "itemB", "locationC"), List.copyOf(markers.keySet()));
    }

    @Test void refreshingAtCapacityPreservesFifoOrderAndDoesNotEvict() {
        var markers = new LinkedHashMap<Integer, Long>();
        for (int i = 0; i < 5; i++) TacticalMarkerLogic.putMarker(markers, i, 100L);
        TacticalMarkerLogic.putMarker(markers, 0, 500L);
        assertEquals(5, markers.size());
        assertEquals(500L, markers.get(0));
        assertEquals(List.of(0, 1, 2, 3, 4), List.copyOf(markers.keySet()));
        TacticalMarkerLogic.putMarker(markers, 5, 600L);
        assertEquals(List.of(1, 2, 3, 4, 5), List.copyOf(markers.keySet()));
    }

    @Test void removedMarkerFreesCapacityBeforeNextAddition() {
        var markers = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < 5; i++) TacticalMarkerLogic.putMarker(markers, i, "marker");
        markers.remove(2);
        TacticalMarkerLogic.putMarker(markers, 5, "new");
        assertEquals(List.of(0, 1, 3, 4, 5), List.copyOf(markers.keySet()));
    }

    @Test void manySequentialPingsKeepOnlyLastFive() {
        var markers = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < 100; i++) {
            TacticalMarkerLogic.putMarker(markers, i, i);
            assertEquals(Math.min(i + 1, 5), markers.size());
        }
        assertEquals(List.of(95, 96, 97, 98, 99), List.copyOf(markers.keySet()));
    }

    @Test void appearanceStartsSmallAndTransparentAndSettlesExactly() {
        var start = TacticalMarkerLogic.appearance(0);
        assertEquals(0.55f, start.scale(), 0.00001f);
        assertEquals(8f, start.offsetY());
        assertEquals(0f, start.opacity());
        var rest = new TacticalMarkerLogic.Appearance(1, 0, 1);
        assertEquals(rest, TacticalMarkerLogic.appearance(320));
        assertEquals(rest, TacticalMarkerLogic.appearance(60_000));
        assertEquals(start, TacticalMarkerLogic.appearance(-1));
    }

    @Test void appearanceHasBoundedOvershootAndNonlinearTravel() {
        float previousY = 8, previousAlpha = 0, maximumScale = 0;
        for (int age = 0; age <= 320; age++) {
            var pose = TacticalMarkerLogic.appearance(age);
            assertTrue(pose.offsetY() <= previousY && pose.offsetY() >= 0);
            assertTrue(pose.opacity() >= previousAlpha && pose.opacity() <= 1);
            assertTrue(pose.scale() >= 0.5499f && pose.scale() < 1.05f);
            previousY = pose.offsetY(); previousAlpha = pose.opacity();
            maximumScale = Math.max(maximumScale, pose.scale());
        }
        assertTrue(maximumScale > 1.03f);
        assertTrue(TacticalMarkerLogic.appearance(160).offsetY() < 2); // Linear would be 4.
        assertEquals(1f, TacticalMarkerLogic.appearance(130).opacity());
    }

    @Test void appearanceUsesElapsedTimeAndCanReplayOnRefresh() {
        long createdAt = 1_000;
        var beforeRefresh = TacticalMarkerLogic.appearance(2_000 - createdAt);
        createdAt = 2_000;
        var afterRefresh = TacticalMarkerLogic.appearance(2_000 - createdAt);
        assertEquals(1, beforeRefresh.scale());
        assertTrue(afterRefresh.scale() < beforeRefresh.scale());
        // Sampling other frames does not change the pose at a given elapsed time.
        var expected = TacticalMarkerLogic.appearance(160);
        for (int ms = 0; ms < 160; ms += 7) TacticalMarkerLogic.appearance(ms);
        assertEquals(expected, TacticalMarkerLogic.appearance(160));
    }

    // ==================== v2.5.7 外部标点容量控制（evictOldestExternal） ====================

    @Test void externalEvictionKeepsPlayerQuotaUntouchedAndEvictsOldestExternal() {
        var markers = new LinkedHashMap<Object, String>();
        for (String key : List.of("p1", "p2", "p3", "p4", "p5"))
            TacticalMarkerLogic.putMarker(markers, key, "player-" + key);
        markers.put("ext:a", "old-a");
        markers.put("ext:b", "old-b");
        markers.put("ext:c", "old-c");
        // 容量未超：无操作。
        assertTrue(TacticalMarkerLogic.evictOldestExternal(
                markers, k -> k instanceof String s && s.startsWith("ext:"), 3).isEmpty());
        // 第 4 枚外部标点写入后：逐出最旧的 ext:a（插入序），玩家 5 名额原封不动。
        markers.put("ext:d", "new-d");
        var evicted = TacticalMarkerLogic.evictOldestExternal(
                markers, k -> k instanceof String s && s.startsWith("ext:"), 3);
        assertEquals(1, evicted.size());
        assertEquals("ext:a", evicted.get(0).getKey());
        assertEquals("old-a", evicted.get(0).getValue());
        assertEquals(8, markers.size()); // 5 玩家 + 3 外部
        assertTrue(markers.containsKey("p1") && markers.containsKey("p5"));
        assertFalse(markers.containsKey("ext:a"));
        assertTrue(markers.containsKey("ext:d"));
    }

    @Test void externalEvictionPredicateCanExemptTheUpdatingKey() {
        var markers = new LinkedHashMap<String, String>();
        markers.put("ext:a", "old-a");
        markers.put("ext:b", "old-b");
        // 容量 1 + 更新 c（尚不存在）：predicate 排除 c → 计数 [a,b] 超额 1 → 逐出最旧 a；
        // 正在更新的 c 永不在逐出范围（新增条目不会被自己的写入挤掉）。
        var evicted = TacticalMarkerLogic.evictOldestExternal(markers, k -> !k.equals("ext:c"), 1);
        assertEquals(List.of(Map.entry("ext:a", "old-a")), evicted);
        markers.put("ext:c", "new-c");
        assertEquals(List.of("ext:b", "ext:c"), List.copyOf(markers.keySet()));
    }

    @Test void updatingAnExistingKeyNeverEvictsItself() {
        var markers = new LinkedHashMap<String, String>();
        markers.put("ext:a", "old-a");
        markers.put("ext:b", "old-b");
        markers.put("ext:c", "old-c");
        // 容量 2 + 原地更新 a：predicate 排除 a → 计数 [b,c] 恰满 → 不逐出（a 即将被覆盖，
        // 不触发自逐出）；更新后瞬时 3 枚（更新豁免允许，后续任何新放置回收超额）。
        assertTrue(TacticalMarkerLogic.evictOldestExternal(markers, k -> !k.equals("ext:a"), 2).isEmpty());
        markers.put("ext:a", "new-a");
        assertEquals("new-a", markers.get("ext:a"));
        assertEquals(3, markers.size());
    }

    @Test void externalEvictionUnderCapacityIsNoOp() {
        var markers = new LinkedHashMap<String, String>();
        markers.put("a", "1");
        markers.put("b", "2");
        assertTrue(TacticalMarkerLogic.evictOldestExternal(markers, k -> true, 16).isEmpty());
        assertEquals(2, markers.size());
    }
}

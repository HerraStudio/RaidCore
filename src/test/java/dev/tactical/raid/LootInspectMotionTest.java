package dev.tactical.raid;

import dev.tactical.loot.LootInspectMotion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LootInspectMotionTest {
    @Test void drawThenIdleLoopUseActualClipDurations() {
        var motion=new LootInspectMotion(); motion.equip(1000);
        assertEquals("draw",motion.sample(1100,.35f,1.5f,2.6f).clip());
        assertEquals("idle",motion.sample(1350,.35f,1.5f,2.6f).clip());
        assertEquals(.1f,motion.sample(2950,.35f,1.5f,2.6f).seconds(),.001f);
    }
    @Test void inspectionCannotRestartEveryHeldClick() {
        var motion=new LootInspectMotion(); motion.equip(1000);
        assertFalse(motion.inspect(1050,.35f,2.6f));
        assertTrue(motion.inspect(1500,.35f,2.6f));
        assertFalse(motion.inspect(1700,.35f,2.6f));
        assertEquals("inspect",motion.sample(2100,.35f,1.5f,2.6f).clip());
        assertEquals(.6f,motion.sample(2100,.35f,1.5f,2.6f).seconds(),.001f);
        assertEquals("idle",motion.sample(4100,.35f,1.5f,2.6f).clip());
    }
    @Test void switchingItemsOrDisconnectingStopsAndReequipsCleanly() {
        var motion=new LootInspectMotion(); motion.equip(1000); motion.inspect(1400,.35f,2.6f);
        motion.cancel(); assertFalse(motion.active()); assertFalse(motion.inspect(2000,.35f,2.6f));
        motion.equip(5000); assertEquals("draw",motion.sample(5050,.35f,1.5f,2.6f).clip());
    }
}

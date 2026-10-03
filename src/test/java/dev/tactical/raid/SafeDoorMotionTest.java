package dev.tactical.raid;

import dev.tactical.loot.SafeDoorMotion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeDoorMotionTest {
    @Test void untouchedSafeRemainsClosed() {
        var door=new SafeDoorMotion(false);
        for(int i=0;i<80;i++) door.tick(false);
        assertEquals(0,door.progress(1));
        assertEquals(0,door.angle(1));
    }
    @Test void openingInterpolatesMonotonicallyForSixteenTicks() {
        var door=new SafeDoorMotion(false);
        float last=0;
        for(int tick=0;tick<16;tick++) {
            door.tick(true);
            float halfway=door.angle(.5f), end=door.angle(1);
            assertTrue(halfway>=last && end>=halfway);
            assertTrue(end<=60);
            last=end;
        }
        assertTrue(door.finished());
        assertEquals(60,door.angle(1));
    }
    @Test void loadedOpenSafeDoesNotReplayOrClose() {
        var door=new SafeDoorMotion(true);
        assertEquals(60,door.angle(0));
        for(int i=0;i<40;i++) door.tick(true);
        assertEquals(60,door.angle(.4f));
        door.tick(false);
        assertEquals(60,door.angle(1));
    }
}

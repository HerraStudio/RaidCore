package dev.tactical.raid;

import dev.tactical.crack.CrackRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrackRulesTest {
    @Test void seededRoundsAreRepeatableAndAccelerate() {
        for(long seed=0;seed<1000;seed++) {
            var first=CrackRules.round(seed,0); var second=CrackRules.round(seed,1); var third=CrackRules.round(seed,2);
            assertEquals(first,CrackRules.round(seed,0));
            assertEquals(60,first.width()); assertEquals(45,second.width()); assertEquals(30,third.width());
            assertTrue(first.periodMillis()>second.periodMillis() && second.periodMillis()>third.periodMillis());
            assertTrue(first.center()>=70 && first.center()<310);
        }
    }
    @Test void arcsCorrectlyCrossTwelveOClock() {
        var round=new CrackRules.Round(355,60,1500);
        assertTrue(CrackRules.contains(round,5)); assertTrue(CrackRules.contains(round,325));
        assertTrue(CrackRules.contains(round,25)); assertFalse(CrackRules.contains(round,26));
        assertFalse(CrackRules.contains(round,180));
    }
    @Test void clockStartsAtTwelveAndCompletesOneCycle() {
        var round=CrackRules.round(123,0);
        assertEquals(0,CrackRules.angle(123,0,-100,-1));
        assertEquals(180,CrackRules.angle(123,0,round.periodMillis()/2,-1),1e-6);
        assertEquals(0,CrackRules.angle(123,0,round.periodMillis(),-1),1e-6);
    }
    @Test void injuryJitterIsDeterministicAndExpires() {
        assertEquals(CrackRules.angle(1,1,450,125),CrackRules.angle(1,1,450,125));
        assertNotEquals(CrackRules.angle(1,1,450,-1),CrackRules.angle(1,1,450,125));
        assertEquals(CrackRules.angle(1,1,450,-1),CrackRules.angle(1,1,450,650));
    }
}

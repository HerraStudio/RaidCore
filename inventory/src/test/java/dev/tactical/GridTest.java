package dev.tactical;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class GridTest {
    @Test void rigPouchesAreSeparate() {
        assertTrue(Grid.fits(0,new Grid.Rect(0,0,1,2),List.of()));
        assertTrue(Grid.fits(0,new Grid.Rect(3,4,1,1),List.of()));
        assertFalse(Grid.fits(0,new Grid.Rect(0,1,1,2),List.of()));
        assertFalse(Grid.fits(0,new Grid.Rect(0,0,2,1),List.of()));
        assertFalse(Grid.fits(0,new Grid.Rect(0,4,1,2),List.of()));
    }
    @Test void rotationChangesWhetherLongGunFits() {
        var occupied=List.of(new Grid.Rect(0,0,4,6));
        assertFalse(Grid.fits(1,new Grid.Rect(4,0,5,2),occupied));
        assertTrue(Grid.fits(1,new Grid.Rect(4,0,2,5),occupied));
    }
    @Test void edgesAndOverlapAreExact() {
        var occupied=List.of(new Grid.Rect(0,0,2,3));
        assertTrue(Grid.fits(1,new Grid.Rect(2,0,4,6),occupied));
        assertFalse(Grid.fits(1,new Grid.Rect(1,2,2,2),occupied));
        assertFalse(Grid.fits(1,new Grid.Rect(-1,0,1,1),List.of()));
        assertFalse(Grid.fits(1,new Grid.Rect(5,5,2,1),List.of()));
        assertFalse(Grid.fits(2,new Grid.Rect(0,0,1,1),List.of()));
        assertFalse(Grid.fits(1,new Grid.Rect(Integer.MAX_VALUE,0,2,1),List.of()));
    }
}

package dev.tactical.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BagLayoutTest {
    @Test void pocketsUseStorageCellSizeAndStayInsideViewport() {
        assertEquals(BagLayout.GRID_STEP,BagLayout.CELL_SIZE);
        assertTrue(BagLayout.GRID_X>=BagLayout.STORAGE_LEFT);
        assertTrue(BagLayout.GRID_X+4*BagLayout.GRID_STEP+BagLayout.CELL_SIZE<=BagLayout.STORAGE_RIGHT);
    }

    @Test void storageSectionsHaveConsistentCompactGaps() {
        for(int rows=0;rows<=12;rows++) {
            int rigBottom=BagLayout.STORAGE_CONTENT_TOP+BagLayout.storageSectionHeight(rows);
            assertEquals(BagLayout.SECTION_GAP,BagLayout.pocketSectionY(rows)-rigBottom);
            assertEquals(BagLayout.SECTION_HEADER,BagLayout.pocketY(rows)-BagLayout.pocketSectionY(rows));
            assertEquals(BagLayout.SECTION_GAP,BagLayout.backpackY(rows)-BagLayout.pocketSectionY(rows)-BagLayout.POCKET_SECTION_HEIGHT);
            assertTrue(BagLayout.storageSectionHeight(rows)>=78);
            assertTrue(BagLayout.storageSectionHeight(rows)>=16+rows*BagLayout.GRID_STEP);
        }
    }

    @Test void compactSectionsFollowLargerRigCapacity() {
        assertEquals(230,BagLayout.pocketY(5));
        assertEquals(284,BagLayout.backpackY(5));
        assertEquals(7*BagLayout.GRID_STEP,BagLayout.backpackY(12)-BagLayout.backpackY(5));
    }

    @Test void layoutFitsBothStandardAndWideWindows() {
        for(int width:new int[]{720,960}) {
            var fit=BagLayout.fit(width,500);
            assertEquals(16,fit.screenX(16),0.001);
            assertTrue(fit.screenX(BagLayout.WIDTH)<=width-16+0.001);
            assertTrue(fit.screenY(BagLayout.HEIGHT)<=500);
            assertTrue(fit.y()>=0);
        }
    }

    @Test void lootingPanelFitsWithoutCoveringBackpack() {
        assertTrue(BagLayout.LOOT_X>BagLayout.STORAGE_RIGHT);
        assertEquals(6*BagLayout.LOOT_CELL,BagLayout.LOOT_WIDTH);
        for(int width:new int[]{480,720,960}) {
            var fit=BagLayout.fit(width,500,true);
            assertTrue(fit.screenX(BagLayout.WIDTH_WITH_LOOT)<=width-16+0.001);
            assertTrue(fit.screenY(BagLayout.HEIGHT)<=500);
        }
    }

    @Test void playerHeadAlignsWithChestSlotAndFeetRemainVisible() {
        for(boolean armored:new boolean[]{false,true}) {
            int modelScale=armored?170:190;
            float offset=BagLayout.playerOffset(modelScale,armored,1.8f);
            float feet=BagLayout.HEIGHT/2f+(0.9f+offset)*modelScale;
            float head=feet-(armored?1.66f:1.64f)*modelScale;
            assertEquals(75+56/2f,head,0.001);
            assertTrue(feet<BagLayout.HEIGHT-2);
            assertTrue(head-modelScale*0.25f>0);
        }
    }
}

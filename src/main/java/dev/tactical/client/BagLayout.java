package dev.tactical.client;

import java.util.List;

public final class BagLayout {
    public static final int WIDTH=560, HEIGHT=420;
    public static final int STORAGE_X=280, GRID_X=352;
    public static final int STORAGE_LEFT=272, STORAGE_RIGHT=552, STORAGE_TOP=42, STORAGE_BOTTOM=402;
    public static final int STORAGE_CONTENT_TOP=48, GRID_STEP=28, CELL_SIZE=28, SECTION_GAP=10;
    public static final int SECTION_HEADER=16, POCKET_SECTION_HEIGHT=60;
    public static final int SECTION_WIDTH=STORAGE_RIGHT-STORAGE_X;
    public static final int LOOT_X=584, LOOT_TOP=48, LOOT_GRID_TOP=64, LOOT_CELL=28, LOOT_WIDTH=168, LOOT_BOTTOM=402;
    public static final int WIDTH_WITH_LOOT=772, PLAYER_HEAD_Y=103;
    public static final List<EquipmentBox> EQUIPMENT=List.of(
            new EquipmentBox(39,"头盔","helmet",196,12,56,56),
            new EquipmentBox(38,"胸甲","vest",196,75,56,56),
            new EquipmentBox(2,"手枪 · 快捷栏 3","pistol",196,138,56,56),
            new EquipmentBox(3,"近战武器 · 快捷栏 4","knife",196,201,56,56),
            new EquipmentBox(0,"主武器（吊索） · 快捷栏 1","rifle",138,267,114,56),
            new EquipmentBox(1,"主武器（背部） · 快捷栏 2","rifle",138,329,114,56));

    public record EquipmentBox(int id,String label,String icon,int x,int y,int width,int height) {}
    public record Fit(float scale,float x,float y) {
        public double screenX(double position) { return x+position*scale; }
        public double screenY(double position) { return y+position*scale; }
    }

    public static Fit fit(int width,int height) {
        return fit(width,height,false);
    }

    public static Fit fit(int width,int height,boolean looting) {
        float scale=Math.min((width-32f)/((looting?WIDTH_WITH_LOOT:WIDTH)-16),(height-16f)/HEIGHT);
        return new Fit(scale,16-16*scale,(height-HEIGHT*scale)/2);
    }

    public static float playerOffset(int modelScale,boolean armored,float height) {
        return (PLAYER_HEAD_Y-HEIGHT/2f)/modelScale+(armored?1.66f:1.64f)-height/2;
    }

    public static int storageSectionHeight(int rows) { return Math.max(78,16+rows*GRID_STEP); }
    public static int pocketSectionY(int rigRows) { return STORAGE_CONTENT_TOP+storageSectionHeight(rigRows)+SECTION_GAP; }
    public static int pocketY(int rigRows) { return pocketSectionY(rigRows)+SECTION_HEADER; }
    public static int backpackY(int rigRows) { return pocketSectionY(rigRows)+POCKET_SECTION_HEIGHT+SECTION_GAP; }
    public static int gridCellSize(int area) { return CELL_SIZE; }
    public static int gridItemSize(int area,int cells) { return (cells-1)*GRID_STEP+gridCellSize(area); }

    private BagLayout() {}
}

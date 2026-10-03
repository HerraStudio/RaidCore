package dev.tactical.profile;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemProfileTest {
    @Test void fiveTiersUseExactRequestedColors() {
        assertArrayEquals(new int[]{0xFFFFFF,0x4CAF50,0x2196F3,0x9C27B0,0xFFC107},
                java.util.Arrays.stream(Rarity.values()).mapToInt(r->r.rgb).toArray());
        assertArrayEquals(new String[]{"普通","精良","稀有","史诗","传说"},
                java.util.Arrays.stream(Rarity.values()).map(r->r.label).toArray());
        assertEquals(0x662196F3,Rarity.RARE.color(102));
    }
    @Test void wholeFootprintsHaveStrictBounds() {
        var key=new ItemProfile.Key("gwo:content_gun","gwo:ak47");
        var gun=new ItemProfile(key,5,2,Rarity.EPIC); assertEquals(10,gun.width()*gun.height());
        assertThrows(IllegalArgumentException.class,()->new ItemProfile(key,0,2,Rarity.COMMON));
        assertThrows(IllegalArgumentException.class,()->new ItemProfile(key,7,2,Rarity.COMMON));
        assertThrows(IllegalArgumentException.class,()->new ItemProfile(key,5,-1,Rarity.COMMON));
        assertThrows(IllegalArgumentException.class,()->new ItemProfile(key,1,Integer.MAX_VALUE,Rarity.COMMON));
    }
    @Test void gunVariantsOverrideTheBaseWithoutAffectingOtherModels() {
        var base=new ItemProfile.Key("gwo:content_gun","");
        var ak=new ItemProfile.Key("gwo:content_gun","gwo:ak47");
        var pistol=new ItemProfile.Key("gwo:content_gun","gwo:glock17");
        var common=new ItemProfile(base,4,2,Rarity.FINE); var special=new ItemProfile(ak,5,2,Rarity.LEGENDARY);
        var profiles=Map.of(base,common,ak,special);
        assertSame(special,ItemProfile.resolve(profiles,ak));
        assertSame(common,ItemProfile.resolve(profiles,pistol));
        assertNull(ItemProfile.resolve(profiles,new ItemProfile.Key("minecraft:diamond","")));
        assertNotEquals(ak.encoded(),pistol.encoded());
    }
    @Test void invalidIdsCannotCreateAmbiguousSelectors() {
        for(String id:new String[]{"",":","minecraft:DIAMOND","minecraft:diamond@other:gun","../file","minecraft:铁"})
            assertThrows(IllegalArgumentException.class,()->new ItemProfile.Key(id,""));
        assertThrows(IllegalArgumentException.class,()->new ItemProfile.Key("gwo:content_gun","invalid content"));
        assertEquals("minecraft:diamond",new ItemProfile.Key("minecraft:diamond","").encoded());
    }
}

package dev.tactical.loot;

import dev.tactical.profile.ItemProfile;
import dev.tactical.profile.Rarity;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LootRuleTest {
    @Test void coefficientsMultiplyAndClampActualRollProbability() {
        assertEquals(.1,new LootRule("minecraft:stone","物资箱",.5,true).probability(.2),1e-12);
        assertEquals(.4,new LootRule("minecraft:stone","物资箱",2,true).probability(.2),1e-12);
        assertEquals(1,new LootRule("minecraft:stone","物资箱",8,true).probability(.2));
        assertEquals(0,new LootRule("minecraft:stone","",0,true).probability(1));
        assertEquals(0,new LootRule("minecraft:stone","",2,false).probability(1));
    }
    @Test void poisonedRatesAndSelectorsAreRejected() {
        for(double coefficient:new double[]{-1,101,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new LootRule("minecraft:stone","",coefficient,true));
        var rule=new LootRule("minecraft:stone","",1,true);
        for(double chance:new double[]{-1,1.1,Double.NaN,Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->rule.probability(chance));
        assertThrows(IllegalArgumentException.class,()->new LootRule("../stone","",1,true));
        assertThrows(IllegalArgumentException.class,()->new LootRule("minecraft:stone","name\ncontrol",1,true));
        assertThrows(IllegalArgumentException.class,()->new LootRule("minecraft:stone","x".repeat(97),1,true));
    }
    @Test void configuredPoolHasIndependentRatesAndCanBeEmpty() {
        var common=new ItemProfile(new ItemProfile.Key("minecraft:bread",""),1,1,Rarity.COMMON,true,.2);
        var rare=new ItemProfile(new ItemProfile.Key("minecraft:diamond",""),1,1,Rarity.RARE,true,.05);
        var disabled=new ItemProfile(new ItemProfile.Key("minecraft:apple",""),1,1,Rarity.FINE,false,1);
        assertEquals(List.of(common),LootRolls.select(List.of(common,rare,disabled),new LootRule("minecraft:barrel","",2,true),()->.15));
        assertTrue(LootRolls.select(List.of(common,rare),new LootRule("minecraft:barrel","",0,true),()->0).isEmpty());
        assertEquals(List.of(common,rare),LootRolls.select(List.of(common,rare),new LootRule("minecraft:barrel","",100,true),()->.999));
    }
}

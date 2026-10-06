package dev.tactical.loot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

/** The server repeats the aim test; the packet cannot select a container through a wall. */
public final class LootTargeting {
    public static final double REACH=3.5;
    public static BlockPos find(Player player) {
        if(player==null || !player.isAlive() || player.isSpectator()) return null;
        var start=player.getEyePosition();
        var end=start.add(player.getViewVector(1).scale(Math.min(REACH,player.blockInteractionRange())));
        var hit=player.level().clip(new ClipContext(start,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        return hit.getType()==HitResult.Type.BLOCK && LootSettings.searchable(player.level().getBlockState(hit.getBlockPos()))
                ?hit.getBlockPos().immutable():null;
    }
    private LootTargeting() {}
}

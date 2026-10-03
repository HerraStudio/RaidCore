package dev.herrastudio.tacticalactions;

import com.zigythebird.playeranimcore.animation.AnimationData;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonMode;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import net.minecraft.client.player.AbstractClientPlayer;

/** Additive upper-body pose; the player's root, legs and native gun animation remain intact. */
public final class TacticalPeekAnimation implements IAnimation {
    private final AbstractClientPlayer player;
    private float partialTick;

    public TacticalPeekAnimation(AbstractClientPlayer player) {
        this.player = player;
    }

    @Override
    public void setupAnim(AnimationData data) {
        partialTick = data.getPartialTick();
    }

    @Override
    public boolean isActive() {
        return TacticalActionsClient.hasLean(player);
    }

    @Override
    public FirstPersonMode getFirstPersonMode() {
        return FirstPersonMode.NONE;
    }

    @Override
    public PlayerAnimBone get3DTransform(PlayerAnimBone bone) {
        if (FirstPersonMode.isFirstPersonPass()) return bone;
        String name = bone.getName();
        if (("right_arm".equals(name) || "left_arm".equals(name))
                && GwoPeekSupport.isGun(player.getMainHandItem())) {
            // GWO writes its arm aim after PAL; its late hook adds the pose once.
            return bone;
        }
        PeekPoseMath.Delta delta = PeekPoseMath.delta(name,
                TacticalActionsClient.leanAt(player, partialTick),
                PeekStates.offsetAt(player, partialTick));
        bone.positionX += delta.dx();
        bone.positionY += delta.dy();
        bone.positionZ += delta.dz();
        bone.rotX += delta.rx();
        bone.rotY += delta.ry();
        bone.rotZ += delta.rz();
        return bone;
    }
}

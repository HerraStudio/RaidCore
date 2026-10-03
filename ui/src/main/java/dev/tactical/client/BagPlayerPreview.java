package dev.tactical.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

final class BagPlayerPreview extends RemotePlayer {
    private final LocalPlayer source;
    private final PlayerTeam previewTeam=new PlayerTeam(new Scoreboard(),"inventory_preview");

    BagPlayerPreview(LocalPlayer source) {
        super(source.clientLevel,source.getGameProfile());
        this.source=source;
        previewTeam.setNameTagVisibility(Team.Visibility.NEVER);
    }

    void syncArmor() {
        for(var slot:EquipmentSlot.values()) if(slot.getType()==EquipmentSlot.Type.HUMANOID_ARMOR
                && !ItemStack.matches(getItemBySlot(slot),source.getItemBySlot(slot)))
            setItemSlot(slot,source.getItemBySlot(slot).copy());
    }

    @Override public PlayerSkin getSkin() { return source.getSkin(); }
    @Override public boolean isModelPartShown(PlayerModelPart part) { return source.isModelPartShown(part); }
    @Override public HumanoidArm getMainArm() { return source.getMainArm(); }
    @Override public PlayerTeam getTeam() { return previewTeam; }
}

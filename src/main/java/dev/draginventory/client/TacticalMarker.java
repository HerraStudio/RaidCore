package dev.draginventory.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Refreshed markers replace their map entry, retaining a single icon for each target.
 *
 * <p>v2.5.7：生命周期从类级常量升级为<b>逐标点 TTL</b>（{@code ttlMs} 组件）——
 * 玩家手动标点沿用 60 秒默认（四参便捷构造），联动系统经
 * {@code TacticalMarkerManager.placeExternalMarker} 可自定义存活时长（上限 24 小时，
 * 与对局计时同口径）。判定语义不变：{@code now - createdAt >= ttlMs} 即过期。</p>
 */
public record TacticalMarker(Type type, Vec3 position, Entity target, long createdAt, long ttlMs) {
    public enum Type { LOCATION, ENEMY, ITEM }

    /** 玩家手动标点口径：默认 60 秒生命周期（{@link TacticalMarkerLogic#LIFETIME_MS}）。 */
    public TacticalMarker(Type type, Vec3 position, Entity target, long createdAt) {
        this(type, position, target, createdAt, TacticalMarkerLogic.LIFETIME_MS);
    }

    public Vec3 position(float partialTick) {
        return type == Type.ITEM && target != null
                ? target.getPosition(partialTick).add(0, target.getBbHeight() * 0.5, 0) : position;
    }

    public ItemStack item() { return target instanceof ItemEntity item ? item.getItem() : ItemStack.EMPTY; }

    public boolean valid(ClientLevel level, long now) {
        return now - createdAt < ttlMs && (target == null
                || target.level() == level && target.isAlive() && !target.isRemoved()
                && level.getEntity(target.getId()) == target
                && (!(target instanceof ItemEntity item) || !item.getItem().isEmpty()));
    }
}

package dev.draginventory.client.compass;

import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

/**
 * 外部标记提供者：为未来 HERRA 模组（小地图、标记点、队友方位等）预留的注入接口。
 * 仅在客户端主线程于渲染前调用，实现应保持轻量，避免每帧分配大量对象。
 */
public interface CompassMarkerProvider {

    /** 每帧渲染前调用一次，将需要显示的标记写入 out。 */
    void collectMarkers(Context context, Consumer<CompassMark> out);

    /** 采集上下文。 */
    record Context(ClientLevel level, Player player, long nowMillis) {}
}

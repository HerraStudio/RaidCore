package dev.draginventory.client.map;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;

/**
 * 将不可变的工厂几何采样为"语义分类 + 方块真实颜色"。
 *
 * <p>v2.5.0 起每个格子的采样结果是 {@link Sample}（语义 + 颜色）：语义决定强调规则与图层
 * 归属（着色管线见 {@link FactoryMapPalette#bake}），颜色取自命中方块的原版
 * {@link MapColor}（Xaero 风格的真实方块颜色底图）。分类按固定优先级从高到低判定：
 * 未加载/越界 {@link MapSemantics#VOID} → 门 {@link MapSemantics#DOOR} →
 * 墙体 {@link MapSemantics#WALL} → 水体 {@link MapSemantics#WATER} →
 * 不可通行 {@link MapSemantics#BLOCKED} → 楼梯 {@link MapSemantics#STAIR} →
 * 地面装饰 {@link MapSemantics#DECOR} → 边缘 {@link MapSemantics#EDGE} →
 * 可通行地面 {@link MapSemantics#FLOOR}。</p>
 *
 * <p>采样结果以 {@link CellKey}(x, y, z) 为键做访问序 LRU 缓存（上限
 * {@code MAX_CACHED_CELLS} = 120,000 格）：本项目刻意不支持玩家建造/破坏，
 * 方块不会动态变化，因此同一格子跨瓦片、跨帧的重复采样均为 O(1) 查表。
 * 缓存仅在 {@link #prepare} 检测到 ClientLevel 实例变化（切换世界）时清空；
 * 未加载区块返回 VOID 样本且不写缓存，待区块加载后自然重新分类。</p>
 */
public final class FactoryMapSampler {
    /** 访问序 LRU 缓存的容量上限（格子数）。 */
    private static final int MAX_CACHED_CELLS = 120_000;

    /**
     * 单格采样结果：语义分类 + 该语义命中方块的 ARGB 颜色
     * （取自原版 MapColor，见 {@link #sample}；无可用颜色时为语义基础色/回退色）。
     */
    public record Sample(MapSemantics semantic, int color) {}

    private ClientLevel cachedLevel;
    private final Map<CellKey, Sample> samples = new LinkedHashMap<>(4096, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CellKey, Sample> eldest) {
            return size() > MAX_CACHED_CELLS;
        }
    };

    /** 切换世界（ClientLevel 实例变化）时清空采样缓存。 */
    public void prepare(ClientLevel level) {
        if (cachedLevel != level) {
            cachedLevel = level;
            samples.clear();
        }
    }

    /**
     * 主采样入口：带 LRU 缓存的"语义 + 方块颜色"采样。
     * 区块未加载时直接返回 VOID 样本（不写缓存，避免加载后读到陈旧结果）。
     */
    public Sample sample(ClientLevel level, int x, int z, int layerY) {
        prepare(level);
        if (!level.hasChunkAt(new BlockPos(x, layerY, z))) return new Sample(MapSemantics.VOID, 0);
        CellKey key = new CellKey(x, layerY, z);
        Sample cached = samples.get(key);
        if (cached != null) return cached;

        Sample result = classify(level, x, z, layerY);
        samples.put(key, result);
        return result;
    }

    /**
     * 私有静态分类逻辑（不缓存），按优先级从高到低判定：
     * VOID → DOOR → WALL → WATER → BLOCKED → STAIR → DECOR → EDGE → FLOOR。
     * 各语义按命中方块取 {@link MapColor} 真实颜色（系数做明暗纵深，见各分支注释）。
     */
    private static Sample classify(ClientLevel level, int x, int z, int layerY) {
        int y = Mth.clamp(layerY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        BlockPos probe = new BlockPos(x, y, z);
        if (!level.hasChunkAt(probe)) return new Sample(MapSemantics.VOID, 0);

        // 门与活板门虽占据行走平面，但属于战术出入点：脚部/头部空间方块名含 "door" 即判为门，优先于墙体。
        // 颜色取命中的门方块本身，系数 1.0。
        BlockPos door = doorPosAt(level, x, y + 1, z);
        if (door == null) door = doorPosAt(level, x, y + 2, z);
        if (door != null) return new Sample(MapSemantics.DOOR,
                mapColorArgb(level, door, 1.0, FactoryMapPalette.baseColor(MapSemantics.DOOR)));

        // 在选定的行走平面上，脚部/头部空间有碰撞体即为墙体。
        // 颜色取第一个有碰撞体的方块，系数 0.86（墙体略暗做纵深）。
        if (solidAt(level, x, y + 1, z)) return new Sample(MapSemantics.WALL,
                mapColorArgb(level, new BlockPos(x, y + 1, z), 0.86, FactoryMapPalette.baseColor(MapSemantics.WALL)));
        if (solidAt(level, x, y + 2, z)) return new Sample(MapSemantics.WALL,
                mapColorArgb(level, new BlockPos(x, y + 2, z), 0.86, FactoryMapPalette.baseColor(MapSemantics.WALL)));

        // 行走平面上下数格内存在流体则视为水体。颜色取流体所在格方块，系数 1.0。
        int waterFallback = FactoryMapPalette.baseColor(MapSemantics.WATER);
        for (int fy = Math.max(level.getMinBuildHeight(), y - 2);
             fy <= Math.min(level.getMaxBuildHeight() - 1, y + 3); fy++) {
            BlockPos fluid = new BlockPos(x, fy, z);
            if (!level.getBlockState(fluid).getFluidState().isEmpty()) {
                return new Sample(MapSemantics.WATER, mapColorArgb(level, fluid, 1.0, waterFallback));
            }
        }

        // 无有效地板（地板无碰撞或上方两格受阻）则为不可通行区域：
        // 从 y-1 向下扫至 y-4 取第一个非空气方块的颜色，系数 0.5（读作深坑/实体区）。
        BlockPos floor = new BlockPos(x, y, z);
        if (!FactoryMapLayerResolver.isWalkableFloor(level, floor)) {
            return new Sample(MapSemantics.BLOCKED, blockedColor(level, x, y, z));
        }

        // 楼梯、梯子、脚手架：层间的垂直连接。颜色取 floor 方块，系数 1.0。
        var floorState = level.getBlockState(floor);
        String blockName = floorState.getBlock().builtInRegistryHolder().key().location().getPath();
        if (blockName.contains("stairs") || blockName.contains("ladder") || blockName.contains("scaffold")) {
            return new Sample(MapSemantics.STAIR,
                    mapColorArgb(level, floor, 1.0, FactoryMapPalette.baseColor(MapSemantics.STAIR)));
        }

        // 地板上方一格若是无碰撞的植物（花/草/蕨/树苗/蘑菇），仅作地面装饰，不影响通行判定。
        // 颜色取植物方块本身，系数 1.0。
        BlockPos above = floor.above();
        var aboveState = level.getBlockState(above);
        String aboveName = aboveState.getBlock().builtInRegistryHolder().key().location().getPath();
        boolean plant = aboveName.contains("flower") || aboveName.contains("grass") || aboveName.contains("fern")
                || aboveName.contains("sapling") || aboveName.contains("mushroom");
        if (plant && aboveState.getCollisionShape(level, above).isEmpty()) {
            return new Sample(MapSemantics.DECOR,
                    mapColorArgb(level, above, 1.0, FactoryMapPalette.baseColor(MapSemantics.DECOR)));
        }

        // 任一水平邻格不可行走即为边缘（描边提升可读性），其余为普通可通行地面。
        // 两者颜色均取 floor 方块（EDGE 的压暗交给 palette.bake 的语义强调）。
        boolean edge = !isWalkable(level, x + 1, y, z) || !isWalkable(level, x - 1, y, z)
                || !isWalkable(level, x, y, z + 1) || !isWalkable(level, x, y, z - 1);
        return edge
                ? new Sample(MapSemantics.EDGE, mapColorArgb(level, floor, 1.0, FactoryMapPalette.baseColor(MapSemantics.EDGE)))
                : new Sample(MapSemantics.FLOOR, mapColorArgb(level, floor, 1.0, FactoryMapPalette.baseColor(MapSemantics.FLOOR)));
    }

    /**
     * BLOCKED 取色：从 y-1 向下扫至 y-4 找第一个非空气方块，mapColor 系数 0.5；
     * 四格内全是空气（悬空/深渊）时返回固定的深坑色。
     */
    private static int blockedColor(ClientLevel level, int x, int y, int z) {
        for (int dy = 1; dy <= 4; dy++) {
            int by = y - dy;
            if (by < level.getMinBuildHeight() || by > level.getMaxBuildHeight() - 1) break;
            BlockPos pos = new BlockPos(x, by, z);
            if (!level.getBlockState(pos).isAir()) {
                return mapColorArgb(level, pos, 0.5, 0xFF10161B);
            }
        }
        return 0xFF10161B;
    }

    /**
     * 方块 MapColor → ARGB（系数做明暗纵深）。
     *
     * <p>关键 API：{@code state.getMapColor(level, pos)} 返回 {@link MapColor}；
     * {@code calculateRGBColor(Brightness.HIGH)} 返回的是 <b>ABGR 排布</b>（红在最低字节），
     * 需转换为标准 ARGB。MapColor 为 NONE（col == 0）或抛异常时返回回退色。</p>
     */
    private static int mapColorArgb(ClientLevel level, BlockPos pos, double factor, int fallback) {
        try {
            MapColor mc = level.getBlockState(pos).getMapColor(level, pos);
            if (mc == null || mc == MapColor.NONE || mc.col == 0) return fallback;
            int v = mc.calculateRGBColor(MapColor.Brightness.HIGH);  // ABGR：红在最低字节
            int r = v & 0xFF;
            int g = (v >> 8) & 0xFF;
            int b = (v >> 16) & 0xFF;
            r = Mth.clamp((int) Math.round(r * factor), 0, 255);
            g = Mth.clamp((int) Math.round(g * factor), 0, 255);
            b = Mth.clamp((int) Math.round(b * factor), 0, 255);
            return 0xFF000000 | r << 16 | g << 8 | b;
        } catch (RuntimeException e) {
            // 区块卸载竞态等异常场景：用回退色，不让地图渲染崩掉。
            return fallback;
        }
    }

    private static boolean solidAt(ClientLevel level, int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return false;
        BlockPos pos = new BlockPos(x, y, z);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 方块注册路径名含 "door"（涵盖门与活板门）时返回其坐标，否则 null（DOOR 语义检测 + 取色）。 */
    private static BlockPos doorPosAt(ClientLevel level, int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return null;
        BlockPos pos = new BlockPos(x, y, z);
        return level.getBlockState(pos).getBlock().builtInRegistryHolder().key().location().getPath().contains("door")
                ? pos : null;
    }

    private static boolean isWalkable(ClientLevel level, int x, int y, int z) {
        return level.hasChunkAt(new BlockPos(x, y, z))
                && FactoryMapLayerResolver.isWalkableFloor(level, new BlockPos(x, y, z));
    }

    private record CellKey(int x, int y, int z) {}
}

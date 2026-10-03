package dev.draginventory.client.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * 围绕玩家动态扫描可行走平面（层），并为玩家当前区域选出唯一的活动层。
 *
 * <p><b>动态扫描策略</b>（取代旧的固定工厂范围）：
 * 以玩家为中心、radius 为半径（来自配置 scan_radius，64~512）的方形区域做稀疏网格采样：
 * <ul>
 *   <li>水平方向：步长 {@code max(4, radius/32)} 随半径自适应——半径越大采样越稀疏，
 *       采样点数始终约为 (2*radius/step)² ≤ ~4096；</li>
 *   <li>垂直方向：玩家 Y ± min(160, 维度高度范围)，每个采样列做全深度检测，
 *       因此能发现地下室、夹层与高架步道等多层结构。</li>
 * </ul>
 *
 * <p><b>采样预算</b>（点数 × 垂直扫描深度的量级）：最坏约为 4096 采样列 × 321 层
 * ≈ 1.3*10^6 次 floor 判定（每次 {@link #isWalkableFloor} 含 3 个方块的碰撞形状查询），
 * 且只遍历已加载区块，避免长时间卡住 UI。
 *
 * <p>发现结果按 |ΔfloorY| ≤ 3 合并为同层，最多保留 16 层；
 * 楼梯/电梯连接点随采样一并记录，玩家自身楼层必定入榜（见 {@link #resolvePlayerFloor}）。
 *
 * <p><b>共享层目录（v2.5.2 自动层完善）</b>：最近一次 {@link #discover} 的结果会存入
 * 静态缓存（携带所属世界引用），小地图 HUD 不做自己的扫描，而是把
 * {@link #resolvePlayerFloor} 的结果<b>对齐到共享目录的最近层</b>——小地图与
 * 大地图屏幕的自动层完全同层，避免“大地图 Y64 / 小地图 Y65”两层割裂与
 * 瓦片键抖动。目录与世界不匹配时（未扫描过）返回 null，小地图退回原始解析值。</p>
 */
public final class FactoryMapLayerResolver {
    /** 未找到可行走面时的哨兵值。 */
    public static final int NO_FLOOR = Integer.MIN_VALUE;
    /** 垂直扫描半跨度上限：玩家 Y ±160，实际再与维度高度范围取小。 */
    private static final int VERTICAL_SPAN = 160;
    /** 层数上限：超出后按采样数优先保留最显著的层。 */
    private static final int MAX_LAYERS = 16;
    /** 层合并容差：|floorY 差| ≤ 3 视为同一层。 */
    private static final int MERGE_TOLERANCE = 3;

    /** 最近一次 discover 的层目录（供小地图对齐层，见类注释）。 */
    private static LayerCatalog sharedCatalog = LayerCatalog.empty();
    /** sharedCatalog 所属的世界（身份比较；换世界后缓存失效）。 */
    private static Object sharedCatalogWorld;

    private FactoryMapLayerResolver() {}

    /**
     * 以玩家为中心、radius 为半径做一次动态层扫描。
     *
     * <p>水平扫描范围为玩家 ± radius（radius 来自配置 scan_radius，64~512，此处仅做非负保护）；
     * 采样步长 {@code max(4, radius/32)}：半径大时更稀疏，
     * 采样点数 ≈ (2*radius/step)² ≤ ~4096。
     * 垂直扫描范围为玩家 Y ± min(160, 维度高度范围)，逐格寻找可行走面；
     * 找到的平面按 floorY 记入 LayerStats，随后统一做
     * 阈值过滤（采样数 ≥ max(4, 峰值/180)）、±3 格合并与 16 层封顶，
     * 玩家自身楼层（{@link #resolvePlayerFloor}）必定入榜。
     *
     * @param level  客户端世界；为 null 时返回空目录
     * @param player 玩家（扫描中心）；为 null 时返回空目录
     * @param radius 扫描半径（方块数，来自配置 64~512）
     * @return 层目录（携带动态扫描范围与玩家层）
     */
    public static LayerCatalog discover(ClientLevel level, Player player, int radius) {
        if (level == null || player == null) return LayerCatalog.empty();
        int r = Math.max(1, radius);
        int px = Mth.floor(player.getX()), pz = Mth.floor(player.getZ());
        int minX = px - r, maxX = px + r;
        int minZ = pz - r, maxZ = pz + r;
        int playerY = Mth.floor(player.getY());
        // ±160 逻辑保留，但半跨度不超过维度自身的可建设高度范围。
        int heightRange = Math.max(1, level.getMaxBuildHeight() - level.getMinBuildHeight());
        int verticalSpan = Math.min(VERTICAL_SPAN, heightRange);
        int minY = Math.max(level.getMinBuildHeight(), playerY - verticalSpan);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, playerY + verticalSpan);
        int probeY = Mth.clamp(playerY, minY, maxY);
        int step = Math.max(4, r / 32);
        Map<Integer, LayerStats> stats = new HashMap<>();

        // 稀疏采样：既找得到地下室和高架步道，又不会长时间卡住 UI。
        // 每列在 [minY, maxY] 全深度检测；未加载区块直接跳过。
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                if (!level.hasChunkAt(new BlockPos(x, probeY, z))) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos floor = new BlockPos(x, y, z);
                    if (isWalkableFloor(level, floor)) {
                        final int floorY = y;
                        stats.computeIfAbsent(floorY, ignored -> new LayerStats(floorY)).add(x, z, connectorAt(level, floor));
                    }
                }
            }
        }

        int playerFloor = resolvePlayerFloor(level, player);
        stats.computeIfAbsent(playerFloor, ignored -> new LayerStats(playerFloor)).add(px, pz);
        int peak = stats.values().stream().mapToInt(s -> s.samples).max().orElse(1);
        int threshold = Math.max(4, peak / 180);
        List<LayerStats> candidates = stats.values().stream()
                .filter(s -> s.samples >= threshold)
                .sorted(Comparator.comparingInt((LayerStats s) -> s.samples).reversed())
                .toList();

        List<LayerStats> chosen = new ArrayList<>();
        for (LayerStats candidate : candidates) {
            LayerStats merged = chosen.stream().filter(existing -> Math.abs(existing.floorY - candidate.floorY) <= MERGE_TOLERANCE)
                    .findFirst().orElse(null);
            if (merged != null) merged.merge(candidate);
            else if (chosen.size() < MAX_LAYERS) chosen.add(candidate);
        }
        chosen.sort(Comparator.comparingInt(s -> s.floorY));
        List<Layer> layers = new ArrayList<>(chosen.size());
        for (int i = 0; i < chosen.size(); i++) {
            LayerStats s = chosen.get(i);
            // v2.5.1：id = floorY（同层合并后各层 floorY 唯一）。目录每 40 tick 重建一次，
            // 若 id 用列表下标，层集合增减时全部下标漂移，会被误判为“换层”（触发闪烁提示
            // 与瓦片重烤）；floorY 作为身份在重建间稳定。
            layers.add(new Layer(s.floorY, s.floorY, minX, maxX, minZ, maxZ, s.samples, s.connectors()));
        }
        LayerCatalog catalog = new LayerCatalog(layers, minX, maxX, minZ, maxZ, playerFloor);
        sharedCatalog = catalog;       // v2.5.2：共享目录（小地图对齐层用）
        sharedCatalogWorld = level;
        return catalog;
    }

    /**
     * 与指定世界匹配的最近一次扫描层目录（大地图会话每次重建都会刷新它）；
     * 未扫描过或已换世界时返回 null，调用方应退回各自的原始解析。
     */
    public static LayerCatalog sharedCatalog(ClientLevel level) {
        return level != null && level == sharedCatalogWorld ? sharedCatalog : null;
    }

    /**
     * 把任意 Y 对齐到共享目录中最近的层：小地图用它与地图屏幕保持同层，
     * 瓦片键（含 floorY）在两个视图间一致，互不重烤。目录为空/未匹配时原样返回 floorY。
     */
    public static int alignToSharedCatalog(ClientLevel level, int floorY) {
        LayerCatalog catalog = sharedCatalog(level);
        if (catalog == null) return floorY;
        Layer best = null;
        for (Layer layer : catalog.layers()) {
            if (best == null || Math.abs(layer.floorY() - floorY) < Math.abs(best.floorY() - floorY)) {
                best = layer;
            }
        }
        return best == null ? floorY : best.floorY();
    }

    /**
     * 把任意 Y 归一为按 span 对齐的层代表值（向下对齐到 span 的整数倍）。
     *
     * <p>用于手动楼层与跨层比较：手动层模式下，玩家在同一层跨度（span）内的
     * 小幅 Y 变化仍会自动对齐到同一个层代表值，不会因脚下几格高差而切换层；
     * 跨层比较时也以该代表值为基准判断两个 Y 是否同层。
     *
     * @param floorY 任意世界 Y（玩家脚下方块 Y 或手动选定的楼层 Y）
     * @param span   层高跨度（来自配置）；&le;1 视为 1，即原样返回
     * @return 层代表值：{@code floorY - Math.floorMod(floorY, Math.max(1, span))}
     */
    public static int normalizeFloor(int floorY, int span) {
        return floorY - Math.floorMod(floorY, Math.max(1, span));
    }

    /**
     * 解析玩家当前真正站立的楼层 Y。
     * <p>先在玩家脚下 ±8 格内找直接可行走面；找不到时退化为玩家 Y 的钳制值。
     * 找到后再在周围 6 格半径内对候选 Y 投票
     * （出现次数 ×12 − 与直接面高差 ×3 加权），取加权最优者。
     */
    public static int resolvePlayerFloor(ClientLevel level, Player player) {
        int x = Mth.floor(player.getX()), z = Mth.floor(player.getZ());
        int around = Mth.floor(player.getY() - 0.05) - 1;
        int direct = findWalkableAt(level, x, z, around, 8);
        if (direct == NO_FLOOR) return Mth.clamp(around, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int dx = -6; dx <= 6; dx += 2) for (int dz = -6; dz <= 6; dz += 2) {
            int y = findWalkableAt(level, x + dx, z + dz, direct, 7);
            if (y != NO_FLOOR) counts.merge(y, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Comparator.comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue() * 12
                        - Math.abs(e.getKey() - direct) * 3))
                .map(Map.Entry::getKey).orElse(direct);
    }

    /**
     * 在 (x, z) 列上、aroundY 上下各 radiusY 格内，由近及远寻找第一个可行走面。
     *
     * @return 可行走面的 Y；找不到返回 {@link #NO_FLOOR}
     */
    public static int findWalkableAt(ClientLevel level, int x, int z, int aroundY, int radiusY) {
        if (level == null) return NO_FLOOR;
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        int clamped = Mth.clamp(aroundY, minY, maxY);
        if (!level.hasChunkAt(new BlockPos(x, clamped, z))) return NO_FLOOR;
        for (int d = 0; d <= radiusY; d++) {
            int down = clamped - d;
            if (down >= minY && isWalkableFloor(level, new BlockPos(x, down, z))) return down;
            if (d == 0) continue;
            int up = clamped + d;
            if (up <= maxY && isWalkableFloor(level, new BlockPos(x, up, z))) return up;
        }
        return NO_FLOOR;
    }

    /**
     * 判定某格是否为可行走面：本体有碰撞箱，且上方两格无碰撞箱（可站立、可通过）。
     */
    public static boolean isWalkableFloor(ClientLevel level, BlockPos floor) {
        var floorState = level.getBlockState(floor);
        if (floorState.getCollisionShape(level, floor).isEmpty()) return false;
        return level.getBlockState(floor.above()).getCollisionShape(level, floor.above()).isEmpty()
                && level.getBlockState(floor.above(2)).getCollisionShape(level, floor.above(2)).isEmpty();
    }

    /**
     * 识别层间连接点：楼梯/脚手架/梯子记为 STAIR，铁活板门/铁门记为 ELEVATOR；其余返回 null。
     */
    private static Connector connectorAt(ClientLevel level, BlockPos floor) {
        String name = level.getBlockState(floor).getBlock().builtInRegistryHolder().key().location().getPath();
        if (name.contains("stairs") || name.contains("scaffolding") || name.contains("ladder"))
            return new Connector(floor.getX(), floor.getZ(), Connector.Kind.STAIR);
        if (name.contains("iron_trapdoor") || name.contains("iron_door"))
            return new Connector(floor.getX(), floor.getZ(), Connector.Kind.ELEVATOR);
        return null;
    }

    /** 一层：id = floorY（跨目录重建稳定，供换层判定）；floorY 代表面、扫描范围边界（minX/maxX/minZ/maxZ）、采样数与连接点；contains/label 供 UI 使用。 */
    public record Layer(int id, int floorY, int minX, int maxX, int minZ, int maxZ, int samples,
                        List<Connector> connectors) {
        public boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        public String label() { return "Y " + floorY; }
    }

    /** 层目录：一次 discover 的产物，携带动态扫描范围与玩家层。 */
    public record LayerCatalog(List<Layer> layers, int minX, int maxX, int minZ, int maxZ, int playerFloor) {
        /** 空目录（未扫描或世界未就绪）。 */
        static LayerCatalog empty() { return new LayerCatalog(List.of(), 0, 0, 0, 0, 0); }

        /**
         * 玩家层跟随：按玩家当前位置与最近 floorY 选出活动层。
         * 优先取包含玩家坐标的层中 floorY 最近者；都不包含时全局取最近者（再退回首层）。
         */
        public Layer resolve(ClientLevel level, Player player) {
            if (layers.isEmpty()) {
                int floor = resolvePlayerFloor(level, player);
                return new Layer(floor, floor, minX, maxX, minZ, maxZ, 1, List.of());
            }
            int floor = resolvePlayerFloor(level, player);
            int x = Mth.floor(player.getX()), z = Mth.floor(player.getZ());
            return layers.stream().filter(layer -> layer.contains(x, z))
                    .min(Comparator.comparingInt(layer -> Math.abs(layer.floorY() - floor)))
                    .orElseGet(() -> layers.stream().min(Comparator.comparingInt(layer -> Math.abs(layer.floorY() - floor))).orElse(layers.get(0)));
        }

        /** 按 id（= floorY）查层；不存在时回退首层（无层返回 null）。 */
        public Layer byId(int id) { return layers.stream().filter(l -> l.id() == id).findFirst().orElse(layers.isEmpty() ? null : layers.get(0)); }
    }

    /** 单个候选 floorY 的采样统计：覆盖范围、样本数与连接点；merge 供 ±3 合并使用。 */
    static final class LayerStats {
        final int floorY;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, samples;
        final List<Connector> connectors = new ArrayList<>();
        LayerStats(int floorY) { this.floorY = floorY; }
        void add(int x, int z) { add(x, z, null); }
        void add(int x, int z, Connector connector) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z); samples++;
            if (connector != null && connectors.size() < 128 && connectors.stream().noneMatch(c -> Math.abs(c.x() - x) <= 5 && Math.abs(c.z() - z) <= 5)) connectors.add(connector);
        }
        List<Connector> connectors() { return List.copyOf(connectors); }
        void merge(LayerStats other) { minX = Math.min(minX, other.minX); maxX = Math.max(maxX, other.maxX); minZ = Math.min(minZ, other.minZ); maxZ = Math.max(maxZ, other.maxZ); samples += other.samples; for (Connector c : other.connectors) if (connectors.size() < 128) connectors.add(c); }
    }

    /** 层间连接点（楼梯/电梯），坐标为世界格坐标。 */
    public record Connector(int x, int z, Kind kind) {
        public enum Kind { STAIR, ELEVATOR }
    }
}

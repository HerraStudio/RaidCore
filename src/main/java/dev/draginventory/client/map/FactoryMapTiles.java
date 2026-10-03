package dev.draginventory.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 动态瓦片地图渲染器：彩色底图（方块真实颜色采样）+ 预设滤镜 + VOID 透明。
 *
 * <p><b>瓦片化</b>：世界按 {@value #TILE_BLOCKS}×{@value #TILE_BLOCKS} 格切成瓦片
 * （4×4 区块），每张瓦片对应一张 64×64 的 {@link DynamicTexture}（1 方块 = 1 纹素，
 * 保留像素结构）。只烘焙覆盖视口（外扩一圈）的瓦片，视野拉多远都不再受固定范围限制。</p>
 *
 * <p><b>分帧渐进烘焙</b>：每瓦片每帧推进 {@value #BAKE_ROWS_PER_FRAME} 行（4 帧烤完一张），
 * 活动层每帧最多烘焙 {@value #ACTIVE_BAKE_BUDGET} 张、每个叠加层
 * {@value #OVERLAY_BAKE_BUDGET} 张。<b>每帧采样上限 = 3 瓦片 × 16 行 × 64 格 = 3072 格
 * + 叠加层 1024 格 ≈ 4k</b>（旧方案全图重烤 65536 格/帧）；采样结果（语义 + 方块颜色）由
 * {@link FactoryMapSampler} 的 LRU 缓存兜底（本项目方块不可变），跨帧重复采样 O(1) 查表。</p>
 *
 * <p><b>彩色 + 滤镜预设</b>：像素颜色 = {@link FactoryMapPalette#bake}(语义, 方块真实颜色)；
 * 滤镜为六选一预设（vision.filter_style，默认 none 彩色原色不滤镜），每帧同步到色板
 * （内部 dirty 检查，值不变零开销）。VOID 语义烘焙为<b>全透明</b>（0x00000000）：
 * 被图层开关滤掉的语义也画透明像素，低不透明度时可以直接看到游戏世界（透明度语义交给底幕）。</p>
 *
 * <p><b>LRU 瓦片池</b>：活动层与上下叠加层共用一个 {@value #MAX_TILES} 容量的访问序缓存
 * （256 × 64×64×4B ≈ 4MB 显存），超限淘汰最旧瓦片并释放纹理。楼层切换与目录重建
 * <b>不</b>主动失效瓦片——瓦片键含 floorY，旧层瓦片留在池中自然淘汰，切回旧层立即命中。</p>
 *
 * <p><b>失效时机</b>：
 * <ul>
 *   <li>换世界（ClientLevel 实例变化）：全部瓦片关闭重烤；</li>
 *   <li>图层开关/滤镜预设变化（签名检测）：全部瓦片 nextRow 清零重烤
 *       （语义缓存与颜色无关，sampler 不清）；</li>
 *   <li>烘焙模式变化（某层在上层叠加与活动层之间切换）：受影响瓦片清零重烤。</li>
 * </ul></p>
 *
 * <p>仅客户端渲染线程使用（由地图屏幕 / 预览每帧调用 {@link #draw}；视图状态经
 * {@link FactoryMapView} 最小接口注入，大地图会话与小地图各自实现）。</p>
 *
 * <p><b>纹理 ID 全局唯一（v2.5.2 错误修正）</b>：本类存在多个共存实例（小地图 HUD、
 * 大地图屏幕、设置预览），若各实例的瓦片序号独立从 0 递增，同位置的瓦片会生成
 * <b>同名 ResourceLocation</b>。NeoForge 的 {@code TextureManager.register} 对重复 id
 * 会 {@code safeClose} 旧纹理——直接关闭旧 DynamicTexture 的 NativeImage 并释放
 * GL 纹理：另一实例后续烘焙时 {@code getPixels()} 返回 null 拋 NPE（小地图 HUD
 * 熔断后未会话内永久消失），未抛异常的路径则 blit 到别人的纹理（错误材质/串图）。
 * 因此瓦片序号必须是<b>跨实例的全局静态原子递增</b>，保证任何两个瓦片的 id 永不相同。</p>
 */
final class FactoryMapTiles implements AutoCloseable {
    /** 瓦片边长（方块数）：64×64 格 = 4×4 区块，对应一张 64×64 纹理。 */
    static final int TILE_BLOCKS = 64;
    /** LRU 瓦片池上限：256 × 64×64×4B ≈ 4MB 显存。 */
    static final int MAX_TILES = 256;
    /** 每帧每个被烘焙瓦片推进的行数：64 行 / 16 = 4 帧烤完一张瓦片。 */
    static final int BAKE_ROWS_PER_FRAME = 16;
    /** 活动层每帧最多烘焙的瓦片数（bakeStep 调用次数，含推进中的与新建的）。 */
    static final int ACTIVE_BAKE_BUDGET = 3;
    /** 每个叠加层（上/下）每帧最多烘焙的瓦片数。 */
    static final int OVERLAY_BAKE_BUDGET = 1;

    private final FactoryMapSampler sampler = new FactoryMapSampler();
    private final FactoryMapPalette palette = new FactoryMapPalette();

    /** 访问序 LRU 瓦片池：键含 floorY，各楼层（活动 + 叠加）共用；淘汰时释放纹理。 */
    private final LinkedHashMap<TileKey, Tile> tiles = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TileKey, Tile> eldest) {
            if (size() > MAX_TILES) {
                eldest.getValue().close();
                return true;
            }
            return false;
        }
    };

    /** 图层开关 + 滤镜预设签名；变化时全部瓦片 nextRow 清零重烤。 */
    private String lastLayersSignature = "";
    /** 瓦片纹理 ID 递增序号：跨实例全局唯一（见类注释“纹理 ID 全局唯一”）。
     * v2.5.1 及以前是实例字段，小地图/大地图/设置预览三实例各自从 0 递增 →
     * 同名 id 互相 safeClose（NPE 熔断 / 错误材质），v2.5.2 起改为静态原子序号。 */
    private static final java.util.concurrent.atomic.AtomicInteger TILE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 上次渲染的世界（身份比较）：换世界时全部瓦片失效。 */
    private Object lastWorld;
    /** 可见瓦片列表缓存（按变换相等判断失效）：静止视口零排序开销。 */
    private MapCoordinateTransform lastTransform;
    private List<VisTile> visibleCache = List.of();

    // ==================== 主入口 ====================

    /**
     * 每帧渲染入口，流程：
     * 换世界检测 → 滤镜预设同步 → 图层签名检测 → 可见瓦片枚举 →
     * 活动层底图（alpha 取 vision.opacity）→ 下层地面淡显（0.12）→ 上层墙体轮廓（0.18）。
     * 叠加层画在底图之上（VOID/被滤掉的像素透明，不会遮住底图），上层最后画保证墙体轮廓最清晰。
     */
    void draw(GuiGraphics g, Minecraft mc, MapCoordinateTransform t, FactoryMapView view) {
        if (mc.level == null) return;
        if (lastWorld != mc.level) {
            lastWorld = mc.level;
            closeAll();
        }
        // 每帧同步滤镜预设（palette 内部有 dirty 检查，值不变时零开销）。
        palette.setStyle(FactoryMapPalette.styleFromId(MapConfig.FILTER_STYLE.get()));
        // 图层开关/滤镜预设签名变化 → 全量重烤。sampler 的语义缓存与颜色无关，无需清空。
        String signature = layersSignature();
        if (!signature.equals(lastLayersSignature)) {
            lastLayersSignature = signature;
            for (Tile tile : tiles.values()) tile.nextRow = 0;
        }
        List<VisTile> visible = visibleCache;
        if (visible.isEmpty() || !t.equals(lastTransform)) {
            visible = computeVisible(t);
            visibleCache = visible;
            lastTransform = t;
        }
        FactoryMapLayerResolver.Layer active = view.activeLayer();
        // 活动层为 null：跳过全部瓦片渲染（网格/标点/HUD 由地图屏幕独立绘制，不受影响）。
        if (active == null) return;
        // 楼层目录（引用）变化不失效瓦片：瓦片键含 floorY，旧层瓦片留在 LRU 池自然淘汰。
        renderLayer(g, mc, t, active.floorY(), BakeMode.FULL,
                (float) MapConfig.OPACITY.get().doubleValue(), ACTIVE_BAKE_BUDGET, visible);
        FactoryMapLayerResolver.LayerCatalog catalog = view.catalog();
        FactoryMapLayerResolver.Layer lower = MapConfig.SHOW_LOWER.get()
                ? nearestLayer(catalog, active.floorY(), false) : null;
        if (lower != null) {
            renderLayer(g, mc, t, lower.floorY(), BakeMode.LOWER, 0.12f, OVERLAY_BAKE_BUDGET, visible);
        }
        FactoryMapLayerResolver.Layer upper = MapConfig.SHOW_UPPER.get()
                ? nearestLayer(catalog, active.floorY(), true) : null;
        if (upper != null) {
            renderLayer(g, mc, t, upper.floorY(), BakeMode.UPPER, 0.18f, OVERLAY_BAKE_BUDGET, visible);
        }
    }

    // ==================== 分层渲染 ====================

    /**
     * 渲染一层：预算内分帧烘焙（先推进进行中的瓦片，再开新瓦片，近的优先），
     * 然后 blit 全部已有纹理的可见瓦片——完成的完整显示，未完成的渐进清晰，未开始的不画。
     */
    private void renderLayer(GuiGraphics g, Minecraft mc, MapCoordinateTransform t, int floorY,
                             BakeMode mode, float alpha, int budget, List<VisTile> visible) {
        if (budget <= 0 || visible.isEmpty()) return;
        int spent = 0;
        // 预算第一优先：推进已开始但未完成的瓦片，避免半成品越积越多。
        for (VisTile v : visible) {
            if (spent >= budget) break;
            Tile tile = tiles.get(new TileKey(floorY, v.tileX(), v.tileZ()));
            if (tile != null && tile.mode == mode && tile.nextRow > 0 && tile.nextRow < TILE_BLOCKS) {
                tile.bakeStep(mc, sampler, palette, floorY, BAKE_ROWS_PER_FRAME, mode);
                spent++;
            }
        }
        // 预算第二优先：开启新瓦片（含签名变化或模式切换后待重烤的瓦片）。
        for (VisTile v : visible) {
            if (spent >= budget) break;
            Tile tile = acquire(new TileKey(floorY, v.tileX(), v.tileZ()), mode);
            if (tile.nextRow < TILE_BLOCKS || tile.mode != mode) {
                tile.bakeStep(mc, sampler, palette, floorY, BAKE_ROWS_PER_FRAME, mode);
                spent++;
            }
        }
        // blit 按"远 → 近"倒序访问：瓦片互不重叠，绘制顺序不影响画面；
        // 倒序使远瓦片成为 LRU 最旧条目——池满时优先淘汰远处瓦片，
        // 防止刚烤好的近处瓦片被新插入的远瓦片挤出（视口瓦片数超过池容量时的防抖措施）。
        for (int i = visible.size() - 1; i >= 0; i--) {
            VisTile v = visible.get(i);
            Tile tile = tiles.get(new TileKey(floorY, v.tileX(), v.tileZ()));
            if (tile != null && tile.mode == mode) tile.blit(g, t, alpha);
        }
    }

    /** 取得（不存在则创建）瓦片。烘焙模式的切换在 bakeStep 内处理（切换时清零重烤）。 */
    private Tile acquire(TileKey key, BakeMode mode) {
        Tile tile = tiles.get(key);
        if (tile == null) {
            tile = new Tile(key.floorY(), key.tileX(), key.tileZ(), TILE_SEQ.getAndIncrement(), mode);
            tiles.put(key, tile);  // 池满时淘汰并关闭最旧瓦片
        }
        return tile;
    }

    /** 目录中与 floorY 最近的上方（above=true）/ 下方层；无候选返回 null。 */
    private static FactoryMapLayerResolver.Layer nearestLayer(
            FactoryMapLayerResolver.LayerCatalog catalog, int floorY, boolean above) {
        if (catalog == null) return null;
        FactoryMapLayerResolver.Layer best = null;
        for (FactoryMapLayerResolver.Layer layer : catalog.layers()) {
            int dy = layer.floorY() - floorY;
            if (above ? dy > 0 : dy < 0) {
                if (best == null || Math.abs(dy) < Math.abs(best.floorY() - floorY)) best = layer;
            }
        }
        return best;
    }

    /**
     * 枚举覆盖视口的瓦片索引，按"瓦片中心到视口中心的屏幕距离"升序排序（近的优先；
     * 平局按 tileX/tileZ 决胜，保证帧间确定性）。范围 = 视口世界包围盒外扩一圈瓦片。
     */
    private static List<VisTile> computeVisible(MapCoordinateTransform t) {
        // 地图屏幕以 (0,0) 为视口原点绘制；两端角点经 screenToWorld 反解出世界包围盒。
        MapCoordinateTransform.Point min = t.screenToWorld(0, 0);
        MapCoordinateTransform.Point max = t.screenToWorld(t.viewportWidth(), t.viewportHeight());
        int tx0 = Math.floorDiv(Mth.floor(min.x()) - TILE_BLOCKS, TILE_BLOCKS);
        int tx1 = Math.floorDiv(Mth.floor(max.x()) + TILE_BLOCKS, TILE_BLOCKS);
        int tz0 = Math.floorDiv(Mth.floor(min.z()) - TILE_BLOCKS, TILE_BLOCKS);
        int tz1 = Math.floorDiv(Mth.floor(max.z()) + TILE_BLOCKS, TILE_BLOCKS);
        double cx = t.viewportX() + t.viewportWidth() * 0.5;
        double cy = t.viewportY() + t.viewportHeight() * 0.5;
        List<VisTile> list = new ArrayList<>((tx1 - tx0 + 1) * (tz1 - tz0 + 1));
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                double dx = t.worldToScreenX((tx + 0.5) * TILE_BLOCKS) - cx;
                double dz = t.worldToScreenY((tz + 0.5) * TILE_BLOCKS) - cy;
                list.add(new VisTile(tx, tz, dx * dx + dz * dz));
            }
        }
        list.sort(Comparator.comparingDouble(VisTile::dist)
                .thenComparingInt(VisTile::tileX)
                .thenComparingInt(VisTile::tileZ));
        return list;
    }

    /**
     * 图层开关 + 滤镜预设（filter_style）签名。OPACITY 与 SHOW_UPPER/SHOW_LOWER 不参与：
     * 前者只影响 blit alpha，后者只决定是否绘制叠加层，都不改变烘焙像素。
     */
    private static String layersSignature() {
        return MapConfig.SHOW_WALLS.get() + "," + MapConfig.SHOW_FLOOR.get() + "," + MapConfig.SHOW_WATER.get()
                + "," + MapConfig.SHOW_STAIRS.get() + "," + MapConfig.SHOW_DOORS.get() + "," + MapConfig.SHOW_DECOR.get()
                + "," + MapConfig.FILTER_STYLE.get();
    }

    /** 全部瓦片关闭并清池（换世界 / 渲染器关闭时调用）。 */
    private void closeAll() {
        tiles.values().forEach(Tile::close);
        tiles.clear();
    }

    @Override
    public void close() {
        closeAll();
    }

    /** ARGB 转 ABGR（{@link NativeImage} 像素为 ABGR 排列，R 在最低字节）。 */
    private static int rgba(int argb) {
        return argb & 0xFF00FF00 | (argb >> 16 & 255) | (argb & 255) << 16;
    }

    /**
     * 语义位掩码（bit 序 = {@link MapSemantics#ordinal()}）：烘焙模式限定的语义集合
     * 与图层开关共同决定；被滤掉的语义在烘焙时画透明像素。
     * VOID 恒可绘制（其烘焙色即全透明）；BLOCKED（实心不可通行）无开关，仅在活动层全量模式下绘制。
     */
    private static int semanticMask(BakeMode mode) {
        int mask = 1 << MapSemantics.VOID.ordinal();
        switch (mode) {
            case FULL -> {
                mask |= 1 << MapSemantics.BLOCKED.ordinal();
                if (MapConfig.SHOW_WALLS.get()) mask |= 1 << MapSemantics.WALL.ordinal();
                if (MapConfig.SHOW_FLOOR.get()) mask |= 1 << MapSemantics.FLOOR.ordinal() | 1 << MapSemantics.EDGE.ordinal();
                if (MapConfig.SHOW_WATER.get()) mask |= 1 << MapSemantics.WATER.ordinal();
                if (MapConfig.SHOW_STAIRS.get()) mask |= 1 << MapSemantics.STAIR.ordinal();
                if (MapConfig.SHOW_DOORS.get()) mask |= 1 << MapSemantics.DOOR.ordinal();
                if (MapConfig.SHOW_DECOR.get()) mask |= 1 << MapSemantics.DECOR.ordinal();
            }
            case UPPER -> {  // 上层叠加：只保留墙体轮廓（墙/门/楼梯出入点），其余画 VOID
                if (MapConfig.SHOW_WALLS.get()) mask |= 1 << MapSemantics.WALL.ordinal();
                if (MapConfig.SHOW_DOORS.get()) mask |= 1 << MapSemantics.DOOR.ordinal();
                if (MapConfig.SHOW_STAIRS.get()) mask |= 1 << MapSemantics.STAIR.ordinal();
            }
            case LOWER -> {  // 下层叠加：只保留地面淡显（地面/边缘），其余画 VOID
                if (MapConfig.SHOW_FLOOR.get()) mask |= 1 << MapSemantics.FLOOR.ordinal() | 1 << MapSemantics.EDGE.ordinal();
            }
        }
        return mask;
    }

    // ==================== 数据类型 ====================

    /** 瓦片键：楼层 Y + 瓦片索引（tileX = floorDiv(worldX, TILE_BLOCKS)，负坐标正确向下取整）。 */
    record TileKey(int floorY, int tileX, int tileZ) {}

    /** 可见瓦片（tileX/tileZ 索引 + 用于近远排序的屏幕距离平方）。 */
    private record VisTile(int tileX, int tileZ, double dist) {}

    /** 烘焙模式：FULL=活动层全语义；UPPER=上层叠加只画墙/门/楼梯；LOWER=下层叠加只画地面/边缘。 */
    private enum BakeMode { FULL, UPPER, LOWER }

    /**
     * 单张瓦片：64×64 {@link DynamicTexture} + 渐进烘焙进度 nextRow（0..64，64 = 完成）。
     * 烘焙在渲染线程分帧推进，未烤区域保持全透明，画面随烘焙渐进清晰。
     */
    private static final class Tile implements AutoCloseable {
        final ResourceLocation id;
        final int tileX, tileZ;
        BakeMode mode;
        DynamicTexture texture;
        int nextRow;
        Object world;
        boolean lastLinear;

        Tile(int floorY, int tileX, int tileZ, int seq, BakeMode mode) {
            this.id = ResourceLocation.fromNamespaceAndPath("draginventory",
                    "factory_map/tile_" + floorY + "_" + tileX + "_" + tileZ + "_" + seq);
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.mode = mode;
        }

        /**
         * 烤 rows 行（从 nextRow 继续）：逐格 sample（语义 + 方块真实颜色）
         * → 模式/图层开关过滤（滤掉画透明）→ 滤镜烘焙 → ABGR 写入纹理，烤完 upload。
         * 烘焙模式变化时清零重烤。
         */
        void bakeStep(Minecraft mc, FactoryMapSampler sampler, FactoryMapPalette palette,
                      int layerY, int rows, BakeMode mode) {
            if (mode != this.mode) {
                this.mode = mode;  // 该层从叠加层变为活动层（或反之）：内容口径变化，重烤
                nextRow = 0;
            }
            if (nextRow >= TILE_BLOCKS) return;
            // 防御（v2.5.2）：纹理若被外部关闭（TextureManager 资源重载/safeClose 等），
            // pixels 会变为 null —— 丢弃残壳走下面的重建路径，不再向已关闭的 NativeImage 写入。
            if (texture != null && texture.getPixels() == null) {
                mc.getTextureManager().release(id);
                texture = null;
            }
            if (texture == null || world != mc.level) {
                // 首次烘焙，或换世界防御路径（主类通常已在换世界时整体关闭重建）
                if (texture != null) mc.getTextureManager().release(id);
                world = mc.level;
                nextRow = 0;
                texture = new DynamicTexture(new NativeImage(TILE_BLOCKS, TILE_BLOCKS, false));
                mc.getTextureManager().register(id, texture);
                texture.setFilter(false, false);  // NEAREST 像素风；blit 时按缩放按需切换
                lastLinear = false;
                // malloc 的显存未清零，先整体填全透明（VOID/被滤掉语义均透明），未烤区域不至于显示花屏
                texture.getPixels().fillRect(0, 0, TILE_BLOCKS, TILE_BLOCKS, 0);
            }
            int mask = semanticMask(mode);
            NativeImage image = texture.getPixels();
            int end = Math.min(TILE_BLOCKS, nextRow + rows);
            for (int row = nextRow; row < end; row++) {
                int wz = tileZ * TILE_BLOCKS + row;
                for (int x = 0; x < TILE_BLOCKS; x++) {
                    FactoryMapSampler.Sample s = sampler.sample(mc.level, tileX * TILE_BLOCKS + x, wz, layerY);
                    image.setPixelRGBA(x, row,
                            (mask >>> s.semantic().ordinal() & 1) != 0 ? rgba(palette.bake(s.semantic(), s.color())) : 0);
                }
            }
            nextRow = end;
            texture.upload();
        }

        /**
         * 浮点 pose 定位 blit（与网格/标点共享同一连续坐标空间，避免取整产生 1px 漂移）；
         * alpha 经 RenderSystem 着色色实现，blit 后恢复；缩放切换 NEAREST/LINEAR 过滤。
         */
        void blit(GuiGraphics g, MapCoordinateTransform t, float alpha) {
            if (texture == null) return;
            float x = (float) t.worldToScreenX(tileX * (double) TILE_BLOCKS);
            float y = (float) t.worldToScreenY(tileZ * (double) TILE_BLOCKS);
            float size = (float) (TILE_BLOCKS * t.zoom());
            boolean linear = t.zoom() < 1.0;  // 整数倍缩放 NEAREST 保持像素边缘；低于 1px/格退回线性防摩纹
            if (linear != lastLinear) {
                lastLinear = linear;
                texture.setFilter(linear, false);
                texture.upload();  // 重新绑定使新过滤参数立即生效
            }
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            if (alpha < 1.0f) RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
            g.blit(id, 0, 0, Math.round(size), Math.round(size), 0, 0, TILE_BLOCKS, TILE_BLOCKS, TILE_BLOCKS, TILE_BLOCKS);
            if (alpha < 1.0f) RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.pose().popPose();
        }

        @Override
        public void close() {
            if (texture != null) Minecraft.getInstance().getTextureManager().release(id);
            texture = null;
        }
    }
}

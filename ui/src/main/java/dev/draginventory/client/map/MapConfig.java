package dev.draginventory.client.map;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 战术地图的客户端配置（NeoForge 配置系统，TOML 文件 draginventory-map-client.toml）。
 *
 * <p>设计约定：
 * <ul>
 *   <li>所有数值均带范围校验，改错值会被自动纠正回边界。</li>
 *   <li>图层开关（layers 组）是渲染管线的依赖：语义图层显隐直接决定瓦片烘焙内容。</li>
 *   <li>配置在客户端生效，文件被外部编辑时由 NeoForge 自动热重载。</li>
 * </ul></p>
 *
 * <p><b>保存竞态与写前日志（journal）</b>：NeoForge 在 {@code SPEC.save()} 写盘后，
 * FML 的文件监视线程（{@code ConfigWatcher}）会异步重读文件并<b>整体替换</b>内存配置映射。
 * 若用户在“落盘完成 → 监视线程重载完成”的窗口内又修改了配置，新值会被旧文件内容覆盖
 * （表现为：开关“点了没反应”、滤镜滑条“跳过去又跳回来”）。
 * 对策：每次 {@link #set} 都记入 journal；收到配置重载事件（无论来自 save 的同步事件
 * 还是监视线程的异步重载）时对账——journal 里尚未持久化的值重新写回内存，
 * 并再次调度落盘。两轮后收敛：第二次保存的文件已包含这些值，重载后对账全等，日志清空。</p>
 */
public final class MapConfig {
    public static final ModConfigSpec SPEC;

    /** 用于“恢复默认”与遍历校验的全部值注册表。 */
    private static final List<ModConfigSpec.ConfigValue<?>> ALL = new ArrayList<>();

    /** 防抖落盘状态：仅在设置真正变更时置脏，静默 500ms 后由 client tick 落盘。 */
    private static volatile boolean dirty;
    private static volatile long lastChangeMillis;
    private static final long SAVE_QUIET_PERIOD_MS = 500L;

    /**
     * 写前日志：最近一次 set 但可能尚未持久化到文件的值。
     * 键为 ConfigValue 引用（身份相等）；值非 null（配置值永不为 null）。
     * 读写可能来自主线程与 FML 监视线程（重载对账），故用并发 Map。
     */
    private static final ConcurrentHashMap<ModConfigSpec.ConfigValue<?>, Object> JOURNAL =
            new ConcurrentHashMap<>();

    // ==================== 常规 ====================
    /** 地图总开关：控制 M 键与大地图、小地图显示（v2.5.0 起生效）。 */
    public static final ModConfigSpec.BooleanValue ENABLED;
    /**
     * 层高跨度——自动楼层切换死区（±span/2 格内不切换防抖）。
     * v2.5.2 起地图只保留自动楼层（反馈⑤：移除玩家手动调整显示层），
     * 该值仅服务于自动跟随的防抖灵敏度。
     */
    public static final ModConfigSpec.IntValue LAYER_SPAN;
    /**
     * 打开大地图时默认进入清屏模式（隐藏地图界面内的按钮/工具栏/缩放等 HUD，
     * 只留地图、背景与玩家位置；标点显隐仍由 markers.visible 决定）。
     * v2.5.0 曾硬编码为开，v2.5.1 起改为配置项且默认关闭（反馈“打开地图看不到任何 UI”）。
     * 地图内 H 键随时切换。
     */
    public static final ModConfigSpec.BooleanValue CLEAN_ON_OPEN;
    /**
     * 配置迁移标记（v2.5.1，内部键）：首次加载时把旧版残留的 enabled=false 复位后置位，
     * 保证迁移只执行一次；此后玩家主动执行的 /map off 被尊重。
     */
    public static final ModConfigSpec.BooleanValue MIGRATED;
    /**
     * 手动计时默认时长（秒）：/map timer default <时长> 写此值，/map timer reset 使用。
     * 默认30分钟；Raid 对局计时由服务端提供，不受此客户端配置影响。
     */
    public static final ModConfigSpec.IntValue MATCH_TIMER_DURATION;

    // ==================== 图层开关 ====================
    public static final ModConfigSpec.BooleanValue SHOW_WALLS;
    public static final ModConfigSpec.BooleanValue SHOW_FLOOR;
    public static final ModConfigSpec.BooleanValue SHOW_WATER;
    public static final ModConfigSpec.BooleanValue SHOW_STAIRS;
    public static final ModConfigSpec.BooleanValue SHOW_DOORS;
    /** 地面装饰（花草等非碰撞植物），信息量低，默认关闭。 */
    public static final ModConfigSpec.BooleanValue SHOW_DECOR;
    /** 上层墙体轮廓叠加。 */
    public static final ModConfigSpec.BooleanValue SHOW_UPPER;
    /** 下层地面淡显。 */
    public static final ModConfigSpec.BooleanValue SHOW_LOWER;

    // ==================== 视觉与滤镜 ====================
    /** 地图整体不透明度。 */
    public static final ModConfigSpec.DoubleValue OPACITY;
    /**
     * 滤镜预设样式。合法值：none / delta / tarkov / darkzone / apex / pubg
     * （none = 彩色原色不滤镜，默认；详见 FactoryMapPalette.Style）。
     */
    public static final ModConfigSpec.ConfigValue<String> FILTER_STYLE;
    /** 网格线。 */
    public static final ModConfigSpec.BooleanValue GRID;

    // ==================== 小地图 ====================
    /** 小地图总开关（左上角常驻方形 HUD）。 */
    public static final ModConfigSpec.BooleanValue MINIMAP_ENABLED;
    /** 小地图缩放（每格方块对应的屏幕像素数）。 */
    public static final ModConfigSpec.DoubleValue MINIMAP_ZOOM;
    /**
     * 小地图边长（屏幕像素，正方形）。v2.5.3 默认 128→96（反馈：默认过大）。
     * 旧配置里已落盘的 128 由 MapConfigEvents 的一次性重调收敛到 96
     * （{@link #MINIMAP_SIZE_RETUNED} 标记，见迁移注释）。
     */
    public static final ModConfigSpec.IntValue MINIMAP_SIZE;
    /**
     * 小地图尺寸重调标记（v2.5.3 内部键，默认 false）：首次加载时若 size 仍为旧默认
     * 128 则收敛到新默认 96 并置位落盘；此后玩家自改尺寸（含改回 128）被永久尊重。
     */
    public static final ModConfigSpec.BooleanValue MINIMAP_SIZE_RETUNED;

    // ==================== 行为 ====================
    /** 楼层扫描半径（格）：动态楼层发现与采样的范围。 */
    public static final ModConfigSpec.IntValue SCAN_RADIUS;
    /**
     * 最小缩放（v2.5.5 默认 1.62 = 108%；屏幕百分比 = zoom/1.5*100）。
     * 旧默认 0.25 的存量配置由 MapConfigEvents 一次性收敛（{@link #ZOOM_RETUNED}）。
     */
    public static final ModConfigSpec.DoubleValue ZOOM_MIN;
    /**
     * 最大缩放（v2.5.5 默认 9.0 = 600%）。旧默认 8.0（533%）的存量配置一次性收敛到 9.0；
     * 玩家自定的其他值不动。
     */
    public static final ModConfigSpec.DoubleValue ZOOM_MAX;
    /**
     * 缩放范围重调标记（v2.5.5 内部键，默认 false）：首次加载时把仍为旧默认的
     * zoom_min / zoom_max 收敛到新默认（1.62 / 9.0）并置位落盘；此后玩家自改被永久尊重。
     */
    public static final ModConfigSpec.BooleanValue ZOOM_RETUNED;
    /** 打开地图时居中玩家。 */
    public static final ModConfigSpec.BooleanValue FOLLOW_PLAYER;

    // ==================== 标点 ====================
    /** 标点风格：tactical（战术 glyph 动画）/ minimal（小圆点）。 */
    public static final ModConfigSpec.ConfigValue<String> MARKER_STYLE;
    /** 地图上是否显示标点。 */
    public static final ModConfigSpec.BooleanValue MARKERS_VISIBLE;
    /** 地图右键放置的标点类型：location / enemy / item。 */
    public static final ModConfigSpec.ConfigValue<String> MARKER_PING_TYPE;
    /** 标点距离标注。 */
    public static final ModConfigSpec.BooleanValue SHOW_DISTANCES;
    /** 标点倒计时显示。 */
    public static final ModConfigSpec.BooleanValue SHOW_COUNTDOWN;
    /**
     * 玩家位置图标大小（v2.6.0）：相对默认尺寸的倍率，1.0 = 默认（形状高 ≈12.5px，
     * 即 v2.5.7 以来的视觉尺寸），下限 0.4（≈5px，小地图上仍清晰可辨）。
     * 作用于小地图 / 大地图 / 设置预览全部视图（含尾迹残影，等比缩放）。
     */
    public static final ModConfigSpec.DoubleValue PLAYER_MARKER_SIZE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("战术地图 (Tactical Map) — 客户端配置", "修改后立即生效；外部编辑文件由 NeoForge 自动热重载。").push("general");
        ENABLED = reg(b.define("enabled", true));
        LAYER_SPAN = reg(b.defineInRange("layer_span", 4, 1, 16));
        CLEAN_ON_OPEN = reg(b.define("clean_on_open", false));
        MIGRATED = reg(b.define("migrated", false));
        MATCH_TIMER_DURATION = reg(b.defineInRange("match_timer_seconds", (int) MapMatchTimer.DEFAULT_DURATION_SECONDS, 1, 86400));
        b.pop();

        b.comment("图层开关：控制各语义图层是否参与绘制。",
                "show_upper / show_lower = 上下层叠加（上层墙体轮廓 / 下层地面淡显）；",
                "show_decor = 地面装饰（花草等非碰撞植物），默认关闭。").push("layers");
        SHOW_WALLS = reg(b.define("show_walls", true));
        SHOW_FLOOR = reg(b.define("show_floor", true));
        SHOW_WATER = reg(b.define("show_water", true));
        SHOW_STAIRS = reg(b.define("show_stairs", true));
        SHOW_DOORS = reg(b.define("show_doors", true));
        SHOW_DECOR = reg(b.define("show_decor", false));
        SHOW_UPPER = reg(b.define("show_upper", true));
        SHOW_LOWER = reg(b.define("show_lower", true));
        b.pop();

        b.comment("视觉与滤镜：opacity = 地图整体不透明度；",
                "filter_style = 滤镜预设（none 彩色原色不滤镜 / delta 三角洲 / tarkov 塔科夫 / darkzone 暗区 / apex / pubg，默认 none）；",
                "grid = 网格线。").push("vision");
        OPACITY = reg(b.defineInRange("opacity", 0.95, 0.3, 1.0));
        FILTER_STYLE = reg(b.define("filter_style", "none"));
        GRID = reg(b.define("grid", true));
        b.pop();

        b.comment("小地图（左上角常驻方形，Xaero 风格）。",
                "zoom = 缩放（每格方块对应的像素数）；size = 边长（像素，v2.5.3 起默认 96）；",
                "size_retuned = 内部标记（v2.5.3 尺寸默认值重调的一次性迁移），无需手动修改。").push("minimap");
        MINIMAP_ENABLED = reg(b.define("enabled", true));
        MINIMAP_ZOOM = reg(b.defineInRange("zoom", 2.0, 0.5, 4.0));
        MINIMAP_SIZE = reg(b.defineInRange("size", 96, 64, 256));
        MINIMAP_SIZE_RETUNED = reg(b.define("size_retuned", false));
        b.pop();

        b.comment("行为：scan_radius = 楼层扫描半径（格，决定动态楼层发现与采样范围）；",
                "zoom_min / zoom_max = 缩放范围（默认 1.62~9.0，即屏幕百分比 108%~600%）；",
                "zoom_retuned = 内部标记（v2.5.5 缩放默认值重调的一次性迁移），无需手动修改；",
                "follow_player = 打开地图时居中玩家。").push("behavior");
        SCAN_RADIUS = reg(b.defineInRange("scan_radius", 256, 64, 512));
        ZOOM_MIN = reg(b.defineInRange("zoom_min", 1.62, 1.08, 2.0));
        ZOOM_MAX = reg(b.defineInRange("zoom_max", 9.0, 2.0, 16.0));
        ZOOM_RETUNED = reg(b.define("zoom_retuned", false));
        FOLLOW_PLAYER = reg(b.define("follow_player", true));
        b.pop();

        b.comment("标点：marker_style = tactical / minimal（tactical = 战术 glyph 动画，minimal = 小圆点）；",
                "visible = 地图上是否显示标点；ping_type = 地图右键放置的标点类型（location / enemy / item）；",
                "show_distances = 标点距离标注；show_countdown = 倒计时显示；",
                "player_marker_size = 玩家位置图标大小（0.4~1.0 倍率，1.0 = 默认尺寸，v2.6.0）。").push("markers");
        MARKER_STYLE = reg(b.define("marker_style", "tactical"));
        MARKERS_VISIBLE = reg(b.define("visible", true));
        MARKER_PING_TYPE = reg(b.define("ping_type", "location"));
        SHOW_DISTANCES = reg(b.define("show_distances", true));
        SHOW_COUNTDOWN = reg(b.define("show_countdown", true));
        PLAYER_MARKER_SIZE = reg(b.defineInRange("player_marker_size", 1.0, 0.4, 1.0));
        b.pop();

        SPEC = b.build();
    }

    private MapConfig() {}

    private static <T extends ModConfigSpec.ConfigValue<?>> T reg(T value) {
        ALL.add(value);
        return value;
    }

    /**
     * 运行时修改配置（指令 / 设置界面使用）。
     *
     * <p>NeoForge 的 {@code ConfigValue.set} 只改内存不落盘；这里标记脏位并记入 journal，
     * 由 client tick 在连续修改（拖动滑条）静默 500ms 后统一写盘。
     * journal 用于对抗 FML 文件监视线程的异步重载覆盖（见类注释）。</p>
     */
    public static <T> void set(ModConfigSpec.ConfigValue<T> value, T newValue) {
        value.set(newValue);
        JOURNAL.put(value, newValue);
        dirty = true;
        lastChangeMillis = System.currentTimeMillis();
    }

    /** 由 client tick 调用：连续修改静默后统一落盘。 */
    static void tickSave() {
        if (dirty && System.currentTimeMillis() - lastChangeMillis >= SAVE_QUIET_PERIOD_MS) {
            flush();
        }
    }

    /** 立即落盘（退出世界 / 关闭设置界面等时机调用的兜底）。 */
    public static void flush() {
        if (dirty) {
            dirty = false;
            // save() 会先写文件再同步发出 Reloading 事件；
            // 事件里的对账（onConfigReloaded）会把已持久化的 journal 条目清掉。
            SPEC.save();
        }
    }

    /**
     * 配置重载对账（Loading / Reloading 事件均调用；可能在 FML 监视线程上执行）。
     *
     * <p>重载会用文件内容整体替换内存映射。对 journal 中每个尚未持久化的值：
     * 已与内存一致（说明文件已包含它）→ 清除日志；不一致（被旧文件覆盖了）→ 重新写回
     * 并再次调度落盘。第二次保存后文件即包含这些值，重载对账全等，自然收敛。</p>
     */
    static void onConfigReloaded() {
        if (JOURNAL.isEmpty()) return;
        boolean reapplied = false;
        for (Iterator<Map.Entry<ModConfigSpec.ConfigValue<?>, Object>> it = JOURNAL.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<ModConfigSpec.ConfigValue<?>, Object> entry = it.next();
            ModConfigSpec.ConfigValue<?> value = entry.getKey();
            Object wanted = entry.getValue();
            try {
                if (Objects.equals(value.getRaw(), wanted)) {
                    it.remove(); // 文件已包含该值，日志条目完成使命
                } else {
                    // 被旧文件内容覆盖了：重放（同时更新内存映射与 ConfigValue 缓存）。
                    setQuietly(value, wanted);
                    reapplied = true;
                }
            } catch (IllegalStateException | NullPointerException ignored) {
                // 配置尚未加载或已卸载：保留日志条目，等下次加载事件再对账。
            }
        }
        if (reapplied) {
            dirty = true;
            lastChangeMillis = System.currentTimeMillis();
        }
    }

    /** 配置卸载（退出到主菜单）：内存映射被丢弃，日志与脏位一并复位。 */
    static void onConfigUnloaded() {
        JOURNAL.clear();
        dirty = false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setQuietly(ModConfigSpec.ConfigValue value, Object wanted) {
        value.set(wanted);
    }

    /** 全部恢复默认值并写盘（显式操作，立即落盘）。 */
    public static void resetToDefaults() {
        for (ModConfigSpec.ConfigValue<?> value : ALL) {
            resetOne(value);
            JOURNAL.put(value, value.getDefault());
        }
        dirty = false;
        SPEC.save();
        // save() 同步触发 Reloading → onConfigReloaded 对账清日志。
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void resetOne(ModConfigSpec.ConfigValue<?> value) {
        ((ModConfigSpec.ConfigValue) value).set(value.getDefault());
    }

    /** 供主类注册使用。 */
    public static ModConfig.Type type() {
        return ModConfig.Type.CLIENT;
    }

    public static String fileName() {
        return "draginventory-map-client.toml";
    }
}

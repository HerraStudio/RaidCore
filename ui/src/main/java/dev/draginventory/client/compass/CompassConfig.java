package dev.draginventory.client.compass;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 方位条 HUD 的客户端配置（NeoForge 配置系统，TOML 文件 draginventory-compass-client.toml）。
 *
 * <p>设计约定：
 * <ul>
 *   <li>所有数值均带范围校验，改错值会被自动纠正回边界。</li>
 *   <li>颜色覆盖项使用 -1 表示“跟随当前配色方案”，正值为 0xRRGGBB 覆盖。</li>
 *   <li>配置在客户端生效，文件被外部编辑时由 NeoForge 自动热重载。</li>
 * </ul></p>
 *
 * <p><b>保存竞态与写前日志（journal）</b>：NeoForge 在 {@code SPEC.save()} 写盘后，
 * FML 的文件监视线程（{@code ConfigWatcher}）会异步重读文件并<b>整体替换</b>内存配置映射。
 * 若用户在“落盘完成 → 监视线程重载完成”的窗口内又修改了配置，新值会被旧文件内容覆盖
 * （表现为：开关“点了没反应”、配色切换“跳过去又跳回来”）。
 * 对策：每次 {@link #set} 都记入 journal；收到配置重载事件（无论来自 save 的同步事件
 * 还是监视线程的异步重载）时对账——journal 里尚未持久化的值重新写回内存，
 * 并再次调度落盘。两轮后收敛：第二次保存的文件已包含这些值，重载后对账全等，日志清空。</p>
 */
public final class CompassConfig {
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
    public static final ModConfigSpec.BooleanValue ENABLED;
    /** 打开 F3 调试屏时自动隐藏（避免遮挡调试信息）。 */
    public static final ModConfigSpec.BooleanValue HIDE_WITH_DEBUG;
    /** v2.6.1 默认预设一次性收敛标记（见 CompassConfigEvents#retuneDefaults），无需手动修改。 */
    public static final ModConfigSpec.BooleanValue DEFAULTS_RETUNED;

    // ==================== 位置与大小 ====================
    public static final ModConfigSpec.IntValue OFFSET_X;
    /** 垂直偏移。v2.6.1 起默认 0（顶部对齐，与快捷对齐“贴顶”同值）；旧默认 6 的存量配置由
     * CompassConfigEvents#retuneDefaults 一次性收敛。 */
    public static final ModConfigSpec.IntValue OFFSET_Y;
    /** 条带宽度（界面像素，不含缩放）。上限 960：超宽屏占比预设（2/3 屏）也能容纳。
     * v2.6.1 起默认 427（640 GUI 宽下给屏宽占比 2/3 预设的换算值，即用户实测的
     * “2/3 屏”默认预设）；旧默认 240 的存量配置由 CompassConfigEvents#retuneDefaults
     * 一次性收敛。 */
    public static final ModConfigSpec.IntValue BAR_WIDTH;
    /** 整体缩放（默认 1.0，新旧预设相同）。 */
    public static final ModConfigSpec.DoubleValue SCALE;

    // ==================== 风格与配色 ====================
    /** 皮肤 id：delta（三角洲行动）/ pubg / apex（v2.6.1 起默认）/ battlefield / warzone。
     * 旧默认 delta 的存量配置由 CompassConfigEvents#retuneDefaults 一次性收敛。 */
    public static final ModConfigSpec.ConfigValue<String> STYLE;
    /** 配色 id：aurora / frost / amber / crimson / violet / slate。 */
    public static final ModConfigSpec.ConfigValue<String> PALETTE;
    /** 设置界面主题：amber（琥珀）/ tech（青蓝）/ crimson（猩红）。 */
    public static final ModConfigSpec.ConfigValue<String> MENU_THEME;
    /** 整体不透明度 0.15 ~ 1。 */
    public static final ModConfigSpec.DoubleValue OPACITY;
    /** 角度数字使用 ° 符号。 */
    public static final ModConfigSpec.BooleanValue DEGREE_SYMBOL;

    // ==================== 颜色覆盖（-1 = 跟随配色） ====================
    public static final ModConfigSpec.IntValue COLOR_ACCENT;
    public static final ModConfigSpec.IntValue COLOR_TEXT;
    public static final ModConfigSpec.IntValue COLOR_DIM;
    public static final ModConfigSpec.IntValue COLOR_TICK;
    public static final ModConfigSpec.IntValue COLOR_BACKGROUND;

    // ==================== 动画 ====================
    /** 平滑响应速度（弹簧刚度 omega，越大越跟手）。 */
    public static final ModConfigSpec.DoubleValue SMOOTHNESS;
    /** 基数方位吸附辅助（接近东南西北时标签放大提亮）。 */
    public static final ModConfigSpec.BooleanValue SNAP_ASSIST;
    public static final ModConfigSpec.IntValue SNAP_RANGE;
    /** 转向时刻度的惯性倾斜。 */
    public static final ModConfigSpec.BooleanValue INERTIA_TILT;
    public static final ModConfigSpec.DoubleValue TILT_INTENSITY;
    /** HUD 出现时的入场动画。 */
    public static final ModConfigSpec.BooleanValue ENTRY_ANIMATION;

    // ==================== 内容 ====================
    /** 可见视野总角度（度）。 */
    public static final ModConfigSpec.IntValue RANGE;
    /** 次级刻度间隔（度）。 */
    public static final ModConfigSpec.IntValue MINOR_STEP;
    /** 数字标注间隔（度）。 */
    public static final ModConfigSpec.IntValue NUMBER_STEP;
    public static final ModConfigSpec.BooleanValue SHOW_CARDINALS;
    /** 显示 45 度倍数的次方位（东北/东南/西南/西北）。 */
    public static final ModConfigSpec.BooleanValue SHOW_INTERCARDINALS;
    public static final ModConfigSpec.BooleanValue SHOW_NUMBERS;
    /** 东南西北使用中文（关闭则用 N/E/S/W）。 */
    public static final ModConfigSpec.BooleanValue CJK_LABELS;
    /** 基数方位文字相对缩放。 */
    public static final ModConfigSpec.DoubleValue CARDINAL_SCALE;

    // ==================== 标点联动 ====================
    public static final ModConfigSpec.BooleanValue MARKERS_ENABLED;
    /** 联动本模组战术标点（中键标记）。 */
    public static final ModConfigSpec.BooleanValue MARKERS_TACTICAL;
    /** 显示标点距离（米）。 */
    public static final ModConfigSpec.BooleanValue MARKERS_DISTANCE;
    /** 标点脉冲动画。 */
    public static final ModConfigSpec.BooleanValue MARKERS_PULSE;
    /** 指令创建的测试标点。 */
    public static final ModConfigSpec.BooleanValue MARKERS_TEST;
    /** 死亡时自动在死亡位置标记 X（靠近后自动消失）。 */
    public static final ModConfigSpec.BooleanValue MARKERS_DEATH;
    /** 显示标点文字标签（与距离合为一行）。 */
    public static final ModConfigSpec.BooleanValue MARKERS_LABELS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("方位条 HUD (HERRA Compass) — 客户端配置", "修改后立即生效；外部编辑文件由 NeoForge 自动热重载。").push("general");
        ENABLED = reg(b.define("enabled", true));
        HIDE_WITH_DEBUG = reg(b.define("hide_with_debug_screen", true));
        DEFAULTS_RETUNED = reg(b.define("defaults_retuned", false));
        b.pop();

        b.comment("位置与大小：锚点为屏幕顶部中央，偏移量为界面像素。",
                "v2.6.1 默认预设：顶部对齐（offset_y=0）+ 水平居中（offset_x=0）",
                "+ 2/3 屏条宽（width=427，640 GUI 宽的换算值）+ 1.0 缩放。").push("position");
        OFFSET_X = reg(b.defineInRange("offset_x", 0, -640, 640));
        OFFSET_Y = reg(b.defineInRange("offset_y", 0, -640, 640));
        BAR_WIDTH = reg(b.defineInRange("width", 427, 120, 960));
        SCALE = reg(b.defineInRange("scale", 1.0, 0.5, 2.0));
        b.pop();

        b.comment("风格与配色：style = delta（三角洲行动）/ pubg / apex / battlefield / warzone；",
                "palette = aurora / frost / amber / crimson / violet / slate；",
                "menu_theme = amber / tech / crimson（设置界面主题）。").push("style");
        STYLE = reg(b.define("style", "apex"));
        PALETTE = reg(b.define("palette", "aurora"));
        MENU_THEME = reg(b.define("menu_theme", "amber"));
        OPACITY = reg(b.defineInRange("opacity", 1.0, 0.15, 1.0));
        DEGREE_SYMBOL = reg(b.define("degree_symbol", false));
        b.pop();

        b.comment("颜色覆盖：-1 表示跟随配色方案，其余值为 0xRRGGBB 整数（TOML 中可用十进制）。").push("colors");
        COLOR_ACCENT = reg(b.defineInRange("accent", -1, -1, 0xFFFFFF));
        COLOR_TEXT = reg(b.defineInRange("text", -1, -1, 0xFFFFFF));
        COLOR_DIM = reg(b.defineInRange("dim_text", -1, -1, 0xFFFFFF));
        COLOR_TICK = reg(b.defineInRange("tick", -1, -1, 0xFFFFFF));
        COLOR_BACKGROUND = reg(b.defineInRange("background", -1, -1, 0xFFFFFF));
        b.pop();

        b.comment("动画：smoothness 为弹簧刚度（4~34，越大越跟手，越小越绵软）。").push("animation");
        SMOOTHNESS = reg(b.defineInRange("smoothness", 15.0, CompassHeading.OMEGA_MIN, CompassHeading.OMEGA_MAX));
        SNAP_ASSIST = reg(b.define("snap_assist", true));
        SNAP_RANGE = reg(b.defineInRange("snap_range", 6, 2, 20));
        INERTIA_TILT = reg(b.define("inertia_tilt", true));
        TILT_INTENSITY = reg(b.defineInRange("tilt_intensity", 1.0, 0.0, 3.0));
        ENTRY_ANIMATION = reg(b.define("entry_animation", true));
        b.pop();

        b.comment("内容：range 为可见视野总角度；minor_step 为次级刻度间隔（宽条带时刻度会自动加密保持视觉密度）。").push("content");
        RANGE = reg(b.defineInRange("range", 120, 60, 360));
        MINOR_STEP = reg(b.defineInRange("minor_step", 5, 5, 30));
        NUMBER_STEP = reg(b.defineInRange("number_step", 15, 15, 90));
        SHOW_CARDINALS = reg(b.define("show_cardinals", true));
        SHOW_INTERCARDINALS = reg(b.define("show_intercardinals", true));
        SHOW_NUMBERS = reg(b.define("show_numbers", true));
        CJK_LABELS = reg(b.define("cjk_labels", true));
        CARDINAL_SCALE = reg(b.defineInRange("cardinal_scale", 1.25, 0.8, 2.0));
        b.pop();

        b.comment("标点联动：战术标点来自本模组中键标记系统（只读）；",
                "death = 死亡位置自动标记（靠近自动消失）；show_labels = 标点文字标签。").push("markers");
        MARKERS_ENABLED = reg(b.define("enabled", true));
        MARKERS_TACTICAL = reg(b.define("tactical", true));
        MARKERS_DISTANCE = reg(b.define("show_distance", true));
        MARKERS_PULSE = reg(b.define("pulse", true));
        MARKERS_TEST = reg(b.define("test_markers", true));
        MARKERS_DEATH = reg(b.define("death", true));
        MARKERS_LABELS = reg(b.define("show_labels", true));
        b.pop();

        SPEC = b.build();
    }

    private CompassConfig() {}

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
        return "draginventory-compass-client.toml";
    }
}

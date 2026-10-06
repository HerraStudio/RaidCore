package dev.draginventory.client.map;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 游戏内指令 /herramap（别名 /map，快捷 /m）：战术地图的全部可调项均可指令化。
 * <ul>
 *   <li>不带参数：直接打开<b>大地图</b>（与 M 键同一入口，v2.5.0 起；
 *       /m 一个字母即可唤出，符合直觉）；gui 子命令打开地图设置界面
 *       （与地图工具栏 settings 按钮同入口）</li>
 *   <li>toggle / on / off：总开关（v2.5.0 起 ENABLED 绑定 M 键与大地图、
 *       控制小地图显示，不再是纯存储项）</li>
 *   <li>span &lt;1..16&gt;：层高跨度（自动楼层切换死区灵敏度，v2.5.2 起楼层恒定自动跟随玩家）</li>
 *   <li>layer list：8 个语义图层开关汇总一条消息；layer &lt;id&gt; &lt;on|off&gt; 单独开关
 *       （id：walls / floor / water / stairs / doors / decor / upper / lower，未知 id 报错列合法值）</li>
 *   <li>opacity：地图不透明度（0.3~1.0）</li>
 *   <li>filter：无参反馈当前滤镜；filter &lt;id&gt; 六选一预设切换
 *       （id：none / delta / tarkov / darkzone / apex / pubg，未知 id 报错列合法值，
 *       反馈携带样式名翻译键）</li>
 *   <li>grid &lt;on|off&gt;：网格线</li>
 *   <li>minimap：无参反馈状态；minimap on|off 小地图开关；
 *       minimap zoom &lt;0.5~4.0&gt; 缩放；minimap size &lt;64..256&gt; 尺寸（像素）</li>
 *   <li>radius &lt;64..512&gt;：楼层扫描半径（格）</li>
 *   <li>zoom min &lt;1.08~2.0&gt; / zoom max &lt;2.0~16.0&gt; / zoom reset：缩放范围（v2.5.5 默认
 *       1.62~9.0，即屏幕百分比 108%~600%；reset 回默认）；</li>
 *   <li>timer：无参反馈状态（剩余/默认）；timer &lt;时长&gt; 立刻开始倒计时
 *       （支持 25:00、1:30:00、30m、1h30m、90s、纯数字=分钟）；timer default &lt;时长&gt;
 *       设手动默认时长；timer reset 重置为默认时长；timer off 关闭手动计时
 *       （v2.5.5，小地图计时行）</li>
 *   <li>follow &lt;on|off&gt;：打开地图时居中玩家</li>
 *   <li>marker style &lt;tactical|minimal&gt;：标点风格（未知报错列合法值）；
 *       marker ping &lt;location|enemy|item&gt;：地图右键放置的标点类型（未知报错列合法值）；
 *       marker show &lt;on|off&gt;：地图上是否显示标点；
 *       marker distance / marker countdown &lt;on|off&gt;：距离标注与倒计时</li>
 *   <li>player_size &lt;0.4~1.0&gt;：玩家位置图标大小（1.0 = 默认尺寸 = 上限，反馈以百分比
 *       显示；作用于小地图/大地图/设置预览与尾迹，v2.6.0）</li>
 *   <li>reset：全部恢复默认；info：一条消息汇总当前配置（9 项，占位符与实参严格一致）</li>
 * </ul>
 * v2.5.2（反馈⑤）：floor 子树（auto / &lt;y&gt; / up / down）已随手动楼层功能整体移除，
 * 楼层恒定自动跟随玩家。
 * 数值参数由对应 ArgumentType 限定范围（越界在解析期即报错）；
 * 字符串参数 tab 补全合法值，非法值 sendFailure 并列出全部可选项。
 * 所有指令均为客户端指令（地图与配置均在客户端），无需权限。
 */
public final class MapCommands {

    /** 语言键前缀。 */
    private static final String K = "draginventory.map.cmd.";

    /** 标点风格合法值（与 MapConfig.MARKER_STYLE 约定一致）。 */
    private static final List<String> MARKER_STYLES = List.of("tactical", "minimal");

    /** 地图右键标点类型合法值（与 MapConfig.MARKER_PING_TYPE 约定一致）。 */
    private static final List<String> PING_TYPES = List.of("location", "enemy", "item");

    /** 滤镜预设合法值（由 FactoryMapPalette.Style 枚举生成，单一事实源）。 */
    private static final List<String> FILTER_IDS = Arrays.stream(FactoryMapPalette.Style.values())
            .map(style -> style.name().toLowerCase(Locale.ROOT)).toList();

    /** 指令 id → 图层开关配置 + 本地化名称键。 */
    private record LayerDef(String id, ModConfigSpec.BooleanValue value, String nameKey) {}

    private static final List<LayerDef> LAYERS = List.of(
            new LayerDef("walls", MapConfig.SHOW_WALLS, "draginventory.map.layer_walls"),
            new LayerDef("floor", MapConfig.SHOW_FLOOR, "draginventory.map.layer_floor"),
            new LayerDef("water", MapConfig.SHOW_WATER, "draginventory.map.layer_water"),
            new LayerDef("stairs", MapConfig.SHOW_STAIRS, "draginventory.map.layer_stairs"),
            new LayerDef("doors", MapConfig.SHOW_DOORS, "draginventory.map.layer_doors"),
            new LayerDef("decor", MapConfig.SHOW_DECOR, "draginventory.map.layer_decor"),
            new LayerDef("upper", MapConfig.SHOW_UPPER, "draginventory.map.layer_upper"),
            new LayerDef("lower", MapConfig.SHOW_LOWER, "draginventory.map.layer_lower"));

    private static final String LAYER_IDS = LAYERS.stream().map(LayerDef::id).collect(Collectors.joining(", "));

    private MapCommands() {}

    /**
     * 由 {@code MapClientEvents} 在 RegisterClientCommandsEvent 时调用。
     * 三个根同口径：/herramap、/map 为完整子树，无参均直接打开大地图；
     * /m 为快捷别名（同样开大地图，对齐 /com 的快捷定位）。
     */
    public static List<LiteralArgumentBuilder<CommandSourceStack>> buildRoots() {
        List<LiteralArgumentBuilder<CommandSourceStack>> roots = new ArrayList<>(3);
        roots.add(build("herramap"));
        roots.add(build("map"));
        // 快捷别名：一个字母直接唤出战术地图。
        roots.add(Commands.literal("m").executes(ctx -> {
            openBigMap(ctx, K + "opened");
            return 1;
        }));
        return roots;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .executes(ctx -> {
                    openBigMap(ctx, K + "opened");
                    return 1;
                })
                // ENABLED 绑定 M 键与大地图、控制小地图显示（v2.5.0 起生效，反馈照常）。
                .then(Commands.literal("toggle").executes(ctx -> {
                    boolean enabled = !MapConfig.ENABLED.get();
                    MapConfig.set(MapConfig.ENABLED, enabled);
                    feedback(ctx, enabled ? K + "enabled" : K + "disabled");
                    return 1;
                }))
                .then(Commands.literal("on").executes(ctx -> setEnabled(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setEnabled(ctx, false)))
                .then(Commands.literal("gui").executes(ctx -> {
                    openSettings(ctx, K + "gui");
                    return 1;
                }))
                .then(Commands.literal("span")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1, 16))
                                .executes(ctx -> {
                                    int v = IntegerArgumentType.getInteger(ctx, "value");
                                    MapConfig.set(MapConfig.LAYER_SPAN, v);
                                    feedback(ctx, K + "span", Component.literal(String.valueOf(v)));
                                    return 1;
                                })))
                .then(Commands.literal("layer")
                        .then(Commands.literal("list").executes(ctx -> {
                            feedback(ctx, K + "layer_list", layerSummary());
                            return 1;
                        }))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        LAYERS.stream().map(LayerDef::id), builder))
                                .then(Commands.literal("on").executes(ctx -> setLayer(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> setLayer(ctx, false)))))
                .then(Commands.literal("opacity")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.3, 1.0))
                                .executes(ctx -> setDouble(ctx, MapConfig.OPACITY, K + "opacity"))))
                .then(Commands.literal("filter")
                        // 无参：反馈当前滤镜预设（样式名翻译键）。
                        .executes(ctx -> {
                            feedback(ctx, K + "filter", currentFilterName());
                            return 1;
                        })
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        FILTER_IDS, builder))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    if (!FILTER_IDS.contains(id)) {
                                        // 未知滤镜直接报错并列出合法值，不静默回退。
                                        ctx.getSource().sendFailure(Component.translatable(K + "unknown_filter",
                                                Component.literal(id),
                                                Component.literal(String.join(", ", FILTER_IDS))));
                                        return 0;
                                    }
                                    MapConfig.set(MapConfig.FILTER_STYLE, id);
                                    feedback(ctx, K + "filter_set",
                                            Component.translatable("draginventory.map.filter_" + id));
                                    return 1;
                                })))
                .then(Commands.literal("grid")
                        .then(Commands.literal("on").executes(ctx -> {
                            MapConfig.set(MapConfig.GRID, true);
                            feedback(ctx, K + "grid_on");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            MapConfig.set(MapConfig.GRID, false);
                            feedback(ctx, K + "grid_off");
                            return 1;
                        })))
                .then(Commands.literal("radius")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(64, 512))
                                .executes(ctx -> {
                                    int v = IntegerArgumentType.getInteger(ctx, "blocks");
                                    MapConfig.set(MapConfig.SCAN_RADIUS, v);
                                    feedback(ctx, K + "radius", Component.literal(String.valueOf(v)));
                                    return 1;
                                })))
                .then(Commands.literal("zoom")
                        .then(Commands.literal("min")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(1.08, 2.0))
                                        .executes(ctx -> setDouble(ctx, MapConfig.ZOOM_MIN, K + "zoom_min"))))
                        .then(Commands.literal("max")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(2.0, 16.0))
                                        .executes(ctx -> setDouble(ctx, MapConfig.ZOOM_MAX, K + "zoom_max"))))
                        .then(Commands.literal("reset").executes(ctx -> {
                            // 两值回默认（1.62 / 9.0，即 108%~600%）：从配置默认值读取，不硬编码。
                            MapConfig.set(MapConfig.ZOOM_MIN, MapConfig.ZOOM_MIN.getDefault());
                            MapConfig.set(MapConfig.ZOOM_MAX, MapConfig.ZOOM_MAX.getDefault());
                            feedback(ctx, K + "zoom_reset",
                                    Component.literal(fmt(MapConfig.ZOOM_MIN.get())),
                                    Component.literal(fmt(MapConfig.ZOOM_MAX.get())));
                            return 1;
                        })))
                .then(Commands.literal("follow")
                        .then(Commands.literal("on").executes(ctx ->
                                setFlag(ctx, MapConfig.FOLLOW_PLAYER, K + "follow", true)))
                        .then(Commands.literal("off").executes(ctx ->
                                setFlag(ctx, MapConfig.FOLLOW_PLAYER, K + "follow", false))))
                .then(Commands.literal("minimap")
                        // 无参：反馈小地图开关状态。
                        .executes(ctx -> {
                            feedback(ctx, K + "minimap",
                                    Component.literal(MapConfig.MINIMAP_ENABLED.get() ? "on" : "off"));
                            return 1;
                        })
                        .then(Commands.literal("on").executes(ctx -> {
                            MapConfig.set(MapConfig.MINIMAP_ENABLED, true);
                            feedback(ctx, K + "minimap_on");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            MapConfig.set(MapConfig.MINIMAP_ENABLED, false);
                            feedback(ctx, K + "minimap_off");
                            return 1;
                        }))
                        .then(Commands.literal("zoom")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.5, 4.0))
                                        .executes(ctx -> setDouble(ctx, MapConfig.MINIMAP_ZOOM, K + "minimap_zoom"))))
                        .then(Commands.literal("size")
                                .then(Commands.argument("value", IntegerArgumentType.integer(64, 256))
                                        .executes(ctx -> {
                                            int v = IntegerArgumentType.getInteger(ctx, "value");
                                            MapConfig.set(MapConfig.MINIMAP_SIZE, v);
                                            feedback(ctx, K + "minimap_size", Component.literal(String.valueOf(v)));
                                            return 1;
                                        }))))
                .then(Commands.literal("timer")
                        // 无参：计时状态（剩余时间 / 默认时长）；off 状态显示字面 off。
                        .executes(ctx -> {
                            feedback(ctx, K + "timer",
                                    Component.literal(MapMatchTimer.hasProvider() || MapMatchTimer.isRunning()
                                            ? MapMatchTimer.format() : "off"),
                                    Component.literal(MapMatchTimer.formatSeconds(MapMatchTimer.defaultDuration())));
                            return 1;
                        })
                        .then(Commands.literal("off").executes(ctx -> {
                            if (!manualTimerAvailable(ctx)) return 0;
                            MapMatchTimer.stop();
                            feedback(ctx, K + "timer_off");
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(ctx -> {
                            if (!manualTimerAvailable(ctx)) return 0;
                            MapMatchTimer.restartDefault();
                            feedback(ctx, K + "timer_reset", Component.literal(MapMatchTimer.format()));
                            return 1;
                        }))
                        .then(Commands.literal("default")
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .executes(ctx -> setTimerDuration(ctx, true))))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(ctx -> setTimerDuration(ctx, false))))
                .then(Commands.literal("marker")
                        .then(Commands.literal("style")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                MARKER_STYLES, builder))
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            if (!MARKER_STYLES.contains(id)) {
                                                // 未知风格直接报错并列出合法值，不静默回退。
                                                ctx.getSource().sendFailure(Component.translatable(K + "unknown_style",
                                                        Component.literal(id),
                                                        Component.literal(String.join(", ", MARKER_STYLES))));
                                                return 0;
                                            }
                                            MapConfig.set(MapConfig.MARKER_STYLE, id);
                                            feedback(ctx, K + "style", Component.literal(id));
                                            return 1;
                                        })))
                        .then(Commands.literal("distance")
                                .then(Commands.literal("on").executes(ctx ->
                                        setFlag(ctx, MapConfig.SHOW_DISTANCES, K + "distance", true)))
                                .then(Commands.literal("off").executes(ctx ->
                                        setFlag(ctx, MapConfig.SHOW_DISTANCES, K + "distance", false))))
                        .then(Commands.literal("countdown")
                                .then(Commands.literal("on").executes(ctx ->
                                        setFlag(ctx, MapConfig.SHOW_COUNTDOWN, K + "countdown", true)))
                                .then(Commands.literal("off").executes(ctx ->
                                        setFlag(ctx, MapConfig.SHOW_COUNTDOWN, K + "countdown", false))))
                        .then(Commands.literal("ping")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                PING_TYPES, builder))
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            if (!PING_TYPES.contains(id)) {
                                                // 未知类型直接报错并列出合法值，不静默回退。
                                                ctx.getSource().sendFailure(Component.translatable(K + "unknown_ping",
                                                        Component.literal(id),
                                                        Component.literal(String.join(", ", PING_TYPES))));
                                                return 0;
                                            }
                                            MapConfig.set(MapConfig.MARKER_PING_TYPE, id);
                                            feedback(ctx, K + "marker_ping",
                                                    Component.translatable("draginventory.map." + id));
                                            return 1;
                                        })))
                        .then(Commands.literal("show")
                                .then(Commands.literal("on").executes(ctx ->
                                        setFlag(ctx, MapConfig.MARKERS_VISIBLE, K + "marker_show", true)))
                                .then(Commands.literal("off").executes(ctx ->
                                        setFlag(ctx, MapConfig.MARKERS_VISIBLE, K + "marker_show", false)))))
                .then(Commands.literal("player_size")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.4, 1.0))
                                .executes(ctx -> {
                                    double v = DoubleArgumentType.getDouble(ctx, "value");
                                    MapConfig.set(MapConfig.PLAYER_MARKER_SIZE, v);
                                    // 反馈按百分比展示（与设置页滑条同口径），入参为倍率。
                                    feedback(ctx, K + "player_size",
                                            Component.literal(Math.round(v * 100) + "%"));
                                    return 1;
                                })))
                .then(Commands.literal("reset").executes(ctx -> {
                    MapConfig.resetToDefaults();
                    feedback(ctx, K + "reset");
                    return 1;
                }))
                .then(Commands.literal("info").executes(ctx -> {
                    // v2.5.2：楼层恒定自动跟随玩家（反馈⑤移除手动层）——
                    // 模式固定显示“自动跟随玩家”，实际楼面由会话动态解析，显示 "-"。
                    Component floor = Component.literal("-");
                    // 标点汇总：显示/隐藏 · 当前放置类型（非法配置值回退 location，与地图屏同口径）。
                    Component markers = Component.literal(MapConfig.MARKERS_VISIBLE.get() ? "on" : "off")
                            .append(" · ").append(pingTypeName());
                    // 对局计时（v2.5.5）：剩余时间或 off 字面。
                    Component timer = Component.literal(MapMatchTimer.hasProvider() || MapMatchTimer.isRunning()
                            ? MapMatchTimer.format() : "off");
                    // 占位符与实参严格一一对应：本键 10 个 %s，下方恰好传 10 个参数（v1.5.4 教训）。
                    feedback(ctx, K + "info",
                            Component.translatable("draginventory.map.floor_auto_follow"),
                            floor,
                            currentFilterName(),
                            Component.literal(fmt(MapConfig.OPACITY.get())),
                            Component.literal(MapConfig.MINIMAP_ENABLED.get() ? "on" : "off"),
                            markers,
                            layerSummary(),
                            Component.literal(fmt(MapConfig.ZOOM_MIN.get()) + " ~ " + fmt(MapConfig.ZOOM_MAX.get())),
                            Component.literal(String.valueOf(MapConfig.SCAN_RADIUS.get())),
                            timer);
                    return 1;
                }));
    }

    /**
     * /map timer [default] &lt;时长&gt;：解析时长（冒号式/后缀式/纯数字=分钟）并启动或写入默认。
     * 解析失败时反馈带示例的错误（不静默回退）；写入默认时长同时同步内存与配置。
     */
    private static int setTimerDuration(CommandContext<CommandSourceStack> ctx, boolean asDefault) {
        if (!asDefault && !manualTimerAvailable(ctx)) return 0;
        String raw = StringArgumentType.getString(ctx, "duration");
        long seconds;
        try {
            seconds = MapMatchTimer.parseSeconds(raw);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.translatable(K + "timer_bad_duration",
                    Component.literal(raw)));
            return 0;
        }
        String formatted = MapMatchTimer.formatSeconds(seconds);
        if (asDefault) {
            MapConfig.set(MapConfig.MATCH_TIMER_DURATION, (int) Math.min(seconds, Integer.MAX_VALUE));
            MapMatchTimer.setDefaultDuration(seconds);
            feedback(ctx, K + "timer_default_set", Component.literal(formatted));
        } else {
            MapMatchTimer.start(seconds);
            feedback(ctx, K + "timer_set", Component.literal(formatted));
        }
        return 1;
    }

    private static boolean manualTimerAvailable(CommandContext<CommandSourceStack> ctx) {
        if (!MapMatchTimer.hasProvider()) return true;
        ctx.getSource().sendFailure(Component.translatable(K + "timer_server_controlled"));
        return false;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> ctx, boolean value) {
        MapConfig.set(MapConfig.ENABLED, value);
        feedback(ctx, value ? K + "enabled" : K + "disabled");
        return 1;
    }

    private static int setLayer(CommandContext<CommandSourceStack> ctx, boolean on) {
        String id = StringArgumentType.getString(ctx, "id");
        LayerDef def = LAYERS.stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
        if (def == null) {
            // 未知图层直接报错并列出全部合法值，不静默忽略。
            ctx.getSource().sendFailure(Component.translatable(K + "unknown_layer",
                    Component.literal(id), Component.literal(LAYER_IDS)));
            return 0;
        }
        MapConfig.set(def.value(), on);
        feedback(ctx, K + "layer_set",
                Component.translatable(def.nameKey()), Component.literal(on ? "on" : "off"));
        return 1;
    }

    private static int setDouble(CommandContext<CommandSourceStack> ctx,
            ModConfigSpec.DoubleValue value, String key) {
        double v = DoubleArgumentType.getDouble(ctx, "value");
        MapConfig.set(value, v);
        feedback(ctx, key, Component.literal(fmt(v)));
        return 1;
    }

    private static int setFlag(CommandContext<CommandSourceStack> ctx,
            ModConfigSpec.BooleanValue value, String key, boolean on) {
        MapConfig.set(value, on);
        feedback(ctx, key, Component.literal(on ? "on" : "off"));
        return 1;
    }

    /** 8 个图层开关汇总为一个本地化组件：墙体✓ · 地面✓ · ……（复用图层名称键，✓/✗ 中立语言符号）。 */
    private static Component layerSummary() {
        MutableComponent summary = Component.empty();
        for (int i = 0; i < LAYERS.size(); i++) {
            LayerDef def = LAYERS.get(i);
            if (i > 0) {
                summary.append(" · ");
            }
            summary.append(Component.translatable(def.nameKey()))
                    .append(def.value().get() ? "\u2713" : "\u2717");
        }
        return summary;
    }

    /**
     * 打开大地图并反馈（v2.5.0 起无参 / /m 的行为：与 M 键同一入口，含 FOLLOW_PLAYER
     * 视野记忆恢复；setScreen 经 mc.execute 包裹确保在主线程执行）。
     */
    private static void openBigMap(CommandContext<CommandSourceStack> ctx, String feedbackKey) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(FactoryMapScreen.create()));
        feedback(ctx, feedbackKey);
    }

    /** 打开地图设置界面并反馈（/map gui，与地图工具栏 settings 按钮同入口）。 */
    private static void openSettings(CommandContext<CommandSourceStack> ctx, String feedbackKey) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(MapSettingsScreen.create()));
        feedback(ctx, feedbackKey);
    }

    /** 当前滤镜预设的本地化名称（非法配置值回退 none，与设置页/预览读数同口径）。 */
    private static Component currentFilterName() {
        String id = FactoryMapPalette.styleFromId(MapConfig.FILTER_STYLE.get())
                .name().toLowerCase(Locale.ROOT);
        return Component.translatable("draginventory.map.filter_" + id);
    }

    /** 当前右键放置类型的本地化名称（非法配置值回退 location，与地图屏 pingType 同口径）。 */
    private static Component pingTypeName() {
        String id = MapConfig.MARKER_PING_TYPE.get();
        String safe = id != null && PING_TYPES.contains(id) ? id : "location";
        return Component.translatable("draginventory.map." + safe);
    }

    private static void feedback(CommandContext<CommandSourceStack> ctx, String key, Component... args) {
        ctx.getSource().sendSuccess(() -> Component.translatable(key, (Object[]) args), false);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}

package dev.draginventory.client.compass;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 游戏内指令 /herracompass（别名 /compass，快捷 /com）：
 * <ul>
 *   <li>不带参数：打开图形化设置界面（/com 三个字母即可唤出）</li>
 *   <li>子指令覆盖全部常用配置：开关 / 位置 / 缩放 / 透明度 / 风格 / 配色 / 动画 / 范围 / 标点</li>
 *   <li>test 子指令：创建测试标点，验证方位条与标点系统的联动</li>
 *   <li>reset 恢复默认；reload 由配置文件热重载机制自动完成</li>
 * </ul>
 * 所有指令均为客户端指令（配置与 HUD 均在客户端），无需权限。
 */
public final class CompassCommands {

    private CompassCommands() {}

    /** 由 {@code CompassClientEvents} 在 RegisterClientCommandsEvent 时调用。 */
    public static List<LiteralArgumentBuilder<CommandSourceStack>> buildRoots() {
        List<LiteralArgumentBuilder<CommandSourceStack>> roots = new ArrayList<>(3);
        roots.add(build("herracompass"));
        roots.add(build("compass"));
        // 快捷别名：只需敲前三个字母即可唤出设置界面（用户需求）。
        roots.add(Commands.literal("com").executes(ctx -> {
            openSettings();
            return 1;
        }));
        return roots;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .executes(ctx -> {
                    openSettings();
                    return 1;
                })
                .then(Commands.literal("toggle").executes(ctx -> {
                    boolean enabled = !CompassConfig.ENABLED.get();
                    CompassConfig.set(CompassConfig.ENABLED, enabled);
                    feedback(ctx, enabled ? "draginventory.compass.cmd.enabled" : "draginventory.compass.cmd.disabled");
                    return 1;
                }))
                .then(Commands.literal("on").executes(ctx -> setBool(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setBool(ctx, false)))
                .then(Commands.literal("gui").executes(ctx -> {
                    openSettings();
                    return 1;
                }))
                .then(Commands.literal("style")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        CompassStyle.all().stream().map(CompassStyle::id), builder))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    CompassStyle style = CompassStyle.byIdOrNull(id);
                                    if (style == null) {
                                        // 未知 id 直接报错并列出合法值，不静默回退默认皮肤。
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "draginventory.compass.cmd.unknown_style",
                                                Component.literal(id), Component.literal(CompassStyle.idList())));
                                        return 0;
                                    }
                                    CompassConfig.set(CompassConfig.STYLE, style.id());
                                    feedback(ctx, "draginventory.compass.cmd.style", Component.literal(style.id()));
                                    return 1;
                                })))
                .then(Commands.literal("palette")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        CompassPalette.all().stream().map(CompassPalette::id), builder))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    CompassPalette palette = CompassPalette.byIdOrNull(id);
                                    if (palette == null) {
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "draginventory.compass.cmd.unknown_palette",
                                                Component.literal(id), Component.literal(CompassPalette.idList())));
                                        return 0;
                                    }
                                    CompassConfig.set(CompassConfig.PALETTE, palette.id());
                                    feedback(ctx, "draginventory.compass.cmd.palette", Component.literal(palette.id()));
                                    return 1;
                                })))
                .then(Commands.literal("offset")
                        .then(Commands.argument("x", IntegerArgumentType.integer(-640, 640))
                                .then(Commands.argument("y", IntegerArgumentType.integer(-640, 640))
                                        .executes(ctx -> {
                                            int x = IntegerArgumentType.getInteger(ctx, "x");
                                            int y = IntegerArgumentType.getInteger(ctx, "y");
                                            CompassConfig.set(CompassConfig.OFFSET_X, x);
                                            CompassConfig.set(CompassConfig.OFFSET_Y, y);
                                            feedback(ctx, "draginventory.compass.cmd.offset",
                                                    Component.literal(x + ", " + y));
                                            return 1;
                                        }))))
                .then(Commands.literal("scale")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.5, 2.0))
                                .executes(ctx -> {
                                    double v = DoubleArgumentType.getDouble(ctx, "value");
                                    CompassConfig.set(CompassConfig.SCALE, v);
                                    feedback(ctx, "draginventory.compass.cmd.scale", Component.literal(fmt(v)));
                                    return 1;
                                })))
                .then(Commands.literal("opacity")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.15, 1.0))
                                .executes(ctx -> {
                                    double v = DoubleArgumentType.getDouble(ctx, "value");
                                    CompassConfig.set(CompassConfig.OPACITY, v);
                                    feedback(ctx, "draginventory.compass.cmd.opacity", Component.literal(fmt(v)));
                                    return 1;
                                })))
                .then(Commands.literal("width")
                        .then(Commands.argument("px", IntegerArgumentType.integer(120, 960))
                                .executes(ctx -> {
                                    int v = IntegerArgumentType.getInteger(ctx, "px");
                                    CompassConfig.set(CompassConfig.BAR_WIDTH, v);
                                    feedback(ctx, "draginventory.compass.cmd.width", Component.literal(String.valueOf(v)));
                                    return 1;
                                })))
                .then(Commands.literal("range")
                        .then(Commands.argument("degrees", IntegerArgumentType.integer(60, 360))
                                .executes(ctx -> {
                                    int v = IntegerArgumentType.getInteger(ctx, "degrees");
                                    CompassConfig.set(CompassConfig.RANGE, v);
                                    feedback(ctx, "draginventory.compass.cmd.range", Component.literal(v + "\u00B0"));
                                    return 1;
                                })))
                .then(Commands.literal("smooth")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(
                                CompassHeading.OMEGA_MIN, CompassHeading.OMEGA_MAX))
                                .executes(ctx -> {
                                    double v = DoubleArgumentType.getDouble(ctx, "value");
                                    CompassConfig.set(CompassConfig.SMOOTHNESS, v);
                                    feedback(ctx, "draginventory.compass.cmd.smooth", Component.literal(fmt(v)));
                                    return 1;
                                })))
                .then(Commands.literal("markers")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean v = BoolArgumentType.getBool(ctx, "on");
                                    CompassConfig.set(CompassConfig.MARKERS_ENABLED, v);
                                    feedback(ctx, v ? "draginventory.compass.cmd.markers_on"
                                            : "draginventory.compass.cmd.markers_off");
                                    return 1;
                                })))
                .then(Commands.literal("test")
                        .then(Commands.literal("enemy").executes(ctx -> addTestMark(ctx, CompassMark.Kind.ENEMY, 15)))
                        .then(Commands.literal("location").executes(ctx -> addTestMark(ctx, CompassMark.Kind.LOCATION, 0)))
                        .then(Commands.literal("item").executes(ctx -> addTestMark(ctx, CompassMark.Kind.ITEM, -15)))
                        .then(Commands.literal("clear").executes(ctx -> {
                            CompassHub.clearTestMarks();
                            feedback(ctx, "draginventory.compass.cmd.test_cleared");
                            return 1;
                        })))
                .then(Commands.literal("reset").executes(ctx -> {
                    CompassConfig.resetToDefaults();
                    feedback(ctx, "draginventory.compass.cmd.reset");
                    return 1;
                }))
                .then(Commands.literal("copy").executes(ctx -> {
                    // 当前朝向 + 方位名复制到剪贴板，方便分享给队友。
                    float heading = CompassApi.getSmoothHeading();
                    if (heading < 0) {
                        ctx.getSource().sendFailure(Component.translatable("draginventory.compass.cmd.no_heading"));
                        return 0;
                    }
                    int degrees = Math.round(heading) % 360;
                    String cardinal = CompassWidget.cardinalName(
                            CompassHeading.nearestCardinal(heading));
                    String text = degrees + "\u00B0 " + cardinal;
                    Minecraft.getInstance().keyboardHandler.setClipboard(text);
                    feedback(ctx, "draginventory.compass.cmd.copy_done", Component.literal(text));
                    return 1;
                }))
                .then(Commands.literal("info").executes(ctx -> {
                    float heading = CompassApi.getSmoothHeading();
                    // heading < 0 表示 HUD 尚未初始化（未进入世界/旁观者），不显示方位名。
                    // nearestCardinal 内部先做浮点除再取整：整数除法会把 315~359° 错误归到“西”。
                    String cardinal = heading < 0 ? "-" : CompassWidget.cardinalName(
                            CompassHeading.nearestCardinal(heading));
                    // v1.5.4 修复：语言键有 5 个占位符（朝向/方位/皮肤/配色/标点数），
                    // 旧版只传 1 个合并参数，未填充的占位符原样输出 %s/%d。
                    // 朝向取模对齐 /compass copy：359.7° 四舍五入到 360 时显示 0°。
                    feedback(ctx, "draginventory.compass.cmd.info",
                            Component.literal(heading < 0 ? "-"
                                    : Math.round(heading) % 360 + "\u00B0"),
                            Component.literal(cardinal),
                            Component.literal(CompassConfig.STYLE.get()),
                            Component.literal(CompassConfig.PALETTE.get()),
                            Component.literal(String.valueOf(CompassWidget.countLiveMarks())));
                    return 1;
                }));
    }

    private static int setBool(CommandContext<CommandSourceStack> ctx, boolean value) {
        CompassConfig.set(CompassConfig.ENABLED, value);
        feedback(ctx, value ? "draginventory.compass.cmd.enabled" : "draginventory.compass.cmd.disabled");
        return 1;
    }

    private static void openSettings() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(CompassSettingsScreen.create()));
    }

    /** 在玩家当前朝向前方随机 48~144 米、偏转 bearingOffset 度处生成测试标点（指令与设置界面共用）。 */
    public static int spawnTestMark(CompassMark.Kind kind, int bearingOffset) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        double distance = 48 + player.getRandom().nextInt(96);
        double bearing = Math.toRadians(CompassHeading.toHeading(player.getYRot()) + bearingOffset);
        Vec3 position = player.position().add(Math.sin(bearing) * distance, 0, -Math.cos(bearing) * distance);
        // 与实际战术标点同款配色：敌人红、其余白（方位条下图标也与实际标点一致）。
        int color = switch (kind) {
            case ENEMY -> 0xFF4949;
            default -> 0xFFFFFF;
        };
        // 掉落物测试标点携带示例物品图标（钻石），验证物品贴图渲染链路。
        net.minecraft.world.item.ItemStack icon = kind == CompassMark.Kind.ITEM
                ? new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND) : null;
        String key = "test-" + kind.name().toLowerCase(java.util.Locale.ROOT);
        CompassHub.addTestMark(CompassMark.of(key, kind, position, color,
                Component.translatable("draginventory.compass.marker." + kind.name().toLowerCase(java.util.Locale.ROOT)),
                true, System.currentTimeMillis(), icon));
        return 1;
    }

    private static int addTestMark(CommandContext<CommandSourceStack> ctx, CompassMark.Kind kind, int bearingOffset) {
        if (Minecraft.getInstance().player == null) {
            ctx.getSource().sendFailure(Component.translatable("draginventory.compass.cmd.no_player"));
            return 0;
        }
        int result = spawnTestMark(kind, bearingOffset);
        feedback(ctx, "draginventory.compass.cmd.test_added",
                Component.translatable("draginventory.compass.marker." + kind.name().toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    private static void feedback(CommandContext<CommandSourceStack> ctx, String key, Component... args) {
        ctx.getSource().sendSuccess(() -> Component.translatable(key, (Object[]) args), false);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}

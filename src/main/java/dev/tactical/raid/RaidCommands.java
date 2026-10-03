package dev.tactical.raid;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class RaidCommands {
    private RaidCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var command = Commands.literal("raid").executes(ctx -> help(ctx.getSource()));
        command.then(Commands.literal("join").executes(ctx -> RaidManager.join(ctx.getSource().getPlayerOrException()) ? 1 : 0));
        command.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        command.then(Commands.literal("result").executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException(); var record = RaidManager.current(player);
            if (record == null || record.session.active()) return error(ctx.getSource(), "当前没有待确认的结算。");
            RaidManager.sendSnapshot(player); return 1;
        }));
        command.then(Commands.literal("lobby").requires(source -> source.hasPermission(2)).executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException(); var data = RaidSavedData.get(player.server);
            data.config.lobby = RaidManager.location(player); RaidManager.configurationChanged(player.server);
            reply(ctx.getSource(), "已将当前位置设为大厅。", true); return 1;
        }));
        command.then(Commands.literal("name").requires(source -> source.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                    var name = StringArgumentType.getString(ctx, "name").strip();
                    if (name.isEmpty() || name.length() > 64) return error(ctx.getSource(), "地图名称需为 1–64 个字符。");
                    RaidSavedData.get(ctx.getSource().getServer()).config.mapName = name;
                    RaidManager.configurationChanged(ctx.getSource().getServer());
                    reply(ctx.getSource(), "地图名称：" + name, true); return 1;
                })));
        command.then(Commands.literal("spawn").requires(source -> source.hasPermission(2))
                .then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word()).executes(RaidCommands::addSpawn)))
                .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    var data = RaidSavedData.get(ctx.getSource().getServer());
                    if (data.config.spawns.remove(StringArgumentType.getString(ctx, "id")) == null) return error(ctx.getSource(), "出生点不存在。");
                    RaidManager.configurationChanged(ctx.getSource().getServer()); reply(ctx.getSource(), "已删除出生点。", true); return 1;
                })))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource()))));
        command.then(Commands.literal("extract").requires(source -> source.hasPermission(2))
                .then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(.5, 128))
                                .executes(ctx -> addExtraction(ctx, 20))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                        .executes(ctx -> addExtraction(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))))
                .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    var data = RaidSavedData.get(ctx.getSource().getServer());
                    if (data.config.extractions.remove(StringArgumentType.getString(ctx, "id")) == null) return error(ctx.getSource(), "撤离点不存在。");
                    RaidManager.configurationChanged(ctx.getSource().getServer()); reply(ctx.getSource(), "已删除撤离点。", true); return 1;
                })))
                .then(Commands.literal("fx").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("effect", StringArgumentType.word()).executes(ctx -> {
                            var data = RaidSavedData.get(ctx.getSource().getServer());
                            var id = StringArgumentType.getString(ctx, "id"); var previous = data.config.extractions.get(id);
                            if (previous == null) return error(ctx.getSource(), "撤离点不存在。");
                            var effect = StringArgumentType.getString(ctx, "effect");
                            if (ResourceLocation.tryParse(effect) == null) return error(ctx.getSource(), "特效需要合法的 namespace:path 资源名。");
                            data.config.extractions.put(id, new RaidConfig.Extraction(id, previous.location(), previous.radius(), previous.seconds(), effect));
                            RaidManager.configurationChanged(ctx.getSource().getServer()); reply(ctx.getSource(), "已设置撤离烟雾：" + effect, true); return 1;
                        }))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource()))));
        command.then(Commands.literal("list").requires(source -> source.hasPermission(2)).executes(ctx -> list(ctx.getSource())));
        command.then(Commands.literal("start").requires(source -> source.hasPermission(2))
                .then(Commands.argument("players", EntityArgument.players()).executes(ctx -> {
                    int joined = 0;
                    for (var player : EntityArgument.getPlayers(ctx, "players")) if (RaidManager.join(player)) joined++;
                    reply(ctx.getSource(), "已安排 " + joined + " 名玩家进入对局。", true); return joined;
                })));
        command.then(Commands.literal("abort").requires(source -> source.hasPermission(2))
                .executes(ctx -> abort(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("players", EntityArgument.players()).executes(ctx -> {
                    int aborted = 0;
                    for (var player : EntityArgument.getPlayers(ctx, "players")) aborted += abort(ctx.getSource(), player);
                    return aborted;
                })));
        dispatcher.register(command);
    }

    private static int addSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrException(); var data = RaidSavedData.get(player.server);
        String id = StringArgumentType.getString(ctx, "id");
        if (!validId(id)) return error(ctx.getSource(), "名称需为 1–32 个字符。");
        if (!data.config.spawns.containsKey(id) && data.config.spawns.size() >= 64) return error(ctx.getSource(), "最多配置 64 个出生点。");
        data.config.spawns.put(id, RaidManager.location(player)); RaidManager.configurationChanged(player.server);
        reply(ctx.getSource(), "已设置出生点：" + id, true); return 1;
    }

    private static int addExtraction(CommandContext<CommandSourceStack> ctx, int seconds) throws CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrException(); var data = RaidSavedData.get(player.server);
        String id = StringArgumentType.getString(ctx, "id");
        if (!validId(id)) return error(ctx.getSource(), "名称需为 1–32 个字符。");
        if (!data.config.extractions.containsKey(id) && data.config.extractions.size() >= 64) return error(ctx.getSource(), "最多配置 64 个撤离点。");
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        data.config.extractions.put(id, new RaidConfig.Extraction(id, RaidManager.location(player), radius, seconds, RaidConfig.DEFAULT_FX));
        RaidManager.configurationChanged(player.server);
        reply(ctx.getSource(), "已设置撤离点：" + id + "，方形边长 " + (radius * 2) + " 格，停留 " + seconds + " 秒。", true);
        return 1;
    }

    private static boolean validId(String id) { return !id.isBlank() && id.length() <= 32; }

    private static int abort(CommandSourceStack source, ServerPlayer player) {
        if (!RaidManager.finish(player, RaidSession.Outcome.ABORTED, true)) return error(source, player.getName().getString() + " 当前没有进行中的对局。");
        reply(source, "已终止 " + player.getName().getString() + " 的对局并返回大厅。", true); return 1;
    }

    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException(); var record = RaidManager.current(player);
        if (record == null) { reply(source, "当前未在对局中。使用 /raid join 入场。", false); return 1; }
        var session = record.session;
        String text = "地图：" + session.map + " | 状态：" + switch (session.status()) {
            case ACTIVE -> "行动中";
            case EXTRACTING -> "撤离中（" + session.zone() + "，剩余 " + Math.ceil(session.remainingTicks() / 20.0) + " 秒）";
            case SETTLED -> switch (session.outcome()) { case EXTRACTED -> "撤离成功"; case DEAD -> "阵亡"; case ABORTED -> "行动终止"; };
        } + " | 行动时间：" + (session.elapsedTicks() / 20) + " 秒 | 击杀：" + session.kills();
        reply(source, text, false); RaidManager.sendSnapshot(player); return 1;
    }

    private static int list(CommandSourceStack source) {
        var config = RaidSavedData.get(source.getServer()).config;
        reply(source, "地图：" + config.mapName + " | 大厅：" + (config.lobby == null ? "未设置" : coordinates(config.lobby)), false);
        config.spawns.forEach((id, location) -> reply(source, "出生点 " + id + "：" + coordinates(location), false));
        config.extractions.forEach((id, zone) -> reply(source, "撤离点 " + id + "：" + coordinates(zone.location()) + " | 半径 "
                + zone.radius() + " | " + zone.seconds() + " 秒 | " + zone.fx(), false));
        String error = RaidManager.readinessError(source.getServer());
        if (error != null) reply(source, "尚不可入场：" + error, false);
        return 1;
    }

    private static String coordinates(RaidConfig.Location point) {
        return point.dimension() + " (" + String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", point.x(), point.y(), point.z()) + ")";
    }

    private static int help(CommandSourceStack source) {
        reply(source, "/raid join 入场；/raid status 查看；/raid result 查看待确认的结算。", false);
        if (source.hasPermission(2)) {
            reply(source, "管理员：当前位置 /raid lobby；/raid spawn add <名称>；/raid extract add <名称> <半径> [秒数，默认20]。", false);
            reply(source, "/raid list 查看配置；/raid name <地图名称>；/raid start <玩家>；/raid abort [玩家]。", false);
        }
        return 1;
    }

    private static void reply(CommandSourceStack source, String message, boolean broadcast) {
        source.sendSuccess(() -> Component.literal(message), broadcast);
    }
    private static int error(CommandSourceStack source, String message) { source.sendFailure(Component.literal(message)); return 0; }
}

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
        command.then(Commands.literal("leave").executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException();
            if (!RaidManager.leave(player)) return error(ctx.getSource(), "当前没有可退出的等待或对局。");
            reply(ctx.getSource(), "已离开等待名单或终止参与本局。", false); return 1;
        }));
        command.then(Commands.literal("stop").requires(source -> source.hasPermission(2)).executes(ctx -> {
            if (!RaidManager.abortMatch(ctx.getSource().getServer())) return error(ctx.getSource(), "当前没有对局。");
            reply(ctx.getSource(), "已停止整局，未完成成员按行动终止处理。", true); return 1;
        }));
        command.then(Commands.literal("minplayers").requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    reply(ctx.getSource(), "最低开局人数：" + RaidSavedData.get(ctx.getSource().getServer()).config.minimumPlayers, false);
                    return 1;
                })
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                    int count = IntegerArgumentType.getInteger(ctx, "count");
                    RaidSavedData.get(ctx.getSource().getServer()).config.minimumPlayers = count;
                    RaidManager.configurationChanged(ctx.getSource().getServer());
                    reply(ctx.getSource(), "最低开局人数已设为 " + count + "（默认2，设为1可单人调试）。", true); return 1;
                })));
        command.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        command.then(Commands.literal("duration").requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    var config = RaidSavedData.get(ctx.getSource().getServer()).config;
                    reply(ctx.getSource(), "Raid 默认对局时长：" + config.matchDurationSeconds / 60 + " 分钟。", false);
                    return 1;
                })
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440)).executes(ctx -> {
                    int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                    RaidSavedData.get(ctx.getSource().getServer()).config.matchDurationSeconds = minutes * 60;
                    RaidManager.configurationChanged(ctx.getSource().getServer());
                    reply(ctx.getSource(), "Raid 默认对局时长已设为 " + minutes + " 分钟，下次入场生效。", true);
                    return 1;
                })));
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
                .executes(ctx -> start(ctx.getSource()))
                .then(Commands.argument("players", EntityArgument.players()).executes(ctx -> {
                    var server = ctx.getSource().getServer();
                    var match = RaidSavedData.get(server).match;
                    if (match != null && match.phase() != RaidMatch.Phase.WAITING)
                        return error(ctx.getSource(), "当前对局已开局，不能追加参与者。");
                    for (var player : EntityArgument.getPlayers(ctx, "players")) {
                        match = RaidSavedData.get(server).match;
                        if (match == null || !match.contains(player.getUUID())) RaidManager.join(player);
                    }
                    return start(ctx.getSource());
                })));
        command.then(Commands.literal("abort").requires(source -> source.hasPermission(2))
                .executes(ctx -> abort(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("players", EntityArgument.players()).executes(ctx -> {
                    int aborted = 0;
                    for (var player : EntityArgument.getPlayers(ctx, "players")) aborted += abort(ctx.getSource(), player);
                    return aborted;
                })));
        // Vanilla's development /raid root requires permission 3. Brigadier merges
        // same-name nodes while retaining the old predicate, hiding join from non-ops.
        // Remove the old children entry before registration so both lookup maps receive
        // this mod's complete root, with permissions scoped to the admin subcommands.
        dispatcher.getRoot().getChildren().removeIf(node -> node.getName().equals("raid"));
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
        var match = RaidSavedData.get(source.getServer()).match;
        if (match != null) {
            reply(source, "Raid ID：" + match.id + " | 阶段：" + match.phase() + " | 维度：" + match.dimension
                    + " | 成员：" + match.participants().size() + " | 行动中：" + match.activePlayers()
                    + " | 整局剩余：" + (match.remainingTicks() + 19) / 20 + " 秒", false);
            for (var entry : match.participants().entrySet()) {
                var online = source.getServer().getPlayerList().getPlayer(entry.getKey());
                var member = entry.getValue();
                reply(source, (online == null ? entry.getKey().toString() : online.getGameProfile().getName())
                        + " | " + member.session().status() + " | 出生点：" + member.spawnId(), false);
            }
        } else reply(source, "当前没有共享对局。", false);
        if (!(source.getEntity() instanceof ServerPlayer)) return 1;
        var player = source.getPlayerOrException(); var record = RaidManager.current(player);
        if (record == null) { reply(source, "当前未在对局中。使用 /raid join 入场。", false); return 1; }
        var session = record.session;
        String text = "地图：" + session.map + " | 状态：" + switch (session.status()) {
            case WAITING -> "等待统一开局";
            case ACTIVE -> "行动中";
            case EXTRACTING -> "撤离中（" + session.zone() + "，剩余 " + Math.ceil(session.remainingTicks() / 20.0) + " 秒）";
            case SETTLED -> switch (session.outcome()) {
                case EXTRACTED -> "撤离成功"; case DEAD -> "阵亡"; case ABORTED -> "行动终止"; case TIMED_OUT -> "行动超时";
            };
        } + (session.inRaid() && match != null ? " | 对局剩余：" + (match.remainingTicks() + 19) / 20 + " 秒" : "")
                + " | 行动时间：" + (session.elapsedTicks() / 20) + " 秒 | 击杀：" + session.kills();
        reply(source, text, false); RaidManager.sendSnapshot(player); return 1;
    }

    private static int list(CommandSourceStack source) {
        var config = RaidSavedData.get(source.getServer()).config;
        reply(source, "地图：" + config.mapName + " | 最低人数：" + config.minimumPlayers + " | 对局时长：" + config.matchDurationSeconds / 60 + " 分钟 | 大厅："
                + (config.lobby == null ? "未设置" : coordinates(config.lobby)), false);
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
        reply(source, "/raid join 加入等待；/raid leave 退出；/raid status 查看整局；/raid result 查看待确认的结算。", false);
        if (source.hasPermission(2)) {
            reply(source, "管理员：当前位置 /raid lobby；/raid spawn add <名称>；/raid extract add <名称> <半径> [秒数，默认20]。", false);
            reply(source, "/raid list 查看配置；/raid name <地图名称>；/raid start <玩家>；/raid abort [玩家]。", false);
            reply(source, "/raid duration [分钟] 查看或设置默认对局时长（默认30分钟，下次入场生效）。", false);
            reply(source, "/raid start 统一开局；/raid start <玩家> 登记并开局；/raid stop 停止整局；/raid minplayers [人数] 设置开局人数。", false);
        }
        return 1;
    }

    private static void reply(CommandSourceStack source, String message, boolean broadcast) {
        source.sendSuccess(() -> Component.literal(message), broadcast);
    }
    private static int start(CommandSourceStack source) {
        String error = RaidManager.startError(source.getServer());
        if (error != null) return error(source, error);
        if (!RaidManager.start(source.getServer())) return error(source, "开局失败，等待名单已保留，请检查服务端日志。");
        var match = RaidSavedData.get(source.getServer()).match;
        reply(source, "共享对局已开始：" + match.id + "，成员 " + match.participants().size() + " 人。", true);
        return match.participants().size();
    }
    private static int error(CommandSourceStack source, String message) { source.sendFailure(Component.literal(message)); return 0; }
}

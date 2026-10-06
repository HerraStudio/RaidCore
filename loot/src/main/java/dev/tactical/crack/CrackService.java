package dev.tactical.crack;

import dev.tactical.Tactical;
import dev.tactical.loot.SafeBlock;
import dev.tactical.loot.SafeBlockEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Clients submit an input edge, never an angle, elapsed time, outcome or reward. */
public final class CrackService {
    public static final double RANGE_SQUARED=3.5*3.5;
    private static final Map<UUID,Session> ACTIVE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID,Long> LAST_START=new HashMap<>();
    private static final class Session {
        final UUID id=UUID.randomUUID();
        final ServerPlayer player;
        final SafeBlockEntity safe;
        final Vec3 anchor;
        final int slot;
        final long started=Util.getMillis();
        int sequence=-1;
        long lastInput=-1000;
        Session(ServerPlayer player,SafeBlockEntity safe) {
            this.player=player; this.safe=safe; anchor=player.position(); slot=player.getInventory().selected;
        }
    }
    public static boolean locked(ServerPlayer player) {
        var session=ACTIVE.get(player.getUUID()); return session!=null && session.player==player;
    }
    public static boolean start(ServerPlayer player,BlockPos pos) {
        long now=Util.getMillis();
        if(now-LAST_START.getOrDefault(player.getUUID(),-1000L)<300) return false;
        LAST_START.put(player.getUUID(),now);
        if(!player.isAlive() || player.isSpectator() || player.isPassenger() || locked(player)
                || player.containerMenu!=player.inventoryMenu || player.distanceToSqr(Vec3.atCenterOf(pos))>RANGE_SQUARED
                || !player.serverLevel().hasChunkAt(pos)
                  || !dev.tactical.loot.LootSettings.searchable(player.serverLevel().getBlockState(pos))
                  || !(player.serverLevel().getBlockEntity(pos) instanceof SafeBlockEntity safe)) return false;
        if(safe.getBlockState().getValue(SafeBlock.OPEN) || safe.rewardGenerated()) { safe.requestOpen(player); return false; }
        if(ACTIVE.values().stream().anyMatch(session->session.safe==safe)) { tell(player,"保险箱正在被其他玩家破译"); return false; }
        if(now<safe.retryAfter()) { tell(player,"保险箱设备冷却中"); return false; }
        // Reject requests through a wall, even when a modified client supplies a nearby position.
        var end=Vec3.atCenterOf(pos).add(0,.2,0);
        var obstruction=player.serverLevel().clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(),end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));
        if(obstruction.getType()!=net.minecraft.world.phys.HitResult.Type.MISS && !obstruction.getBlockPos().equals(pos)) return false;
        var session=new Session(player,safe);
        ACTIVE.put(player.getUUID(),session);
        player.stopUsingItem(); player.setSprinting(false); player.setDeltaMovement(Vec3.ZERO);
        safe.hackSeed=player.serverLevel().random.nextLong();
        safe.hackPhaseStart=now+350; safe.hackServerTime=now; safe.hackShakeStart=-1;
        safe.hackSuccesses=safe.hackFailures=safe.hackFeedback=0; safe.hackRevision=0;
        safe.hackFeedbackTime=0; safe.hackSession=session.id.toString(); safe.hackStatus="active";
        send(session,"");
        return true;
    }
    public static void input(ServerPlayer player,CrackPackets.Input packet) {
        var session=ACTIVE.get(player.getUUID());
        if(session==null || session.player!=player || !session.id.equals(packet.session())) return;
        if(packet.cancel()) { finish(session,"aborted","破译已中断"); return; }
        var safe=session.safe; long now=Util.getMillis();
        if(!valid(session) || packet.revision()!=safe.hackRevision || packet.sequence()<=session.sequence
                || now<safe.hackPhaseStart || now-session.lastInput<180) return;
        session.sequence=packet.sequence(); session.lastInput=now;
        long latency=Math.min(75,Math.max(0,player.connection.latency()/2));
        double judgedAt=now-latency;
        double shake=safe.hackShakeStart<0 ? -1 : judgedAt-safe.hackShakeStart;
        double angle=CrackRules.angle(safe.hackSeed,safe.hackSuccesses,judgedAt-safe.hackPhaseStart,shake);
        boolean success=CrackRules.contains(CrackRules.round(safe.hackSeed,safe.hackSuccesses),angle);
        safe.hackRevision++; safe.hackFeedback=success?1:-1; safe.hackFeedbackTime=now;
        if(success) {
            safe.hackSuccesses++;
            player.serverLevel().playSound(null,safe.getBlockPos(),SoundEvents.NOTE_BLOCK_HAT.value(),SoundSource.BLOCKS,.7f,1.6f);
            if(safe.hackSuccesses>=3) {
                reward(session);
                finish(session,"success","破译成功");
                safe.requestOpen(player);
                return;
            }
            safe.hackPhaseStart=now+220; safe.hackShakeStart=-1;
        } else {
            safe.hackFailures++;
            player.serverLevel().playSound(null,safe.getBlockPos(),SoundEvents.GRINDSTONE_USE,SoundSource.BLOCKS,.9f,.6f);
            if(safe.hackFailures>=3) {
                safe.setRetryAfter(now+3000);
                finish(session,"failed","破译失败 · 设备冷却 3 秒");
                return;
            }
        }
        send(session,"");
    }
    private static boolean valid(Session session) {
        var p=session.player;
        return p.isAlive() && !p.isSpectator() && !p.isPassenger() && !session.safe.isRemoved()
                && p.level()==session.safe.getLevel() && p.serverLevel().getBlockEntity(session.safe.getBlockPos())==session.safe
                && p.distanceToSqr(Vec3.atCenterOf(session.safe.getBlockPos()))<=RANGE_SQUARED;
    }
    public static void tick(MinecraftServer server,boolean pre) {
        for(var session:java.util.List.copyOf(ACTIVE.values())) {
            if(session.player.server!=server) continue;
            var p=session.player;
            if(!valid(session) || p.position().distanceToSqr(session.anchor)>.04 || Util.getMillis()-session.started>90000) {
                finish(session,"aborted","离开区域或状态改变，破译中断"); continue;
            }
            p.setDeltaMovement(Vec3.ZERO); p.setSprinting(false); p.getInventory().selected=session.slot;
            p.fallDistance=0;
            if(!pre) {
                if(server.getTickCount()%2==0) send(session,"");
                if(server.getTickCount()%12==0) p.serverLevel().playSound(null,session.safe.getBlockPos(),
                        SoundEvents.PISTON_CONTRACT,SoundSource.BLOCKS,.5f,.65f);
            }
        }
    }
    public static void damage(ServerPlayer player,float amount) {
        var session=ACTIVE.get(player.getUUID()); if(session==null || amount<=0) return;
        session.safe.hackShakeStart=Util.getMillis(); session.safe.hackRevision++;
        if(!player.isAlive() || player.serverLevel().random.nextFloat()<.35f) finish(session,"aborted","受到伤害，破译中断");
        else send(session,"受到伤害 · 稳定设备");
    }
    public static void disconnect(ServerPlayer player) {
        var session=ACTIVE.get(player.getUUID()); if(session!=null) finish(session,"aborted","");
        LAST_START.remove(player.getUUID());
    }
    public static void stop(MinecraftServer server) {
        for(var session:java.util.List.copyOf(ACTIVE.values())) if(session.player.server==server) disconnect(session.player);
        LAST_START.clear();
    }
    private static void finish(Session session,String status,String message) {
        ACTIVE.remove(session.player.getUUID(),session);
        session.safe.hackStatus=status;
        session.safe.hackServerTime=Util.getMillis();
        send(session,message);
        session.player.setDeltaMovement(Vec3.ZERO);
    }
    private static void send(Session session,String notice) {
        session.safe.hackServerTime=Util.getMillis();
        session.safe.sync(false);
        PacketDistributor.sendToPlayer(session.player,new CrackPackets.State(session.safe.getBlockPos(),
                session.safe.serializeInitialData(session.player.registryAccess()),notice));
    }
    private static void reward(Session session) {
        var safe=session.safe;
        if(safe.rewardGenerated()) return;
        safe.markRewardGenerated();
        if(dev.tactical.loot.LootService.safeReward(session.player,safe)) return;
        var key=ResourceKey.create(Registries.LOOT_TABLE,ResourceLocation.fromNamespaceAndPath("tactical_inventory","chests/safe_decrypted"));
        var table=session.player.server.reloadableRegistries().getLootTable(key);
        var params=new LootParams.Builder(session.player.serverLevel())
                .withParameter(LootContextParams.ORIGIN,Vec3.atCenterOf(safe.getBlockPos()))
                .withOptionalParameter(LootContextParams.THIS_ENTITY,session.player).create(LootContextParamSets.CHEST);
        for(var stack:table.getRandomItems(params)) safe.enqueueReward(stack);
    }
    private static void tell(ServerPlayer player,String message) { player.displayClientMessage(Component.literal(message),true); }
    private CrackService() {}
}

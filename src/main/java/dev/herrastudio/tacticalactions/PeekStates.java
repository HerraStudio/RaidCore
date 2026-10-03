package dev.herrastudio.tacticalactions;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Client and server entities have separate state, including in an integrated server. */
public final class PeekStates {
    // Entity.equals compares numeric entity IDs: integrated-server and client players
    // share those IDs. Keying by level first keeps their animation clocks separate.
    private static final Map<Level, Map<UUID, State>> STATES = new WeakHashMap<>();
    private PeekStates() {}

    public static synchronized void tick(Player player, float target) {
        State state = state(player, true);
        state.remote = false;
        state.transition.tick(target);
    }

    public static synchronized float leanAt(Player player, float partialTick) {
        if (player == null || !canPeek(player)) return 0;
        State state = state(player, false);
        if (state == null) return 0;
        return state.remote ? Mth.lerp(Mth.clamp(partialTick, 0, 1), state.previous, state.current)
                : state.transition.get(partialTick);
    }

    public static float current(Player player) { return leanAt(player, 1); }

    /** GD656's lateral-offset curve, normalized independently from its angle curve. */
    public static synchronized float offsetAt(Player player, float partialTick) {
        if (player == null || !canPeek(player)) return 0;
        State state = state(player, false);
        if (state == null) return 0;
        return state.remote ? Mth.lerp(Mth.clamp(partialTick, 0, 1), state.previousOffset, state.currentOffset)
                : state.transition.offset(partialTick);
    }

    public static synchronized void settleRemote(Player player) {
        State state = state(player, false);
        if (state != null && state.remote) {
            state.previous = state.current;
            state.previousOffset = state.currentOffset;
        }
    }

    public static synchronized void accept(Player player, float previous, float current,
                                            float previousOffset, float currentOffset) {
        if (!Float.isFinite(previous) || !Float.isFinite(current)
                || !Float.isFinite(previousOffset) || !Float.isFinite(currentOffset)) return;
        State state = state(player, true);
        state.remote = true;
        state.previous = Mth.clamp(previous, -1, 1);
        state.current = Mth.clamp(current, -1, 1);
        state.previousOffset = Mth.clamp(previousOffset, -1, 1);
        state.currentOffset = Mth.clamp(currentOffset, -1, 1);
    }

    public static synchronized void reset(Player player) {
        Map<UUID, State> levelStates = STATES.get(player.level());
        if (levelStates != null) levelStates.remove(player.getUUID());
    }

    private static State state(Player player, boolean create) {
        Map<UUID, State> levelStates = create ? STATES.computeIfAbsent(player.level(), ignored -> new HashMap<>())
                : STATES.get(player.level());
        if (levelStates == null) return null;
        return create ? levelStates.computeIfAbsent(player.getUUID(), ignored -> new State()) : levelStates.get(player.getUUID());
    }

    public static boolean canPeek(Player player) {
        return player.isAlive() && !player.isSpectator() && !player.isPassenger()
                && !player.isSleeping() && !player.isFallFlying() && !player.getAbilities().flying
                && !player.isInWater() && !player.isInLava() && !player.isSwimming();
    }

    private static final class State {
        final PeekTransition transition = new PeekTransition();
        boolean remote;
        float previous, current, previousOffset, currentOffset;
    }
}

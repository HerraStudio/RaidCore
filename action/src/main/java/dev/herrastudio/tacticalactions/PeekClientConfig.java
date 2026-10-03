package dev.herrastudio.tacticalactions;

import net.neoforged.neoforge.common.ModConfigSpec;

/** GD656-style optional automatic peeking; manual Q/E remains the default input. */
public final class PeekClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue TOGGLE_INPUT = BUILDER
            .comment("false: hold Q/E; true: tap the same direction again to return.")
            .define("toggleInput", false);
    public static final ModConfigSpec.BooleanValue AUTO_CROUCH = BUILDER
            .comment("Automatically peek around nearby cover while crouching.")
            .define("autoPeekWhileCrouching", false);
    public static final ModConfigSpec.BooleanValue AUTO_AIM = BUILDER
            .comment("Automatically peek around nearby cover while aiming a GWO gun.")
            .define("autoPeekWhileAiming", true);
    public static final ModConfigSpec.BooleanValue AUTO_STAND = BUILDER
            .comment("Automatically peek while standing without aiming.")
            .define("autoPeekWhileStanding", false);
    public static final ModConfigSpec SPEC = BUILDER.build();
    private PeekClientConfig() {}
}

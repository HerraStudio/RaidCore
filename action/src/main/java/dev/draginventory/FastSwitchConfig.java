package dev.draginventory;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Physical-client tuning; weapon mechanics and server cadence keep their native rules. */
public final class FastSwitchConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.DoubleValue SPEED;

    static {
        var builder = new ModConfigSpec.Builder();
        SPEED = builder.comment("GWO draw/holster playback speed. 1 = original, 2 = twice as fast.",
                        "Only switching animations and their matching readiness timers are adjusted.")
                .defineInRange("switchSpeed", 2.0, 1.0, 6.0);
        SPEC = builder.build();
    }

    private FastSwitchConfig() {}

    public static double multiplier() {
        return SPEC.isLoaded() ? SPEED.get() : 2.0;
    }
}

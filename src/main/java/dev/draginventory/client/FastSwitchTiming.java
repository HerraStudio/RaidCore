package dev.draginventory.client;

/** Shared timing rules for the switching pose, task clock and visual playback. */
public final class FastSwitchTiming {
    private FastSwitchTiming() {}

    public static boolean isSwitchState(String state) {
        if (state == null) return false;
        return state.equals("raise") || state.startsWith("raise_")
                || state.equals("drop") || state.startsWith("drop_")
                || state.equals("draw") || state.startsWith("draw_")
                || state.equals("holster") || state.startsWith("holster_");
    }

    public static boolean isOwnedSwitch(String logicalState, String owner) {
        if (owner != null && !owner.isBlank()) return owner.startsWith("raise#") || owner.startsWith("drop#");
        return isSwitchState(logicalState);
    }

    public static float duration(float clipSeconds, float clipStart, float clipEnd, float authoredSpeed, double multiplier) {
        float clip = Float.isFinite(clipSeconds) ? Math.max(0, clipSeconds) : 0;
        float start = Float.isFinite(clipStart) ? Math.clamp(clipStart, 0, clip) : 0;
        float end = Float.isFinite(clipEnd) && clipEnd >= 0 ? Math.min(clip, clipEnd) : clip;
        double speed = Float.isFinite(authoredSpeed) ? Math.max(0.001, authoredSpeed) : 1;
        double factor = Double.isFinite(multiplier) ? Math.max(1, multiplier) : 1;
        // Match DrawPlan's existing minimum; no hidden tail after the visible clip window.
        return (float) Math.max(0.05, Math.max(0, end - start) / speed / factor);
    }
}

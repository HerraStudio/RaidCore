package dev.draginventory.client;

import dev.draginventory.FastSwitchConfig;

/** Optional GWO access without linking its classes into a non-GWO installation. */
public final class GwoSwitchTimingBridge {
    private GwoSwitchTimingBridge() {}

    public static float adjust(String state, Object definition, float originalSeconds) {
        String action = FastSwitchActionScope.current();
        if (!("raise".equals(action) || "drop".equals(action)))
            return originalSeconds;
        double factor = FastSwitchConfig.multiplier();
        // A factor of one is the exact original behavior, including authored timings.
        if (factor <= 1) return originalSeconds;
        try {
            Object channel = definition == null ? null : channel(definition, state);
            float authoredSpeed = channel == null ? 1 : Math.max(0.001f, number(channel, "speed"));
            float start = channel == null ? 0 : number(channel, "clipStartTime");
            float end = channel == null ? -1 : number(channel, "clipEndTime");
            return FastSwitchTiming.duration(originalSeconds * authoredSpeed, start, end, authoredSpeed, factor);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported GWO switching animation timing API", failure);
        }
    }

    private static Object channel(Object definition, String state) throws ReflectiveOperationException {
        Object controller = definition.getClass().getMethod("animationController").invoke(definition);
        return controller.getClass().getMethod("channel", String.class).invoke(controller, state);
    }

    private static float number(Object object, String method) throws ReflectiveOperationException {
        return ((Number) object.getClass().getMethod(method).invoke(object)).floatValue();
    }
}

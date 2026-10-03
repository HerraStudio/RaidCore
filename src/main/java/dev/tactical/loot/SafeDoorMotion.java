package dev.tactical.loot;

/** One-way lid movement. Open safes never reset when their loot screen closes. */
public final class SafeDoorMotion {
    public static final int DURATION = 16;
    public static final float OPEN_ANGLE = 60;
    private int previous, ticks;

    public SafeDoorMotion(boolean alreadyOpen) {
        previous = ticks = alreadyOpen ? DURATION : 0;
    }
    public void tick(boolean open) {
        previous = ticks;
        if(open && ticks < DURATION) ticks++;
    }
    public float angle(float partialTick) {
        float progress = progress(partialTick);
        return OPEN_ANGLE * (1 - (1-progress)*(1-progress)*(1-progress));
    }
    public float progress(float partialTick) {
        float partial = Math.max(0, Math.min(1, partialTick));
        return (previous + (ticks-previous)*partial) / DURATION;
    }
    public boolean finished() { return ticks >= DURATION; }
}

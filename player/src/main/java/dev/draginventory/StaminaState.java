package dev.draginventory;

public final class StaminaState {
    public static final float MAXIMUM = 100;
    public static final float SPRINT_PER_TICK = 0.6f;
    public static final float JUMP_COST = 10;
    public static final float RECOVERY_PER_TICK = 0.8f;
    public static final int RECOVERY_DELAY = 40;
    public static final float LOW_FRACTION = 0.2f;
    private float value;
    private int recoveryTicks;
    private double previousX;
    private double previousZ;
    private boolean positionKnown;

    public StaminaState() {
        this(MAXIMUM);
    }

    public StaminaState(float value) {
        this.value = Float.isFinite(value) ? Math.clamp(value, 0, MAXIMUM) : MAXIMUM;
        recoveryTicks = this.value < MAXIMUM ? RECOVERY_DELAY : 0;
    }

    public float value() {
        return value;
    }

    public boolean canSprint(boolean alreadySprinting) {
        return value > 0 && (alreadySprinting || value / MAXIMUM > LOW_FRACTION);
    }

    public boolean canJump() {
        return value > 0;
    }

    public boolean moved(double positionX, double positionZ) {
        double distanceSquared = Math.pow(positionX - previousX, 2) + Math.pow(positionZ - previousZ, 2);
        boolean moved = positionKnown && distanceSquared > 0.0001 && distanceSquared < 16;
        previousX = positionX;
        previousZ = positionZ;
        positionKnown = true;
        return moved;
    }

    public void tick(boolean sprinting) {
        if (sprinting && value > 0) {
            consume(SPRINT_PER_TICK);
        } else if (recoveryTicks > 0) {
            recoveryTicks--;
        } else {
            value = Math.min(MAXIMUM, value + RECOVERY_PER_TICK);
        }
    }

    public boolean jump() {
        if (!canJump()) return false;
        consume(JUMP_COST);
        return true;
    }

    private void consume(float amount) {
        value = Math.max(0, value - amount);
        recoveryTicks = RECOVERY_DELAY;
    }
}

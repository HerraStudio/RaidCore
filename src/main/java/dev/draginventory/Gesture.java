package dev.draginventory;

/** Input timing only: never changes game stacks or sends packets. */
public final class Gesture {
    public static final long HOLD_MS = 180;
    public static final long DOUBLE_CLICK_MS = 250;
    private Object lastSlot;
    private long lastPress;
    private Object pressedSlot;
    private long pressedAt;
    private double startX, startY;
    private boolean active, dragging, quickMove;

    public boolean press(Object slot, double x, double y, long now, boolean canQuickMove) {
        quickMove = canQuickMove && slot != null && slot == lastSlot
                && now >= lastPress && now - lastPress < DOUBLE_CLICK_MS;
        lastSlot = null;
        active = true;
        dragging = false;
        pressedSlot = slot;
        pressedAt = now;
        startX = x;
        startY = y;
        return quickMove;
    }
    public boolean update(double x, double y, long now) {
        if (!active || quickMove || dragging) return false;
        double dx = x - startX, dy = y - startY;
        if (now - pressedAt >= HOLD_MS || dx * dx + dy * dy >= 16) {
            dragging = true;
            lastSlot = null;
            return true;
        }
        return false;
    }
    public void release(Object slot, long now) {
        if (active && !dragging && !quickMove && slot != null && slot == pressedSlot
                && now - pressedAt < HOLD_MS) {
            lastSlot = slot;
            lastPress = pressedAt;
        } else lastSlot = null;
        active = false;
        dragging = false;
        quickMove = false;
        pressedSlot = null;
    }
    public void reset() {
        active = dragging = quickMove = false;
        lastSlot = pressedSlot = null;
    }
    public boolean active() { return active; }
    public boolean dragging() { return dragging; }
    public boolean quickMove() { return quickMove; }
}

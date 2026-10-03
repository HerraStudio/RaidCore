package dev.draginventory.client.wheel;

/** A 50 ms hold opens once; interrupted presses wait for a fresh press. */
public final class WheelHoldGesture {
    public static final long HOLD_MS = 50;
    private boolean wasDown;
    private boolean blocked;
    private boolean armed;
    private long started;

    public boolean update(boolean down, boolean eligible, long now) {
        if (!down) {
            reset();
            return false;
        }
        if (!eligible) {
            blocked = true;
            armed = false;
        } else if (!wasDown && !blocked) {
            armed = true;
            started = now;
        }
        wasDown = true;
        // A clock adjustment must not make an in-progress press open early.
        if (armed && now < started) started = now;
        if (!blocked && armed && now - started >= HOLD_MS) {
            blocked = true;
            armed = false;
            return true;
        }
        return false;
    }

    public void suspend(boolean down) {
        if (!down) reset();
        else {
            wasDown = true;
            blocked = true;
            armed = false;
        }
    }

    public void reset() {
        wasDown = false;
        blocked = false;
        armed = false;
        started = 0;
    }
}

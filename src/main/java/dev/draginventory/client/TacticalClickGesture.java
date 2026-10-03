package dev.draginventory.client;

import java.util.function.BiConsumer;

/** Commits a press immediately; only the upgrade window is retained. */
final class TacticalClickGesture<T> {
    static final long DOUBLE_CLICK_MS = 450;
    private long pressedAt;
    private boolean waiting;

    void press(T hit, long now, BiConsumer<T, Boolean> emit) {
        if (waiting && now >= pressedAt && now - pressedAt <= DOUBLE_CLICK_MS) {
            cancel();
            emit.accept(hit, true);
        } else {
            pressedAt = now;
            waiting = true;
            emit.accept(hit, false);
        }
    }

    void flush(long now) {
        if (waiting && now - pressedAt > DOUBLE_CLICK_MS) {
            cancel();
        }
    }

    boolean waiting() { return waiting; }
    void cancel() { waiting = false; }
}

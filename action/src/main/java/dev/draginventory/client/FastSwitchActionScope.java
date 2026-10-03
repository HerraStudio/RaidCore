package dev.draginventory.client;

import java.util.ArrayDeque;

/** A short synchronous planning scope makes custom/shared animation names unambiguous. */
public final class FastSwitchActionScope implements AutoCloseable {
    private static final ThreadLocal<ArrayDeque<String>> ACTIONS = ThreadLocal.withInitial(ArrayDeque::new);
    private boolean closed;

    private FastSwitchActionScope(String action) { ACTIONS.get().push(action == null ? "" : action); }
    public static FastSwitchActionScope enter(String action) { return new FastSwitchActionScope(action); }
    public static String current() { return ACTIONS.get().peek(); }
    @Override public void close() {
        if (!closed) {
            closed = true;
            var stack = ACTIONS.get();
            stack.pop();
            if (stack.isEmpty()) ACTIONS.remove();
        }
    }
}

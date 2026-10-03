package dev.herrastudio.tacticalactions;

/** Pure input state resolver, kept separate so the client tick code is easy to verify. */
public enum TacticalActionState {
    STANDING(null),
    LEAN_LEFT("lean_left"),
    LEAN_RIGHT("lean_right"),
    PRONE("prone");

    private final String animation;

    TacticalActionState(String animation) {
        this.animation = animation;
    }

    public String animationName() {
        return animation;
    }

    public static TacticalActionState resolve(boolean prone, boolean leftDown, boolean rightDown) {
        if (prone) return PRONE;
        if (leftDown == rightDown) return STANDING;
        return leftDown ? LEAN_LEFT : LEAN_RIGHT;
    }
}

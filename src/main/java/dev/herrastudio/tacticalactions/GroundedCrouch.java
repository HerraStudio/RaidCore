package dev.herrastudio.tacticalactions;

/** One instance per local player; a crouch-jump spends one tick standing before takeoff. */
public final class GroundedCrouch {
    private boolean queuedJump;
    private boolean blockedUntilRelease;

    public Result update(boolean supported, boolean canStand, boolean crouched,
                         boolean sneakHeld, boolean jumpHeld) {
        if (!supported) {
            queuedJump = false;
            blockedUntilRelease = true;
            return new Result(false, jumpHeld);
        }
        // Only a release on the ground rearms crouch. An airborne press/hold is
        // never a request to crouch on landing, including toggle-sneak input.
        if (!sneakHeld) blockedUntilRelease = false;
        if (queuedJump) {
            queuedJump = false;
            blockedUntilRelease = true;
            return new Result(false, canStand);
        }
        boolean crouchRequested = sneakHeld && !blockedUntilRelease;
        if (jumpHeld && (crouched || crouchRequested)) {
            if (!canStand) return new Result(crouchRequested, false);
            queuedJump = true;
            blockedUntilRelease = true;
            return new Result(false, false);
        }
        if (jumpHeld) blockedUntilRelease = true;
        return new Result(crouchRequested && !jumpHeld, jumpHeld);
    }

    public void leaveGround() { queuedJump = false; blockedUntilRelease = true; }
    public void reset() { leaveGround(); }
    public record Result(boolean crouch, boolean jump) {}
}

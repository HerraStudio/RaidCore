package dev.draginventory;

import java.util.Arrays;

/** Rules for the four weapon slots shown by the GWO HUD. */
public final class WeaponSlots {
    public static final int FIRST = 0;
    public static final int LAST = 3;
    public static final int FULL = (1 << (LAST + 1)) - 1;
    private static final String[] ROLES = {"primary", "primary", "pistol", "melee"};

    private WeaponSlots() {}

    public static boolean isWeaponSlot(int slot) {
        return slot >= FIRST && slot <= LAST;
    }

    /** Skip empty physical slots; a single or empty weapon group leaves selection unchanged. */
    public static int next(int current, double scrollAmount, int occupied) {
        int direction = Integer.signum((int) Math.signum(scrollAmount));
        if (direction == 0) return current;
        int start = isWeaponSlot(current) ? current : direction > 0 ? FIRST : LAST;
        for (int step = 1; step <= LAST + 1; step++) {
            int target = Math.floorMod(start - direction * step, LAST + 1);
            if ((occupied & (1 << target)) != 0) return target;
        }
        return current;
    }

    public static int[] candidates(int selected, int occupied) {
        if (!isWeaponSlot(selected)) return new int[0];
        int[] result = new int[3];
        int index = 0;
        for (int slot = FIRST; slot <= LAST; slot++) {
            if (slot != selected && (occupied & (1 << slot)) != 0) result[index++] = slot;
        }
        return Arrays.copyOf(result, index);
    }

    /** Zero-based hotbar binding for an occupied physical slot, compacted across gaps. */
    public static int bindingIndex(int slot, int occupied) {
        if (!isWeaponSlot(slot) || (occupied & (1 << slot)) == 0) return -1;
        return Integer.bitCount(occupied & ((1 << slot) - 1));
    }

    public static int slotForBinding(int binding, int occupied) {
        if (!isWeaponSlot(binding)) return -1;
        for (int slot = FIRST; slot <= LAST; slot++) {
            if ((occupied & (1 << slot)) != 0 && binding-- == 0) return slot;
        }
        return -1;
    }

    public static String role(int slot) {
        return isWeaponSlot(slot) ? ROLES[slot] : "other";
    }

    public static int[] all() {
        return Arrays.copyOf(new int[] {FIRST, 1, 2, LAST}, 4);
    }
}

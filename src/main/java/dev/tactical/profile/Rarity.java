package dev.tactical.profile;

/** Tactical rarity is independent of vanilla enchantment/name formatting. */
public enum Rarity {
    COMMON("普通",0xFFFFFF), FINE("精良",0x4CAF50), RARE("稀有",0x2196F3),
    EPIC("史诗",0x9C27B0), LEGENDARY("传说",0xFFC107);
    public final String label;
    public final int rgb;
    Rarity(String label,int rgb) { this.label=label; this.rgb=rgb; }
    public int color(int alpha) { return (Math.max(0,Math.min(255,alpha))<<24)|rgb; }
    public static Rarity parse(String value) { return valueOf(value); }
}

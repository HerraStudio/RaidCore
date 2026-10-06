package dev.tactical.loot;

import dev.tactical.profile.ItemProfile;

/** A rule applies to a registered block type; names are plain display text. */
public record LootRule(String block,String name,double multiplier,boolean enabled) {
    public static final int MAX_RULES=2048,MAX_NAME=96;
    public static final double MAX_MULTIPLIER=100;
    public LootRule {
        new ItemProfile.Key(block,"");
        if(name==null || name.length()>MAX_NAME || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("容器名称最多 96 字，不能包含控制字符");
        name=name.trim();
        if(!Double.isFinite(multiplier) || multiplier<0 || multiplier>MAX_MULTIPLIER)
            throw new IllegalArgumentException("容器系数必须为 0–100");
    }
    public double probability(double base) {
        if(!Double.isFinite(base) || base<0 || base>1) throw new IllegalArgumentException("无效的基础爆率");
        return enabled?Math.min(1,base*multiplier):0;
    }
}

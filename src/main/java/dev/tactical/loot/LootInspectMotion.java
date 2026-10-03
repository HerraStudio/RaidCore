package dev.tactical.loot;

/** Only selects Blender-authored clips; interpolation is performed by GWO's animation sampler. */
public final class LootInspectMotion {
    public record Sample(String clip,float seconds) {}
    private long equippedAt,inspectAt=-1;
    private boolean active;
    public void equip(long now) { equippedAt=now; inspectAt=-1; active=true; }
    public boolean inspect(long now,float drawLength,float inspectLength) {
        if(!active || now-equippedAt<drawLength*1000 || inspectAt>=0 && now-inspectAt<inspectLength*1000) return false;
        inspectAt=now; return true;
    }
    public Sample sample(long now,float drawLength,float idleLength,float inspectLength) {
        if(!active) return new Sample("idle",0);
        if(inspectAt>=0 && now-inspectAt<inspectLength*1000) return new Sample("inspect",Math.max(0,(now-inspectAt)/1000f));
        if(inspectAt>=0) { equippedAt=inspectAt+(long)(inspectLength*1000)-(long)(drawLength*1000); inspectAt=-1; }
        float elapsed=Math.max(0,(now-equippedAt)/1000f);
        if(elapsed<drawLength) return new Sample("draw",elapsed);
        return new Sample("idle",idleLength<=0?0:(elapsed-drawLength)%idleLength);
    }
    public boolean active() { return active; }
    public void cancel() { active=false; inspectAt=-1; }
}

package dev.tactical.crack;

import java.util.SplittableRandom;

/** The same deterministic clock and arc math is used for presentation and server judgement. */
public final class CrackRules {
    public record Round(double center,double width,double periodMillis) {}
    public static Round round(long seed,int successes) {
        int stage=Math.max(0,Math.min(2,successes));
        var random=new SplittableRandom(seed ^ (0x9e3779b97f4a7c15L*(stage+1)));
        return new Round(70+random.nextDouble()*240,60-stage*15,
                new double[]{1500,1320,1160}[stage]*(.96+random.nextDouble()*.08));
    }
    public static double angle(long seed,int successes,double elapsedMillis,double shakeMillis) {
        double angle=Math.max(0,elapsedMillis)*360/round(seed,successes).periodMillis();
        if(shakeMillis>=0 && shakeMillis<650) {
            double fade=1-shakeMillis/650;
            angle+=fade*(19*Math.sin(shakeMillis*.039)+11*Math.sin(shakeMillis*.071));
        }
        return normalize(angle);
    }
    public static boolean contains(Round round,double angle) {
        return Math.abs(normalize(angle-round.center()+180)-180)<=round.width()/2;
    }
    public static double normalize(double angle) { return (angle%360+360)%360; }
    private CrackRules() {}
}

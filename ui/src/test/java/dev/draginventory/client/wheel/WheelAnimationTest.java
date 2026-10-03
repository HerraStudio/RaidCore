package dev.draginventory.client.wheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WheelAnimationTest {
    @Test void fadesAndScalesWithoutEntranceRotationAndThenFinishesClosing() {
        var animation = new WheelAnimation();
        assertFalse(animation.isVisible(0));
        assertEquals(-1, animation.hovered());
        animation.open(1000);
        var start = animation.frame(1000);
        assertTrue(animation.isOpen());
        assertTrue(animation.isVisible(1000));
        assertEquals(0, start.alpha());
        assertEquals(0.86f, start.scale());
        assertEquals(0, start.rotation());
        var midway = animation.frame(1090);
        assertTrue(midway.alpha() > 0.5f && midway.alpha() < 1);
        assertTrue(midway.scale() > start.scale() && midway.scale() < 1);
        assertEquals(0, midway.rotation());
        var end = animation.frame(1180);
        assertEquals(1, end.alpha());
        assertEquals(1, end.scale());
        assertEquals(0, end.rotation());
        animation.close(1200);
        assertFalse(animation.isOpen());
        assertTrue(animation.isClosing());
        assertTrue(animation.isVisible(1349));
        assertFalse(animation.isVisible(1350));
        assertFalse(animation.isClosing());
        assertEquals(0, animation.frame(1350).alpha());
        assertEquals(0.86f, animation.frame(1350).scale());
        assertEquals(0, animation.frame(1350).rotation());
    }

    @Test void openingAndClosingNeverRotateAtAnyIntermediateTime() {
        var animation = new WheelAnimation();
        animation.open(100);
        for (long time = 100; time <= 400; time++) assertEquals(0, animation.frame(time).rotation());
        animation.close(400);
        for (long time = 400; time <= 700; time++) assertEquals(0, animation.frame(time).rotation());
    }

    @Test void scrollSpinsClockwiseAndCounterclockwiseByOneSectorPerStep() {
        var animation = new WheelAnimation();
        animation.open(0);
        animation.hover(2, 0);
        animation.spin(1, 200);
        assertEquals(0, animation.frame(200).rotation());
        assertTrue(animation.frame(240).rotation() > 0);
        assertEquals(Math.PI * 2 / 5, animation.frame(380).rotation(), 0.000001);
        assertEquals(2, animation.hovered());
        assertEquals(1, animation.frame(380).highlights()[2]);
        animation.spin(-2, 400);
        assertEquals(Math.PI * 2 / 5, animation.frame(400).rotation(), 0.000001);
        assertEquals(-Math.PI * 2 / 5, animation.frame(580).rotation(), 0.000001);
        animation.spin(5, 600);
        assertEquals(Math.PI * 2 * 4 / 5, animation.frame(780).rotation(), 0.000001);
    }

    @Test void rapidScrollRetargetsWithoutJumpAndKeepsEveryPendingStepIncludingReversals() {
        var animation = new WheelAnimation();
        animation.open(0);
        animation.spin(1, 100);
        var before = animation.frame(120);
        animation.spin(2, 120);
        assertSamePose(before, animation.frame(120));
        assertEquals(Math.PI * 2 * 3 / 5, animation.frame(300).rotation(), 0.000001);
        animation.spin(-1, 310);
        before = animation.frame(330);
        animation.spin(1, 330);
        assertSamePose(before, animation.frame(330));
        assertEquals(Math.PI * 2 * 3 / 5, animation.frame(510).rotation(), 0.000001);
    }

    @Test void closingFreezesSpinAndReopeningPreservesItsPoseUntilANewOpening() {
        var animation = new WheelAnimation();
        animation.open(0);
        animation.spin(2, 200);
        float pose = animation.frame(230).rotation();
        animation.close(230);
        assertEquals(pose, animation.frame(250).rotation());
        animation.spin(1, 250);
        assertEquals(pose, animation.frame(260).rotation());
        animation.open(260);
        assertEquals(pose, animation.frame(260).rotation());
        assertEquals(pose, animation.frame(600).rotation());
        animation.spin(1, 600);
        assertEquals(pose + Math.PI * 2 / 5, animation.frame(780).rotation(), 0.000001);
        animation.close(800);
        animation.open(1000);
        assertEquals(0, animation.frame(1000).rotation());
        assertEquals(0, animation.frame(1180).rotation());
    }

    @Test void spinIsIndependentOfHowManyFramesAreRenderedAndIgnoresClosedOrZeroSteps() {
        var frequent = new WheelAnimation();
        var skipped = new WheelAnimation();
        frequent.spin(2, 0);
        skipped.spin(-2, 0);
        frequent.open(0);
        skipped.open(0);
        frequent.spin(1, 100);
        skipped.spin(1, 100);
        for (long time = 100; time < 140; time++) frequent.frame(time);
        frequent.spin(2, 140);
        skipped.spin(2, 140);
        frequent.spin(0, 160);
        for (long time = 140; time < 320; time++) frequent.frame(time);
        assertSamePose(frequent.frame(320), skipped.frame(320));
        assertEquals(Math.PI * 2 * 3 / 5, frequent.frame(320).rotation(), 0.000001);
    }

    @Test void reopeningAnInterruptedExitDoesNotJumpOrDiscardTheCurrentSelectionPose() {
        var animation = new WheelAnimation();
        animation.open(0);
        animation.hover(2, 100);
        animation.close(200);
        var before = animation.frame(260);
        animation.open(260);
        assertSamePose(before, animation.frame(260));
        assertTrue(animation.isOpen());
        assertFalse(animation.isClosing());
        assertEquals(1, animation.frame(440).alpha());
        assertEquals(1, animation.frame(440).scale());
        assertEquals(0, animation.frame(440).rotation());
    }

    @Test void fastHoverRetargetsCurrentHighlightsAndMarkerWithNoVisualJump() {
        var animation = new WheelAnimation();
        animation.open(0);
        animation.hover(0, 100);
        var before = animation.frame(140);
        animation.hover(4, 140);
        assertSamePose(before, animation.frame(140));
        var middle = animation.frame(180);
        assertTrue(middle.highlightAngle() < before.highlightAngle());
        assertTrue(middle.highlights()[0] < before.highlights()[0]);
        assertTrue(middle.highlights()[4] > 0);
        var nextBefore = animation.frame(190);
        animation.hover(1, 190);
        assertSamePose(nextBefore, animation.frame(190));
        assertEquals(1, animation.frame(340).highlights()[1]);
        assertEquals(0, animation.frame(340).highlights()[0]);
        assertEquals(0, animation.frame(340).highlights()[4]);
    }

    @Test void centerCancelsHoverAndSelectionPulseReturnsToRest() {
        var animation = new WheelAnimation();
        animation.open(0);
        animation.hover(3, 10);
        assertEquals(1, animation.frame(65).selectionPulse());
        assertEquals(0, animation.frame(190).selectionPulse());
        animation.hover(-1, 200);
        assertEquals(-1, animation.hovered());
        assertEquals(0, animation.frame(350).highlights()[3]);
        animation.close(400);
        animation.open(600);
        assertEquals(-1, animation.hovered());
        assertArrayEquals(new float[5], animation.frame(600).highlights());
    }

    @Test void animationIsIndependentOfRenderFrameRateAndRepeatedOpenRequests() {
        var frequent = new WheelAnimation();
        var skipped = new WheelAnimation();
        frequent.open(0);
        skipped.open(0);
        frequent.hover(0, 20);
        skipped.hover(0, 20);
        for (int time = 20; time < 100; time++) frequent.frame(time);
        frequent.open(100);
        frequent.hover(2, 100);
        skipped.hover(2, 100);
        for (int time = 100; time < 200; time++) frequent.frame(time);
        assertSamePose(frequent.frame(200), skipped.frame(200));
        frequent.close(220);
        skipped.close(220);
        for (int time = 220; time < 280; time++) frequent.frame(time);
        frequent.open(280);
        skipped.open(280);
        assertSamePose(frequent.frame(340), skipped.frame(340));
    }

    @Test void framesOwnDefensiveHighlightSnapshots() {
        var animation = new WheelAnimation();
        animation.open(0);
        animation.hover(2, 0);
        var frame = animation.frame(150);
        frame.highlights()[2] = 0;
        assertEquals(1, frame.highlights()[2]);
        assertEquals(1, animation.frame(150).highlights()[2]);
        float[] source = {1, 2, 3, 4, 5};
        var externalFrame = new WheelAnimation.Frame(1, 1, 0, source, 0, 0);
        source[0] = 0;
        assertEquals(1, externalFrame.highlights()[0]);
    }

    private static void assertSamePose(WheelAnimation.Frame expected, WheelAnimation.Frame actual) {
        assertEquals(expected.alpha(), actual.alpha(), 0.000001f);
        assertEquals(expected.scale(), actual.scale(), 0.000001f);
        assertEquals(expected.rotation(), actual.rotation(), 0.000001f);
        assertArrayEquals(expected.highlights(), actual.highlights(), 0.000001f);
        assertEquals(expected.highlightAngle(), actual.highlightAngle(), 0.000001f);
        assertEquals(expected.selectionPulse(), actual.selectionPulse(), 0.000001f);
    }
}

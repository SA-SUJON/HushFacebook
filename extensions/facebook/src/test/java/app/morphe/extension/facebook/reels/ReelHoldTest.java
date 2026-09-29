/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hold a reel for 2x: while the switch is on, a long press on a reel goes to Facebook's own
 * speed-up wherever it lands, every reel gets the release listener, and that listener puts the
 * speed back from the first lift it hears after a hold, never after a tap once it has. Off, paused
 * or before the settings are ready, every answer is Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        ReelHold.forget();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HOLD_REEL_FOR_2X.resetToDefault();
        ReelHold.forget();
        HookStatus.clear();
    }

    private static void finger(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 100, 200, 0);
        ReelHold.touch(event);
        event.recycle();
    }

    /** A hold as Facebook's long-press handler makes it: the flag asked, then the speed-up. */
    private static void hold() {
        ReelHold.longPress(false);
        ReelHold.held();
    }

    /** A tap on a reel: the release listener hears the finger land and lift. True when either answer was yes. */
    private static boolean tapReleases() {
        finger(MotionEvent.ACTION_DOWN);
        boolean down = ReelHold.release(false);
        finger(MotionEvent.ACTION_UP);
        return ReelHold.release(false) || down;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.HOLD_REEL_FOR_2X + ":")) return line;
        }
        return null;
    }

    @Test
    public void aHoldAnywhereOnAReelGoesToTheSpeedUpAndTheLiftPutsTheSpeedBack() {
        assertTrue("the switch starts off", Settings.HOLD_REEL_FOR_2X.get());
        assertTrue("a reel got no release listener", ReelHold.speedUp(false));
        finger(MotionEvent.ACTION_DOWN);
        assertFalse("the listener put a speed back before the hold", ReelHold.release(false));
        assertTrue("the long press went to Facebook's menu", ReelHold.longPress(false));
        assertTrue("a hold in the middle of the reel didn't count", ReelHold.anywhere(false));
        ReelHold.held();
        finger(MotionEvent.ACTION_MOVE);
        assertTrue("the listener didn't hear the hold's slide", ReelHold.release(false));
        finger(MotionEvent.ACTION_UP);
        assertTrue("the lift didn't put the speed back", ReelHold.release(false));
        assertEquals(FamilyNames.HOLD_REEL_FOR_2X + ": invoked 7, 5 found, 0 missing. Counted: "
                + ReelHold.HELD + " 1", statusLine());
    }

    /**
     * The listener hears every touch, and with the flags on it would put back the speed the reel had
     * before its last hold. Once the hold's lift has put the speed back, a tap doesn't.
     */
    @Test
    public void aTapAfterAHoldLeavesTheSpeedAlone() {
        finger(MotionEvent.ACTION_DOWN);
        hold();
        finger(MotionEvent.ACTION_UP);
        assertTrue(ReelHold.release(false));
        assertFalse("a tap after the hold put a speed back", tapReleases());
        assertFalse("a second tap put a speed back", tapReleases());
    }

    /**
     * The release listener reads whether a hold is on as it stood when the reel was last drawn, and
     * the speed-up has the reel drawn again. A lift before that reaches a listener that doesn't put
     * the speed back, or none at all, and the reel would stay at 2x. The hold lasts until a lift is
     * heard, so the next gesture's lift puts the speed back, and a tap after that is a plain tap.
     */
    @Test
    public void aLiftNoListenerHeardLeavesTheHoldForTheNextLift() {
        finger(MotionEvent.ACTION_DOWN);
        hold();
        finger(MotionEvent.ACTION_UP);

        finger(MotionEvent.ACTION_DOWN);
        assertTrue("the next gesture's listener was told the hold was over", ReelHold.release(false));
        finger(MotionEvent.ACTION_UP);
        assertTrue("the next lift didn't put the speed back", ReelHold.release(false));
        assertTrue("the release flag and the speed-up flag disagreed on the same lift", ReelHold.release(false));

        assertFalse("a tap after the speed went back put a speed back", tapReleases());
    }

    /** A cancelled gesture ends the hold as a lift does, as the listener takes either. */
    @Test
    public void aCancelPutsTheSpeedBackOnce() {
        finger(MotionEvent.ACTION_DOWN);
        hold();
        finger(MotionEvent.ACTION_CANCEL);
        assertTrue(ReelHold.release(false));
        assertFalse("a tap after the cancel put a speed back", tapReleases());
    }

    /** An ad's long press opens Facebook's menu, never the speed-up: no hold to count or put back. */
    @Test
    public void anAdsLongPressIsNoHold() {
        finger(MotionEvent.ACTION_DOWN);
        assertTrue(ReelHold.longPress(false));
        finger(MotionEvent.ACTION_UP);
        assertFalse("the lift after an ad's long press put a speed back", ReelHold.release(false));
        assertFalse("a tap after an ad's long press put a speed back", tapReleases());
        assertEquals(FamilyNames.HOLD_REEL_FOR_2X + ": invoked 4, 2 found, 0 missing", statusLine());
    }

    /** A pinch's second finger is part of the same gesture, so a hold stays a hold. */
    @Test
    public void aSecondFingerKeepsTheHold() {
        finger(MotionEvent.ACTION_DOWN);
        hold();
        finger(MotionEvent.ACTION_POINTER_DOWN);
        assertTrue(ReelHold.release(false));
        finger(MotionEvent.ACTION_POINTER_UP);
        assertTrue("the first finger up ended the hold", ReelHold.release(false));
    }

    @Test
    public void offEveryAnswerIsFacebooks() {
        Settings.HOLD_REEL_FOR_2X.save(false);
        for (boolean facebooks : new boolean[] {false, true}) {
            finger(MotionEvent.ACTION_DOWN);
            assertEquals(facebooks, ReelHold.longPress(facebooks));
            assertEquals(facebooks, ReelHold.anywhere(facebooks));
            assertEquals(facebooks, ReelHold.speedUp(facebooks));
            ReelHold.held();
            assertEquals(facebooks, ReelHold.release(facebooks));
        }
        assertEquals(FamilyNames.HOLD_REEL_FOR_2X + ": invoked 10, 0 found, 0 missing", statusLine());
    }

    @Test
    public void pausedEveryAnswerIsFacebooks() {
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            finger(MotionEvent.ACTION_DOWN);
            assertFalse(reason.name(), ReelHold.longPress(false));
            assertFalse(reason.name(), ReelHold.anywhere(false));
            assertFalse(reason.name(), ReelHold.speedUp(false));
            ReelHold.held();
            assertFalse(reason.name(), ReelHold.release(false));
            assertTrue(reason.name(), ReelHold.release(true));
        }
        PauseForTests.resume();
        assertTrue(ReelHold.longPress(false));
    }

    /**
     * Facebook's own yes to a long-press flag stays yes with the switch on, and its yes to the release
     * flags holds only once a hold sped the reel up too, since the listener would otherwise undo a
     * picked speed.
     */
    @Test
    public void onFacebooksOwnSpeedUpStaysAndItsReleaseWaitsForAHold() {
        assertTrue(ReelHold.anywhere(true));
        assertTrue(ReelHold.speedUp(true));
        finger(MotionEvent.ACTION_DOWN);
        assertFalse("a tap put a speed back", ReelHold.release(true));
        assertTrue(ReelHold.longPress(true));
        ReelHold.held();
        assertTrue(ReelHold.release(true));
    }
}

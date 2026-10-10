/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hooks on the camera roll processing check and the media count job's kill switch: they hold
 * both back while the camera roll switch is on, and every other time leave Facebook's own answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CameraRollProcessingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void turnTheSwitchOn() {
        Settings.HOLD_CAMERA_ROLL_PROCESSING.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HOLD_CAMERA_ROLL_PROCESSING.resetToDefault();
        Settings.HOLD_ANALYTICS_UPLOADS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(CameraRollProcessing.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report("")) {
            if (line.startsWith(FamilyNames.ANALYTICS_UPLOADS + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffBelongsToTheAnalyticsPatchAndOnHoldsBoth() {
        assertFalse("the switch starts off", Settings.HOLD_CAMERA_ROLL_PROCESSING.defaultValue);
        assertTrue("Pause and the settings page reach it through the analytics family",
                PatchFamily.ANALYTICS_UPLOADS.switches.contains(Settings.HOLD_CAMERA_ROLL_PROCESSING));
        assertTrue("the processing check answered yes", CameraRollProcessing.holdProcessing());
        assertTrue("the media count job kept running", CameraRollProcessing.stopMediaCount(false));
        assertEquals(CameraRollProcessing.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: "
                + CameraRollProcessing.MEDIA_COUNT + ". Removed: " + CameraRollProcessing.PROCESSING + " 1, "
                + CameraRollProcessing.MEDIA_COUNT + " 1", counterLine());
        assertEquals(FamilyNames.ANALYTICS_UPLOADS + ": invoked 2, 0 found, 0 missing", statusLine());
    }

    @Test
    public void offFacebookDecides() {
        Settings.HOLD_CAMERA_ROLL_PROCESSING.save(false);
        assertFalse(CameraRollProcessing.holdProcessing());
        assertFalse(CameraRollProcessing.stopMediaCount(false));
        assertEquals(CameraRollProcessing.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
    }

    /** The two switches are separate: the analytics one alone holds no camera roll work. */
    @Test
    public void theAnalyticsSwitchAloneLeavesTheCameraRollAlone() {
        Settings.HOLD_CAMERA_ROLL_PROCESSING.save(false);
        Settings.HOLD_ANALYTICS_UPLOADS.save(true);
        assertFalse(CameraRollProcessing.holdProcessing());
        assertFalse(CameraRollProcessing.stopMediaCount(false));
    }

    /** Facebook's own kill switch stands whatever the switch says, and isn't counted as held. */
    @Test
    public void facebooksOwnKillSwitchStaysSet() {
        assertTrue(CameraRollProcessing.stopMediaCount(true));
        Settings.HOLD_CAMERA_ROLL_PROCESSING.save(false);
        assertTrue(CameraRollProcessing.stopMediaCount(true));
        assertNull("a job Facebook had off was counted", counterLine());
        assertEquals(FamilyNames.ANALYTICS_UPLOADS + ": invoked 2, 0 found, 0 missing", statusLine());
    }

    @Test
    public void pausedFacebookDecides() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse("paused by " + why, CameraRollProcessing.holdProcessing());
            assertFalse("paused by " + why, CameraRollProcessing.stopMediaCount(false));
        }
        PauseForTests.resume();
        assertTrue(CameraRollProcessing.holdProcessing());
        assertTrue(CameraRollProcessing.stopMediaCount(false));
    }

    /** Before Facebook hands Hushfacebook its context, both hooks take Facebook's own path. */
    @Test
    public void beforeTheSettingsAreReadyFacebookDecides() {
        List<Boolean> answers = new ArrayList<>();
        SettingsContextRule.withoutContext(() -> {
            answers.add(CameraRollProcessing.holdProcessing());
            answers.add(CameraRollProcessing.stopMediaCount(false));
        });
        assertEquals(java.util.Arrays.asList(false, false), answers);
    }

    /** Every check in a session is held, not only the first. */
    @Test
    public void everyCheckIsHeldNotOnlyTheFirst() {
        for (int i = 0; i < 3; i++) assertTrue(CameraRollProcessing.holdProcessing());
        assertEquals(CameraRollProcessing.ROUTE + ": 3 lists, 3 items, 3 removed. Last reason: "
                + CameraRollProcessing.PROCESSING + ". Removed: " + CameraRollProcessing.PROCESSING + " 3", counterLine());
    }
}

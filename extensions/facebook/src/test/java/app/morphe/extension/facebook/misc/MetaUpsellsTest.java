/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide Meta upsells: each of the four switches starts off, and on it answers away only its own
 * promotions, counted. A no stays a no, Kotlin's suspend marker passes the Meta Verified hook
 * untouched, and off or paused every answer is Facebook's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MetaUpsellsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final BooleanSetting[] SWITCHES = {Settings.HIDE_EDITS_UPSELLS, Settings.HIDE_THREADS_CROSS_POSTING,
            Settings.HIDE_META_VERIFIED_UPSELLS, Settings.HIDE_AVATAR_UPSELLS};

    /** What Kotlin hands back from a suspend method that hasn't finished, as far as the hook can tell. */
    private static final Object NOT_YET = new Object();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        for (BooleanSetting setting : SWITCHES) setting.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.META_UPSELLS + ":")) return line;
        }
        return "";
    }

    /** Whether each part hides, in the order Edits, Threads, Meta Verified, avatar stickers. */
    private static boolean[] hiding() {
        boolean edits = !MetaUpsells.editsHeader(true) && !MetaUpsells.fetchEditsPill(true)
                && Boolean.FALSE.equals(MetaUpsells.fetchEditsPill(Boolean.TRUE));
        boolean threads = !MetaUpsells.threadsOnboarding(1);
        boolean verified = Boolean.FALSE.equals(MetaUpsells.metaVerifiedSheet(Boolean.TRUE))
                && MetaUpsells.metaVerifiedLabel("Meta Verified") == null;
        boolean avatar = MetaUpsells.hidesAvatarUpsell();
        return new boolean[] {edits, threads, verified, avatar};
    }

    @Test
    public void everySwitchStartsOffAndFacebookDecides() {
        for (BooleanSetting setting : SWITCHES) assertFalse(setting.key + " starts on", setting.get());
        assertTrue(Arrays.toString(hiding()), Arrays.equals(new boolean[4], hiding()));
        assertEquals("Meta Verified", MetaUpsells.metaVerifiedLabel("Meta Verified"));
        assertEquals(Boolean.TRUE, MetaUpsells.fetchEditsPill(Boolean.TRUE));
    }

    @Test
    public void eachSwitchHidesOnlyItsOwnAndIsCounted() {
        for (int on = 0; on < SWITCHES.length; on++) {
            for (BooleanSetting setting : SWITCHES) setting.save(setting == SWITCHES[on]);
            boolean[] expected = new boolean[4];
            expected[on] = true;
            assertTrue(SWITCHES[on].key + " hid " + Arrays.toString(hiding()), Arrays.equals(expected, hiding()));
        }
        String line = statusLine();
        assertTrue(line, line.contains(MetaUpsells.EDITS_HIDDEN + " 3"));
        assertTrue(line, line.contains(MetaUpsells.THREADS_HIDDEN + " 1"));
        assertTrue(line, line.contains(MetaUpsells.VERIFIED_HIDDEN + " 2"));
        assertTrue(line, line.contains(MetaUpsells.AVATAR_HIDDEN + " 1"));
    }

    @Test
    public void aNoStaysANoAndTheSuspendMarkerPasses() {
        for (BooleanSetting setting : SWITCHES) setting.save(true);
        assertFalse(MetaUpsells.editsHeader(false));
        assertFalse(MetaUpsells.fetchEditsPill(false));
        assertFalse(MetaUpsells.threadsOnboarding(0));
        assertSame("the eligibility check's not-yet marker was swapped", NOT_YET, MetaUpsells.metaVerifiedSheet(NOT_YET));
        assertEquals(Boolean.FALSE, MetaUpsells.metaVerifiedSheet(Boolean.FALSE));
        assertNull(MetaUpsells.metaVerifiedLabel(null));
        assertFalse("a no was counted", statusLine().contains("Counted"));
        // A gate that had no answer still sends a no, so the server isn't left to pick.
        assertEquals(Boolean.FALSE, MetaUpsells.fetchEditsPill((Boolean) null));
    }

    @Test
    public void pausedEveryAnswerIsFacebooks() {
        for (BooleanSetting setting : SWITCHES) setting.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " hid " + Arrays.toString(hiding()),
                    Arrays.equals(new boolean[4], hiding()));
            PauseForTests.resume();
        }
    }

    @Test
    public void theSwitchesTravelWithThePatch() {
        assertEquals(Arrays.asList(SWITCHES), PatchFamily.META_UPSELLS.switches);
        assertEquals("Hide Meta upsells", FamilyNames.META_UPSELLS);
    }
}

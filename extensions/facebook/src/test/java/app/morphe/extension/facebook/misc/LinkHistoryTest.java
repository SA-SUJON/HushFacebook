/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hook first in each of the browser's link history writer factories: a yes while the switch is
 * on, so neither writer is built, and Facebook's own answer every other time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LinkHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HOLD_LINK_HISTORY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(LinkHistory.ROUTE + ":")) return line;
        }
        return null;
    }

    @Test
    public void onByDefaultNeitherWriterIsBuilt() {
        assertTrue("the switch starts on", Settings.HOLD_LINK_HISTORY.defaultValue);
        assertTrue("the signals writer was built", LinkHistory.hold());
        assertTrue("the page data writer was built", LinkHistory.hold());
        assertEquals(LinkHistory.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: " + LinkHistory.WRITERS
                + ". Removed: " + LinkHistory.WRITERS + " 2", counterLine());
        boolean reported = false;
        for (String line : HookStatus.report("")) {
            if (line.equals(FamilyNames.EXTERNAL_BROWSER + ": invoked 2, 0 found, 0 missing")) reported = true;
        }
        assertTrue(String.valueOf(HookStatus.report("")), reported);
    }

    @Test
    public void offTheBrowserBuildsItsWriters() {
        Settings.HOLD_LINK_HISTORY.save(false);
        assertFalse(LinkHistory.hold());
        assertEquals(LinkHistory.ROUTE + ": 1 lists, 1 items, 0 removed", counterLine());
    }

    @Test
    public void pausedTheBrowserBuildsItsWriters() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(LinkHistory.hold());
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertFalse(LinkHistory.hold());
        PauseForTests.resume();
        assertTrue(LinkHistory.hold());
    }
}

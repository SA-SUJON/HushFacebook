/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide the Feeds header: with the switch on, the Feeds tab's yes to a title row is answered as a
 * no, its filters go to a copy of their container, and its posts get no room for them, each
 * counted. Off, which is how it starts, or paused, Facebook's answers stand, and a missing
 * container is never copied.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedsHeaderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_FEEDS_HEADER.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FEEDS_HEADER + ":")) return line;
        }
        return null;
    }

    private static View container() {
        return new FrameLayout(RuntimeEnvironment.getApplication());
    }

    @Test
    public void offAtFirstFacebooksHeaderStays() {
        assertFalse("the switch doesn't start off", Settings.HIDE_FEEDS_HEADER.get());
        assertTrue(FeedsHeader.navBar(true));
        assertFalse(FeedsHeader.navBar(false));
        assertFalse(FeedsHeader.hidesFilters(container()));
        assertTrue(FeedsHeader.roomForFilters(true));
        assertFalse(FeedsHeader.roomForFilters(false));
        assertEquals(FamilyNames.FEEDS_HEADER + ": invoked 5, 3 found, 0 missing", statusLine());
    }

    @Test
    public void onTheTitleRowTheFiltersAndTheirRoomGo() {
        Settings.HIDE_FEEDS_HEADER.save(true);
        assertFalse("the Feeds tab still gets its title row", FeedsHeader.navBar(true));
        assertFalse("a fragment that wants no title row was given one", FeedsHeader.navBar(false));
        assertTrue("the filters stay on screen", FeedsHeader.hidesFilters(container()));
        assertFalse("a missing container was copied", FeedsHeader.hidesFilters(null));
        assertFalse("the posts still sit under the filters' room", FeedsHeader.roomForFilters(true));
        assertFalse("posts without filters were given room for them", FeedsHeader.roomForFilters(false));
        assertEquals(FamilyNames.FEEDS_HEADER + ": invoked 6, 3 found, 0 missing. Counted: "
                + FeedsHeader.NO_TITLE_ROW + " 1, " + FeedsHeader.NO_FILTERS + " 1, " + FeedsHeader.NO_ROOM + " 1",
                statusLine());
    }

    @Test
    public void pausedFacebooksHeaderStays() {
        Settings.HIDE_FEEDS_HEADER.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " left out the title row", FeedsHeader.navBar(true));
            assertFalse("a Hushfacebook paused by " + reason + " left out the filters", FeedsHeader.hidesFilters(container()));
            assertTrue("a Hushfacebook paused by " + reason + " left out the filters' room", FeedsHeader.roomForFilters(true));
            PauseForTests.resume();
        }
    }
}

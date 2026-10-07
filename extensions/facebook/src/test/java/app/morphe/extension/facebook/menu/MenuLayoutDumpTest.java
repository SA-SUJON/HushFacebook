/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.litho.BaseMountingView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * With Debug logging on, a Menu build has Menu's layout written a moment later, cut down to the
 * Litho components naming the Muse card with their parents and the views whose description names
 * Muse, and the rest of Menu left out. Off, nothing is read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuLayoutDumpTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** How Litho describes a tree: each component on its line, indented under its parent. */
    private static final String MENU = String.join("\n",
            "litho.Column{1 V.E..... .. 0,0-1080,2000}",
            "  litho.ProfileHeader{2 V.E..... .. 0,0-1080,300 text=\"Pat Doe\"}",
            "  litho.HScroll{3 V.E..... .. 0,300-1080,600}",
            "    litho.MuseCard{4 V.E..... .. 0,300-1080,600}",
            "      litho.Text{5 V.E..... .. 0,300-1080,350 text=\"Meet Muse, your personal AI agent.\"}",
            "      litho.Button{6 V.E..... .. 0,500-300,600 text=\"Get app\"}",
            "  litho.Shortcuts{7 V.E..... .. 0,600-1080,1200}",
            "    litho.Text{8 V.E..... .. 0,600-1080,650 text=\"Groups\"}");

    @Before
    public void start() {
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        LogBufferManager.clearLogBuffer();
        MenuSectionsForTests.newProcess();
    }

    @After
    public void finish() {
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        MenuSectionsForTests.newProcess();
    }

    @Test
    public void eachLineNamingTheCardComesWithItsParentsOnly() {
        String[] lines = MENU.split("\n");
        assertEquals(Arrays.asList(lines[0], lines[2], lines[3], lines[4], lines[5]), MenuLayoutDump.around(MENU));
        assertTrue("a tree without the card gives nothing", MenuLayoutDump.around(lines[0] + "\n" + lines[7]).isEmpty());
    }

    @Test
    public void theCardsComponentsAndTheViewNamingItAreWritten() {
        BaseSettings.DEBUG.save(true);
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(context);
        BaseMountingView home = new BaseMountingView(context);
        home.description = "litho.Column{9 V.E..... .. 0,0-1080,2000}\n  litho.Text{10 V.E..... .. 0,0-1080,50 text=\"Home\"}";
        BaseMountingView menu = new BaseMountingView(context);
        menu.description = MENU;
        TextView card = new TextView(context);
        card.setContentDescription("Muse, button 1 of 1");
        menu.addView(card);
        root.addView(home);
        root.addView(menu);

        assertTrue(MenuLayoutDump.dump(root));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Menu layout, Litho tree 2 of 2:"));
        assertTrue(report, report.contains("litho.MuseCard{4"));
        assertTrue(report, report.contains("text=\"Get app\""));
        assertFalse("the rest of Menu was written: " + report, report.contains("Pat Doe"));
        assertFalse("a tree without the card was written: " + report, report.contains("Litho tree 1 of 2"));
        assertTrue(report, report.contains("Menu layout, a view naming Muse: android.widget.TextView < "
                + BaseMountingView.class.getName() + " < android.widget.FrameLayout"));
    }

    @Test
    public void withDebugLoggingOnMenuIsReadAMomentAfterItBuildsUntilTheCardIsFound() {
        FbMainTabActivity screen = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        BaseMountingView menu = new BaseMountingView(screen);
        menu.description = MENU;
        screen.setContentView(menu);
        MenuLayoutDump.screenCreated(screen);

        MenuSectionsForTests.hidesUpgrades();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.DELAY_MS, TimeUnit.MILLISECONDS);
        assertFalse("read with Debug logging off", LogBufferManager.buildExportText().contains("Menu layout"));

        BaseSettings.DEBUG.save(true);
        MenuSectionsForTests.hidesUpgrades();
        assertFalse("read before Litho drew it", LogBufferManager.buildExportText().contains("Menu layout"));
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.DELAY_MS, TimeUnit.MILLISECONDS);
        assertTrue(LogBufferManager.buildExportText(), LogBufferManager.buildExportText().contains("litho.MuseCard{4"));

        LogBufferManager.clearLogBuffer();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.GAP_MS, TimeUnit.MILLISECONDS);
        MenuSectionsForTests.hidesServerUpgrades();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.DELAY_MS, TimeUnit.MILLISECONDS);
        assertFalse("read again after the card was found", LogBufferManager.buildExportText().contains("Menu layout"));
    }
}

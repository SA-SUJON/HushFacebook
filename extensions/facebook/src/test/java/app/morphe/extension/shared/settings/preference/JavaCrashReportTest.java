/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * A Java crash is kept with its whole trace for the next diagnostic report (#94), once per
 * process, and nothing that isn't a crash takes its place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class JavaCrashReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    @Before
    public void start() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.resetForTests();
    }

    @After
    public void finish() {
        LogBufferManager.clearLogBuffer();
        JavaCrashReport.resetForTests();
    }

    @Test
    public void aCrashIsInTheNextReportWithItsWholeTrace() {
        assertEquals("a crash was already kept", "", LogBufferManager.readCrashReport(context));
        Throwable crash = new IllegalStateException("outer", new NullPointerException("inner cause"));
        JavaCrashReport.save(new Thread("CombinedTP7"), crash);

        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("exception: java.lang.IllegalStateException\n"));
        assertTrue(kept, kept.contains("thread: CombinedTP7\n"));
        assertTrue("the cause was left out: " + kept,
                kept.contains("Caused by: java.lang.NullPointerException: inner cause"));
        assertTrue("the frames were left out: " + kept,
                kept.contains("at " + JavaCrashReportTest.class.getName() + ".aCrashIsInTheNextReportWithItsWholeTrace"));

        String report = LogBufferManager.buildExportText();
        assertTrue("the report has no crash section: " + report, report.contains("[LATEST JAVA CRASH]"));
        assertTrue(report, report.contains("Caused by: java.lang.NullPointerException: inner cause"));
    }

    @Test
    public void onlyTheFirstCrashOfAProcessIsKept() {
        // A handler Facebook put in front of ours can hand the same crash back to the one under it.
        JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("the first"));
        JavaCrashReport.save(Thread.currentThread(), new IllegalArgumentException("the second"));

        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("java.lang.IllegalStateException: the first"));
        assertFalse("a second crash replaced the first: " + kept, kept.contains("IllegalArgumentException"));
    }

    @Test
    public void nothingToKeepLeavesTheNextCrashItsPlace() {
        JavaCrashReport.save(Thread.currentThread(), null);
        assertEquals("", LogBufferManager.readCrashReport(context));

        JavaCrashReport.save(null, new IllegalStateException("after"));
        String kept = LogBufferManager.readCrashReport(context);
        assertTrue(kept, kept.contains("java.lang.IllegalStateException: after"));
        assertTrue(kept, kept.contains("thread: unknown\n"));
    }
}

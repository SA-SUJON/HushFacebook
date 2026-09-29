/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Hide the Reels tab does to Facebook's tab bar: it takes off the Reels tab, which some
 * accounts call Video (Facebook keeps one class for both, {@link FacebookTabs#VIDEO_CLASS}), and
 * leaves every other tab where Facebook put it.
 *
 * <p>Facebook's tab bar list builder asks {@link TabBarFilter} about each configured tab right after
 * its own set of hidden tab ids has answered, and that asks {@link #hidesTab}, so the Reels tab goes
 * the way a tab hidden in Facebook's Settings, Tab bar, Customize the bar does. A Reels tab
 * Facebook's own set already hides stays Facebook's: this neither counts it nor puts it back.
 * Facebook builds the list once and keeps it, so a change of the switch shows when Facebook
 * restarts. Reels themselves still open: a reel link, a reel in the feed and the Reels viewer are
 * their own screens, and a start or a notification asking for the missing tab opens the bar's first
 * tab, as Facebook does for any tab its bar hasn't got.
 *
 * <p>It fails open: with the patch not in the build, the switch off, Hushfacebook paused, the
 * settings not ready yet, or a failure in here, the tab bar is Facebook's own.
 */
public final class ReelsTab {
    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "ReelsTab";

    /** What every line of this hook starts with, for a person reading the log. */
    static final String PREFIX = "Reels tab: ";

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /**
     * Whether the tab bar this process built lost the Reels tab to the switch, or null before
     * Facebook has built one. Facebook keeps the bar it built, so this, not the switch, says whether
     * the tab is there until Facebook restarts.
     */
    @Nullable
    private static volatile Boolean tookItOff;

    /** The lines logged, so a rebuilt tab bar doesn't log them again. */
    private static final Set<String> logged = Collections.synchronizedSet(new HashSet<>());

    private ReelsTab() {
    }

    /** Whether this build carries the patch. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.reelsTab();
    }

    /**
     * Whether the Reels tab goes now: the patch in the build, the settings ready and its switch on,
     * which a pause answers off. Never throws.
     */
    static boolean on() {
        try {
            return inBuild() && Utils.settingsReady() && Settings.HIDE_REELS_TAB.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "reels tab switch", failure);
            return false;
        }
    }

    /**
     * Whether a start asking for the Reels tab would find the switch has kept it off the bar: the
     * bar this process built lost it, or, before Facebook has built one, the switch is on. The
     * start then asks for Home. Never throws.
     */
    static boolean offTheBar() {
        Boolean built = tookItOff;
        return built != null ? built : on();
    }

    /**
     * Asked by {@link TabBarFilter} for each configured tab, right after Facebook's hidden-tab set
     * answered [hidden] for [tab]. Answers whether the tab stays off the bar: always when Facebook
     * hides it, and for the Reels tab while the switch is on. Never throws.
     */
    public static boolean hidesTab(boolean hidden, @Nullable Object tab) {
        try {
            if (tab == null || !inBuild()) return hidden;
            boolean reels = FacebookTabs.VIDEO_CLASS.equals(tab.getClass().getName());
            if (!Utils.settingsReady()) {
                // A bar built before the settings is Facebook's, and stays so until it restarts.
                if (reels) tookItOff = false;
                return hidden;
            }
            HookStatus.invoked(FamilyNames.REELS_TAB);
            if (!reels) return hidden;
            boolean wanted = on();
            if (hidden) {
                tookItOff = false;
                if (wanted && logged.add("facebook")) {
                    Logger.diagnosticInfo(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> PREFIX
                            + "Facebook's own tab bar settings already hide the Reels tab, so it's left to them.");
                }
                return true;
            }
            tookItOff = wanted;
            if (!wanted) return false;
            HookStatus.bound(FamilyNames.REELS_TAB, "tab bar");
            if (logged.add("took")) {
                Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                        () -> PREFIX + "took " + tab.getClass().getSimpleName() + " off the tab bar.");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "tab bar", failure);
            return hidden;
        }
    }

    /** Forgets the built tab bar and which lines were logged, as a new process would. */
    static void forget() {
        logged.clear();
        tookItOff = null;
    }
}

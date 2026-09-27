/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Marketplace only does to Facebook's tab bar: it takes off Home with the news feed, Video,
 * Friends, Feeds, Groups, Gaming and Events, and leaves Marketplace, Notifications, the profile or
 * Menu tab and any tab it doesn't know.
 *
 * <p>Facebook builds the tab bar's list from the tabs its servers configure, leaving out each one
 * whose id is in the set of tabs hidden in its own Settings, Tab bar, Customize the bar. The patch
 * calls {@link #hidesTab} right after that set answers for a tab, so a tab this drops goes the way
 * a tab hidden there does. Facebook builds the list once and keeps it, so a change of the switch
 * shows when Facebook restarts. While the switch is on, {@link StartTabRoute} also opens Facebook on
 * Marketplace.
 *
 * <p>It fails open: with the patch not in the build, the switch off, Hushfacebook paused, the
 * settings not ready yet, a tab bar that has no Marketplace to open (not configured for the
 * account, or hidden in Facebook's editor), or a failure in here, the tab bar is Facebook's own.
 */
public final class MarketplaceOnly {
    /** The tabs this takes off the tab bar, by the class Facebook keeps for each. */
    static final Set<String> DROPPED_TABS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            FacebookTabs.HOME_CLASS,
            FacebookTabs.FEEDS_CLASS,
            FacebookTabs.MOST_RECENT_CLASS,
            FacebookTabs.VIDEO_CLASS,
            FacebookTabs.FRIENDS_CLASS,
            FacebookTabs.GROUPS_CLASS,
            FacebookTabs.GAMING_CLASS,
            FacebookTabs.GAMING_CONTROLLER_CLASS,
            FacebookTabs.EVENTS_CLASS)));

    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "MarketplaceOnly";

    /** What every line of this hook starts with, for a person reading the log. */
    static final String PREFIX = "Marketplace only: ";

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** The tab classes a line has been logged for, so a rebuilt tab bar doesn't log them again. */
    private static final Set<String> logged = Collections.synchronizedSet(new HashSet<>());

    private MarketplaceOnly() {
    }

    /** Whether this build carries the patch. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.marketplaceOnly();
    }

    /**
     * Whether the tab bar is Marketplace only now: the patch in the build, the settings ready and
     * its switch on, which a pause answers off. Never throws.
     */
    static boolean on() {
        try {
            return inBuild() && Utils.settingsReady() && Settings.MARKETPLACE_ONLY.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MARKETPLACE_ONLY, "marketplace only switch", failure);
            return false;
        }
    }

    /**
     * Injection point in Facebook's tab bar list builder, right after its set of hidden tab ids
     * answered [hidden] for [tab]. [configured] is the list of tabs the account is configured with
     * and [hiddenIds] the set, each id a String. Answers whether the tab stays off the bar: always
     * when Facebook hides it, and for a tab this drops while it's on and the bar keeps Marketplace.
     * Never throws.
     */
    public static boolean hidesTab(boolean hidden, @Nullable Object tab, @Nullable List<?> configured,
                                   @Nullable Set<?> hiddenIds) {
        if (hidden) return true;
        try {
            if (tab == null || !inBuild()) return false;
            HookStatus.invoked(FamilyNames.MARKETPLACE_ONLY);
            String name = tab.getClass().getName();
            if (!DROPPED_TABS.contains(name) || !on()) return false;
            if (!keepsMarketplace(configured, hiddenIds)) {
                if (logged.add("")) {
                    Logger.diagnosticInfo(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> PREFIX
                            + "this tab bar has no Marketplace to open, so it stays as Facebook built it.");
                }
                return false;
            }
            HookStatus.bound(FamilyNames.MARKETPLACE_ONLY, "tab bar");
            if (logged.add(name)) {
                Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                        () -> PREFIX + "took " + tab.getClass().getSimpleName() + " off the tab bar.");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MARKETPLACE_ONLY, "tab bar", failure);
            return false;
        }
    }

    /**
     * Whether the tab bar will have Marketplace: the account is configured with it and it isn't
     * among the tabs hidden in Facebook's own editor. Without it there'd be nothing to open, so the
     * bar stays as Facebook built it.
     */
    static boolean keepsMarketplace(@Nullable List<?> configured, @Nullable Set<?> hiddenIds) {
        if (configured == null) return false;
        for (Object each : configured) {
            if (each == null || !StartTab.MARKETPLACE.isTab(each.getClass().getName())) continue;
            long id = StartTabRoute.TabBar.tabId(each);
            return id != -1 && (hiddenIds == null || !hiddenIds.contains(String.valueOf(id)));
        }
        return false;
    }

    /** Forgets which lines were logged, as a new process would. */
    static void forgetLogged() {
        logged.clear();
    }
}

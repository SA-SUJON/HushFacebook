/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.events.targetedtab.EventsTab;
import com.facebook.feed.tab.FeedTab;
import com.facebook.friending.tab.FriendRequestsTab;
import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.marketplace.tab.MarketplaceTab;
import com.facebook.navigation.tabbar.state.model.TabTag;
import com.facebook.notifications.tab.NotificationsTab;
import com.facebook.timeline.dashboard.tab.TimelineTab;
import com.facebook.video.videohome.tab.WatchTab;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Marketplace only over Facebook's tab bar as its list builder asks it, one tab at a time after
 * Facebook's own hidden-tab set has answered, and the start it sends to Marketplace.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MarketplaceOnlyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The test account's tab bar, in order, as Facebook configures it. */
    private final List<TabTag> configured = Arrays.asList(new FeedTab(), new WatchTab(), new FriendRequestsTab(),
            new MarketplaceTab(), new NotificationsTab(), new TimelineTab());

    @Before
    public void inBuild() {
        MarketplaceOnlyForTests.inBuild(Boolean.TRUE);
        MarketplaceOnly.forgetLogged();
    }

    @After
    public void restore() {
        MarketplaceOnlyForTests.inBuild(null);
        MarketplaceOnly.forgetLogged();
        StartTabRoute.settled();
        PauseForTests.resume();
        Settings.MARKETPLACE_ONLY.resetToDefault();
        Settings.OPEN_ON_CHOSEN_TAB.resetToDefault();
        Settings.START_TAB.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    /** The tabs Facebook's builder would add, asking the hook after the hidden set for each. */
    private static List<String> shown(List<? extends TabTag> configured, Set<String> hiddenIds) {
        List<String> shown = new ArrayList<>();
        for (TabTag tab : configured) {
            boolean hidden = hiddenIds.contains(String.valueOf(tab.id));
            if (!MarketplaceOnly.hidesTab(hidden, tab, configured, hiddenIds)) shown.add(tab.getClass().getSimpleName());
        }
        return shown;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.MARKETPLACE_ONLY + ":")) return line;
        }
        return null;
    }

    @Test
    public void theTabBarKeepsMarketplaceNotificationsAndTheProfile() {
        assertTrue("the switch starts on", Settings.MARKETPLACE_ONLY.get());
        assertEquals(Arrays.asList("MarketplaceTab", "NotificationsTab", "TimelineTab"),
                shown(configured, Collections.emptySet()));
        assertTrue(statusLine(), statusLine().startsWith(FamilyNames.MARKETPLACE_ONLY + ": invoked 6, 1 found, 0 missing"));
    }

    @Test
    public void everyTabItDropsIsOneOfFacebooksFeedOrSocialTabs() {
        assertEquals(new HashSet<>(Arrays.asList(FacebookTabs.HOME_CLASS, FacebookTabs.FEEDS_CLASS,
                        FacebookTabs.MOST_RECENT_CLASS, FacebookTabs.VIDEO_CLASS, FacebookTabs.FRIENDS_CLASS,
                        FacebookTabs.GROUPS_CLASS, FacebookTabs.GAMING_CLASS, FacebookTabs.GAMING_CONTROLLER_CLASS,
                        FacebookTabs.EVENTS_CLASS)),
                MarketplaceOnly.DROPPED_TABS);
        for (String kept : new String[]{FacebookTabs.MARKETPLACE_CLASS, FacebookTabs.NOTIFICATIONS_CLASS,
                FacebookTabs.MENU_CLASS, FacebookTabs.PROFILE_CLASS}) {
            assertFalse(kept, MarketplaceOnly.DROPPED_TABS.contains(kept));
        }
        // A tab it doesn't know stays: it isn't the feed.
        List<TabTag> withEvents = new ArrayList<>(configured);
        withEvents.add(new EventsTab());
        TabTag unknown = new TabTag(5L) {
        };
        withEvents.add(unknown);
        List<String> shown = shown(withEvents, Collections.emptySet());
        assertFalse(shown.contains("EventsTab"));
        assertEquals(4, shown.size());
    }

    @Test
    public void aTabFacebookHidesStaysHidden() {
        Set<String> hidden = Collections.singleton(String.valueOf(FacebookTabs.NOTIFICATIONS_ID));
        assertEquals(Arrays.asList("MarketplaceTab", "TimelineTab"), shown(configured, hidden));
        Settings.MARKETPLACE_ONLY.save(false);
        assertTrue(MarketplaceOnly.hidesTab(true, new NotificationsTab(), configured, hidden));
    }

    /** Without a Marketplace to open, the tab bar is Facebook's own, and the log says why. */
    @Test
    public void aTabBarWithNoMarketplaceIsLeftAlone() {
        List<TabTag> noMarketplace = Arrays.asList(new FeedTab(), new WatchTab(), new NotificationsTab());
        assertEquals(Arrays.asList("FeedTab", "WatchTab", "NotificationsTab"), shown(noMarketplace, Collections.emptySet()));

        // Hidden in Facebook's own editor, it's as good as not there.
        Set<String> marketplaceHidden = Collections.singleton(String.valueOf(FacebookTabs.MARKETPLACE_ID));
        assertEquals(Arrays.asList("FeedTab", "WatchTab", "FriendRequestsTab", "NotificationsTab", "TimelineTab"),
                shown(configured, marketplaceHidden));
        String line = statusLine();
        assertTrue(line, line.contains("0 found, 0 missing"));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "this tab bar has no Marketplace to open"));
    }

    @Test
    public void offPausedOrNotInTheBuildTheTabBarIsFacebooks() {
        List<String> stock = Arrays.asList("FeedTab", "WatchTab", "FriendRequestsTab", "MarketplaceTab",
                "NotificationsTab", "TimelineTab");
        Settings.MARKETPLACE_ONLY.save(false);
        assertEquals(stock, shown(configured, Collections.emptySet()));
        Settings.MARKETPLACE_ONLY.save(true);

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertEquals(why.name(), stock, shown(configured, Collections.emptySet()));
        }
        PauseForTests.resume();

        MarketplaceOnlyForTests.inBuild(Boolean.FALSE);
        assertEquals(stock, shown(configured, Collections.emptySet()));
        MarketplaceOnlyForTests.inBuild(Boolean.TRUE);

        boolean[] hides = {true};
        SettingsContextRule.withoutContext(() -> hides[0] = MarketplaceOnlyForTests.hidesHome());
        assertFalse("a tab bar built before the context lost Home", hides[0]);
        assertTrue(MarketplaceOnlyForTests.hidesHome());
    }

    /** A failure in the hook leaves the tab where Facebook put it and says so in Hook status. */
    @Test
    public void aFailureLeavesTheTabBarAlone() {
        List<TabTag> broken = new AbstractList<TabTag>() {
            @Override
            public TabTag get(int index) {
                throw new IllegalStateException("for this test");
            }

            @Override
            public int size() {
                return 1;
            }
        };
        assertFalse(MarketplaceOnly.hidesTab(false, new FeedTab(), broken, Collections.emptySet()));
        assertFalse(MarketplaceOnly.hidesTab(false, null, configured, Collections.emptySet()));
        assertFalse(MarketplaceOnly.hidesTab(false, new FeedTab(), null, null));
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("1 missing"));
        assertTrue(line, line.contains("'tab bar' hook (it threw java.lang.IllegalStateException)"));
    }

    @Test
    public void debugLoggingNamesEachTabTakenOffOnce() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        shown(configured, Collections.emptySet());
        shown(configured, Collections.emptySet());
        String report = LogBufferManager.buildExportText();
        for (String tab : new String[]{"FeedTab", "WatchTab", "FriendRequestsTab"}) {
            assertEquals(tab, 1, occurrences(report, "Marketplace only: took " + tab + " off the tab bar."));
        }
        assertEquals(0, occurrences(report, "took MarketplaceTab"));
    }

    /** While it's on, a start from the launcher icon opens Marketplace, whatever tab is chosen. */
    @Test
    public void aStartFromTheLauncherIconOpensMarketplace() {
        Settings.START_TAB.save(StartTab.FRIENDS);
        Settings.OPEN_ON_CHOSEN_TAB.save(false);
        assertEquals(FacebookTabs.MARKETPLACE_ID, askedFor(StartTabRouteForTests.launcherStart()));

        // Off, the chosen tab's own switch decides again.
        Settings.MARKETPLACE_ONLY.save(false);
        assertEquals(-1, askedFor(StartTabRouteForTests.launcherStart()));
        Settings.OPEN_ON_CHOSEN_TAB.save(true);
        assertEquals(FacebookTabs.FRIENDS_ID, askedFor(StartTabRouteForTests.launcherStart()));

        // A build without the patch leaves the chosen tab alone, whatever its stored switch says.
        Settings.MARKETPLACE_ONLY.save(true);
        MarketplaceOnlyForTests.inBuild(Boolean.FALSE);
        assertEquals(FacebookTabs.FRIENDS_ID, askedFor(StartTabRouteForTests.launcherStart()));
        MarketplaceOnlyForTests.inBuild(Boolean.TRUE);

        // Links and notifications keep their own destination.
        android.content.Intent notification = StartTabRouteForTests.launcherStart()
                .putExtra(FacebookTabs.TARGET_TAB_ID, FacebookTabs.NOTIFICATIONS_ID);
        assertEquals(FacebookTabs.NOTIFICATIONS_ID, askedFor(notification));
        assertNull(StartTabRoute.whyLeftAlone(StartTabRouteForTests.launcherStart(), null));
    }

    /** The tab the main screen started by [intent] asks Facebook for once the hook has seen it, or -1. */
    private static long askedFor(android.content.Intent intent) {
        FbMainTabActivity screen = StartTabRouteForTests.screen(intent);
        StartTabRoute.onActivityCreate(screen, null);
        StartTabRoute.settled();
        return screen.getIntent().getLongExtra(FacebookTabs.TARGET_TAB_ID, -1);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }
}

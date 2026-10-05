/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.bookmark.tab.BookmarkTab;
import com.facebook.events.targetedtab.EventsTab;
import com.facebook.feed.tab.FeedTab;
import com.facebook.friending.tab.FriendRequestsTab;
import com.facebook.marketplace.tab.MarketplaceTab;
import com.facebook.navigation.tabbar.state.model.TabTag;
import com.facebook.notifications.tab.NotificationsTab;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide tabs over Facebook's tab bar as its list builder asks the tab bar filter, one tab at a time
 * after Facebook's own hidden-tab set has answered, and the start it keeps off a hidden tab.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HiddenTabsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<TabTag> configured = Arrays.asList(new FeedTab(), new FriendRequestsTab(), new MarketplaceTab(),
            new EventsTab(), new NotificationsTab(), new BookmarkTab());

    @Before
    public void inBuild() {
        HiddenTabs.inBuildForTests = Boolean.TRUE;
        HiddenTabs.clearForTests();
    }

    @After
    public void restore() {
        HiddenTabs.inBuildForTests = null;
        HiddenTabs.clearForTests();
        MarketplaceOnlyForTests.inBuild(null);
        PauseForTests.resume();
        for (HiddenTabs.Tab tab : HiddenTabs.Tab.values()) tab.setting().resetToDefault();
        Settings.MARKETPLACE_ONLY.resetToDefault();
        HookStatus.clear();
    }

    private static List<String> shown(List<? extends TabTag> configured, Set<String> hiddenIds) {
        List<String> shown = new ArrayList<>();
        for (TabTag tab : configured) {
            boolean hidden = hiddenIds.contains(String.valueOf(tab.id));
            if (!TabBarFilter.hidesTab(hidden, tab, configured, hiddenIds)) shown.add(tab.getClass().getSimpleName());
        }
        return shown;
    }

    private final List<String> stock = Arrays.asList("FeedTab", "FriendRequestsTab", "MarketplaceTab", "EventsTab",
            "NotificationsTab", "BookmarkTab");

    @Test
    public void everySwitchStartsOffAndTheBarIsFacebooks() {
        for (HiddenTabs.Tab tab : HiddenTabs.Tab.values()) {
            assertFalse(tab.name(), tab.setting().defaultValue);
            assertTrue(tab.name() + ": Facebook builds the bar once", tab.setting().rebootApp);
        }
        assertEquals(stock, shown(configured, Collections.emptySet()));
    }

    @Test
    public void aTabWhoseSwitchIsOnLeavesTheBar() {
        Settings.HIDE_FRIENDS_TAB.save(true);
        Settings.HIDE_EVENTS_TAB.save(true);
        assertEquals(Arrays.asList("FeedTab", "MarketplaceTab", "NotificationsTab", "BookmarkTab"),
                shown(configured, Collections.emptySet()));
        assertTrue("a start meant for Friends opens Home", HiddenTabs.offTheBar(StartTab.FRIENDS));
        assertFalse(HiddenTabs.offTheBar(StartTab.MARKETPLACE));
        assertFalse("Home can't be hidden", HiddenTabs.offTheBar(StartTab.HOME));
    }

    /** Facebook's own Hide stays in charge, and a tab it hid isn't one this took off. */
    @Test
    public void aTabFacebookAlreadyHidesIsLeftToFacebook() {
        Settings.HIDE_FRIENDS_TAB.save(true);
        Set<String> hidden = Collections.singleton(String.valueOf(new FriendRequestsTab().id));
        assertEquals(Arrays.asList("FeedTab", "MarketplaceTab", "EventsTab", "NotificationsTab", "BookmarkTab"),
                shown(configured, hidden));
        assertFalse(HiddenTabs.offTheBar(StartTab.FRIENDS));
    }

    @Test
    public void marketplaceOnlyKeepsMarketplace() {
        MarketplaceOnlyForTests.inBuild(Boolean.TRUE);
        Settings.MARKETPLACE_ONLY.save(true);
        Settings.HIDE_MARKETPLACE_TAB.save(true);
        assertTrue(shown(configured, Collections.emptySet()).contains("MarketplaceTab"));
        assertFalse(HiddenTabs.offTheBar(StartTab.MARKETPLACE));
    }

    @Test
    public void pausedOrOutOfTheBuildTheBarIsFacebooks() {
        Settings.HIDE_FRIENDS_TAB.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(stock, shown(configured, Collections.emptySet()));
        PauseForTests.resume();
        HiddenTabs.clearForTests();
        HiddenTabs.inBuildForTests = Boolean.FALSE;
        assertEquals(stock, shown(configured, Collections.emptySet()));
        assertFalse(HiddenTabs.offTheBar(StartTab.FRIENDS));
    }
}

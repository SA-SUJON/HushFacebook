/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import com.facebook.video.videohome.tab.WatchTab;

/** Hide the Reels tab as the tab bar builder asks it, for tests in any package. */
public final class ReelsTabForTests {
    private ReelsTabForTests() {
    }

    /** Says the patch is in the build, or with null, asks SettingsStatus again. */
    public static void inBuild(Boolean inBuild) {
        ReelsTab.inBuildForTests = inBuild;
    }

    /**
     * Asks the hook about the Reels tab, which Facebook's own settings don't hide, with the patch
     * in the build. True when it takes the tab off, which is the switch changing what Facebook
     * would have done.
     */
    public static boolean hidesTheTab() {
        Boolean before = ReelsTab.inBuildForTests;
        ReelsTab.inBuildForTests = Boolean.TRUE;
        try {
            return ReelsTab.hidesTab(false, new WatchTab());
        } finally {
            ReelsTab.inBuildForTests = before;
            ReelsTab.forget();
        }
    }

    /** Asks the tab bar's count hook about the Reels tab. True when it answers none for it. */
    public static boolean clearsTheDot() {
        return ReelsTabDot.clear(new WatchTab());
    }
}

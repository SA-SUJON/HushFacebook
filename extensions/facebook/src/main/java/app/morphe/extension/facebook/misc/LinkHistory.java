/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What Facebook's own browser asks, through the Open links in external browser patch, before it
 * builds either of its link history writers.
 *
 * <p>The browser has two. One records the links you open with when each page started, became usable
 * and finished loading. The other records each page's address and title. When Enhanced browsing is on
 * for your account, the second one's records are saved to Facebook as your link history and the
 * first one's go out as navigation events. When it's off, the browser still builds them whenever its
 * launch and Facebook's server config say so, and reports each write it turns away. Each writer's
 * factory already answers null when the feature is off for that browser, so the patch has it answer
 * null on a yes from {@link #hold} too, and the writer never exists.
 *
 * <p>It fails open: the switch off, a pause, settings that aren't ready yet, or a failure in here,
 * and the browser builds its writers as it would have. Login, checkout and autofill are other parts
 * of the browser and don't ask.
 */
public final class LinkHistory {
    /** The diagnostic counter route: each writer the browser went to build, and the ones held back. */
    static final String ROUTE = "Link history";

    /** What a writer that wasn't built is counted under. */
    static final String WRITERS = "Link history writers";

    private LinkHistory() {
    }

    /**
     * Injection point, first thing in each link history writer's factory. True makes the factory
     * answer null, its own answer when the feature is off. Never throws.
     */
    public static boolean hold() {
        try {
            HookStatus.invoked(FamilyNames.EXTERNAL_BROWSER);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HOLD_LINK_HISTORY.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, WRITERS);
            Logger.printDebug(() -> "Link history: held back a writer");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXTERNAL_BROWSER, "link history writer", failure);
            return false;
        }
    }
}

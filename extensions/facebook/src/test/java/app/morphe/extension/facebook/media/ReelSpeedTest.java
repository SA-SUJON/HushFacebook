/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Keep the reel speed: a speed picked in a reel's menu is kept with the viewer of the player it was
 * set on, and the first start after each bind of a player from that viewer gets it. The same reel
 * started again keeps whatever it's at, another viewer keeps Facebook's speed, normal speed goes
 * back to Facebook's reset, and off, paused or failing, every reel starts as Facebook starts it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelSpeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REELS = "fb_shorts_viewer";

    /** Stands in for FbGrootPlayer: its PlayerOrigin's toString and the speeds set on it. */
    private static final class FakePlayers implements ReelSpeed.Player {
        final Map<Object, String> origins = new IdentityHashMap<>();
        final List<String> set = new ArrayList<>();
        RuntimeException failure;

        Object player(String origin) {
            Object player = new Object();
            origins.put(player, origin);
            return player;
        }

        @Override
        public void setSpeed(Object player, float speed) {
            if (failure != null) throw failure;
            set.add(origins.get(player) + " " + speed);
            // Facebook's setter is hooked too, so the extension hears its own change.
            ReelSpeed.speedSet(player, speed);
        }

        @Override
        public Object origin(Object player) {
            String origin = origins.get(player);
            return origin == null ? null : new Object() {
                @Override
                public String toString() {
                    return origin;
                }
            };
        }
    }

    private FakePlayers players;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        ReelSpeed.forget();
        players = new FakePlayers();
        ReelSpeed.access = players;
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_REEL_SPEED.resetToDefault();
        ReelSpeed.forget();
        HookStatus.clear();
    }

    /** A pick in the Reels menu as Facebook makes it: the speed set on the reel's player, then the toast. */
    private static void pick(Object player, float speed) {
        ReelSpeed.speedSet(player, speed);
        SystemClock.sleep(150);
        ReelSpeed.picked(speed);
    }

    /** A reel coming on screen: its player binds the video, then starts playing it. */
    private static void play(Object player) {
        ReelSpeed.bound(player);
        ReelSpeed.started(player);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.KEEP_REEL_SPEED + ":")) return line;
        }
        return null;
    }

    @Test
    public void aPickedSpeedCarriesToEveryNextReelOfTheViewer() {
        assertTrue("the switch starts off", Settings.KEEP_REEL_SPEED.get());
        Object first = players.player(REELS + "::reels_tab");
        play(first);
        assertEquals("a reel started before any pick was changed", 0, players.set.size());
        pick(first, 1.5f);
        assertEquals(1.5f, ReelSpeed.kept(), 0f);

        Object second = players.player(REELS + "::reels_tab");
        play(second);
        // Pooled players come back for later reels, and a reel can start where another left off.
        play(first);
        Object third = players.player(REELS + "::feed_chaining");
        play(third);
        assertEquals(List.of(REELS + "::reels_tab 1.5", REELS + "::reels_tab 1.5", REELS + "::feed_chaining 1.5"),
                players.set);
        assertEquals(FamilyNames.KEEP_REEL_SPEED + ": invoked 13, 2 found, 0 missing. Counted: "
                + ReelSpeed.APPLIED + " 3", statusLine());
    }

    /** Paused and started again, a reel stays at what it's at: a 2x hold, say, lasts until the next reel. */
    @Test
    public void theSameReelStartedAgainKeepsItsSpeed() {
        Object reel = players.player(REELS);
        pick(reel, 0.5f);
        Object next = players.player(REELS);
        play(next);
        ReelSpeed.speedSet(next, 2f);
        ReelSpeed.started(next);
        ReelSpeed.started(next);
        assertEquals(List.of(REELS + " 0.5"), players.set);
    }

    @Test
    public void aPlayerInAnotherViewerStartsAtFacebooksSpeed() {
        pick(players.player(REELS), 1.5f);
        play(players.player("newsfeed"));
        play(players.player("video_home::fb_shorts_viewer"));
        Object noOrigin = new Object();
        play(noOrigin);
        assertEquals(0, players.set.size());
    }

    @Test
    public void normalSpeedGoesBackToFacebooksReset() {
        Object reel = players.player(REELS);
        pick(reel, 2f);
        pick(reel, 1f);
        assertEquals(1f, ReelSpeed.kept(), 0f);
        play(players.player(REELS));
        assertEquals(0, players.set.size());
    }

    /** The toast names the change it follows; a toast with no such change just before keeps nothing. */
    @Test
    public void aToastWithoutItsSpeedChangeKeepsNothing() {
        Object reel = players.player(REELS);
        ReelSpeed.speedSet(reel, 1.25f);
        ReelSpeed.picked(1.5f);
        assertEquals("a toast for another speed kept one", 1f, ReelSpeed.kept(), 0f);
        ReelSpeed.speedSet(reel, 1.5f);
        SystemClock.sleep(ReelSpeed.PICK_WINDOW_MS + 1);
        ReelSpeed.picked(1.5f);
        assertEquals("a toast long after the change kept it", 1f, ReelSpeed.kept(), 0f);
        ReelSpeed.picked(1.5f);
        assertEquals(1f, ReelSpeed.kept(), 0f);
        play(players.player(REELS));
        assertEquals(0, players.set.size());
    }

    @Test
    public void offNothingIsKeptOrApplied() {
        Settings.KEEP_REEL_SPEED.save(false);
        pick(players.player(REELS), 1.5f);
        Settings.KEEP_REEL_SPEED.save(true);
        play(players.player(REELS));
        assertEquals("a pick made while off was kept", 0, players.set.size());

        pick(players.player(REELS), 1.5f);
        Settings.KEEP_REEL_SPEED.save(false);
        play(players.player(REELS));
        assertEquals("a reel started while off got the kept speed", 0, players.set.size());
    }

    @Test
    public void pausedEveryReelStartsAsFacebookStartsIt() {
        pick(players.player(REELS), 1.5f);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            play(players.player(REELS));
            assertEquals(reason.name(), 0, players.set.size());
        }
        PauseForTests.resume();
        play(players.player(REELS));
        assertEquals(List.of(REELS + " 1.5"), players.set);
    }

    @Test
    public void aFailingSetterIsReportedAndTheReelPlaysOn() {
        pick(players.player(REELS), 1.5f);
        players.failure = new IllegalStateException("the setter failed");
        play(players.player(REELS));
        assertTrue(HookStatus.missing(FamilyNames.KEEP_REEL_SPEED).get(0)
                .startsWith("a working 'player start' hook (it threw "));
    }

    /** Until the patch fills the stubs in, no player has a viewer, so nothing is kept. */
    @Test
    public void unpatchedStubsKeepNothing() {
        ReelSpeed.access = ReelSpeed.PATCHED;
        Object reel = new Object();
        pick(reel, 1.5f);
        assertEquals(1f, ReelSpeed.kept(), 0f);
    }
}

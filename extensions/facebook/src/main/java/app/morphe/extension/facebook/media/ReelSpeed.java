/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keep the reel speed: the playback speed picked in a reel's menu stays for the next reels.
 *
 * <p>The Reels menu offers its speeds in two pickers, and a pick in either sets the speed on the
 * reel's own FbGrootPlayer and shows Facebook's speed toast a moment later; nothing else shows that
 * toast. Facebook remembers a speed per video, so the next reel's player starts at normal speed.
 * The patch hands this class every speed set on a player ({@link #speedSet}), the toast's speed
 * ({@link #picked}), each video a player binds ({@link #bound}) and each start of playback
 * ({@link #started}).
 *
 * <p>A speed picked on a reel in the Reels viewer, the {@link #VIEWER} origin the Reels tab and
 * every reel opened full screen play in, is kept, and the first start after each bind of a player
 * there gets it when the player's video is a reel that's neither an ad nor live: Facebook's menu
 * may not offer a speed on an ad, and a live video sped up runs into its live edge. Reels in the
 * feed (fb_shorts_native_in_feed_unit and the like) start on their own as you scroll past, so a
 * pick there stays with that one reel and neither replaces nor forgets the viewer's speed. A reel
 * paused and started again, a reel held at 2x and anything else set on the reel you're watching
 * stay as they are until the next reel. Picking normal speed goes back to Facebook's reset. The
 * speed lives in memory only, so it's gone when Facebook restarts, and nothing is kept or applied
 * while the switch is off, Hushfacebook is paused, the settings aren't ready, or anything here
 * fails.
 */
public final class ReelSpeed {
    static final float NORMAL = 1f;

    /** Two speeds this close are the same one, as Facebook's own speed toast and cache compare them. */
    static final float SAME = 0.01f;

    /** How long after a player's speed changed the toast still names that change. Facebook waits 150 ms. */
    static final long PICK_WINDOW_MS = 2000;

    /** Counted under the patch's name for each reel started at the kept speed. */
    static final String APPLIED = "reel started at the kept speed";

    /** The Reels viewer's origin, what its PlayerOrigin's toString() writes before "::". The patch checks the build has it. */
    static final String VIEWER = "fb_shorts_viewer";

    private static final String FAMILY = FamilyNames.KEEP_REEL_SPEED;

    /** What this class reads from a player and does to it. {@link #PATCHED} is the patch's; tests stand in. */
    interface Player {
        /** Sets the player's speed through Facebook's own setter. */
        void setSpeed(Object player, float speed);

        /** The player's PlayerOrigin, or null when there's none or the patch didn't fill it in. */
        @Nullable
        Object origin(Object player);

        /** Whether the player's video is a reel, by its VideoPlayerParams' isFbShorts. False without params. */
        boolean reel(Object player);

        /** Whether the player's video is an ad, by its params' isSponsored. False without params. */
        boolean ad(Object player);

        /** Whether the player's video is live now, by its params' isLiveNow. False without params. */
        boolean live(Object player);
    }

    static final Player PATCHED = new Player() {
        @Override
        public void setSpeed(Object player, float speed) {
            setPlayerSpeed(player, speed);
        }

        @Override
        public Object origin(Object player) {
            return playerOrigin(player);
        }

        @Override
        public boolean reel(Object player) {
            Object params = playerParams(player);
            return params != null && fbShorts(params);
        }

        @Override
        public boolean ad(Object player) {
            Object params = playerParams(player);
            return params != null && sponsored(params);
        }

        @Override
        public boolean live(Object player) {
            Object params = playerParams(player);
            return params != null && liveNow(params);
        }
    };

    static volatile Player access = PATCHED;

    private static final Object LOCK = new Object();

    /** The players that bound a video and haven't started it yet, weakly, by identity. */
    private static final TapToPlay.ArmedPlayers BOUND = new TapToPlay.ArmedPlayers();

    @Nullable
    private static WeakReference<Object> lastSetPlayer;
    private static float lastSetSpeed = NORMAL;
    private static long lastSetAt = Long.MIN_VALUE;
    private static float kept = NORMAL;

    private ReelSpeed() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: FbGrootPlayer's speed setter. Only a player may be passed. */
    public static void setPlayerSpeed(Object player, float speed) {
    }

    /** Filled in by the patch: FbGrootPlayer's PlayerOrigin getter. Only a player may be passed. */
    @Nullable
    public static Object playerOrigin(Object player) {
        return null;
    }

    /** Filled in by the patch: FbGrootPlayer's VideoPlayerParams getter. Only a player may be passed. */
    @Nullable
    public static Object playerParams(Object player) {
        return null;
    }

    /** Filled in by the patch: the field VideoPlayerParams' debug dump reports as isFbShorts. Only params may be passed. */
    public static boolean fbShorts(Object params) {
        return false;
    }

    /** Filled in by the patch: the field the params' debug dump reports as isSponsored. Only params may be passed. */
    public static boolean sponsored(Object params) {
        return false;
    }

    /** Filled in by the patch: the field the params' debug dump reports as isLiveNow. Only params may be passed. */
    public static boolean liveNow(Object params) {
        return false;
    }

    // ------------------------------------------------------------------ hooks

    /** The hook, first thing in FbGrootPlayer's speed setter, whoever calls it. */
    public static void speedSet(Object player, float speed) {
        try {
            HookStatus.invoked(FAMILY);
            if (player == null || !on()) return;
            synchronized (LOCK) {
                lastSetPlayer = new WeakReference<>(player);
                lastSetSpeed = speed;
                lastSetAt = SystemClock.uptimeMillis();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "speed set", failure);
        }
    }

    /**
     * The hook, first thing in the Reels menu's speed toast, which follows a pick. Keeps [speed] when
     * the pick just set it on a player in the Reels viewer; normal speed forgets it. A pick on a
     * player anywhere else changes nothing kept.
     */
    public static void picked(float speed) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) return;
            HookStatus.bound(FAMILY, "speed picked");
            Object player = pickedPlayer(speed, SystemClock.uptimeMillis());
            String origin = player == null ? null : originName(player);
            if (origin != null && !VIEWER.equals(origin)) {
                Logger.printDebug(() -> "Reel speed: " + speed + "x picked in " + origin + ", not the Reels viewer, so not kept");
                return;
            }
            if (same(speed, NORMAL)) {
                synchronized (LOCK) {
                    kept = NORMAL;
                }
                Logger.printDebug(() -> "Reel speed: normal speed picked, reels start as Facebook starts them");
                return;
            }
            if (origin == null) {
                Logger.printDebug(() -> "Reel speed: " + speed + "x picked, but no reel player took it");
                return;
            }
            synchronized (LOCK) {
                kept = speed;
            }
            Logger.printDebug(() -> "Reel speed: keeping " + speed + "x for the Reels viewer");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "speed picked", failure);
        }
    }

    /** The hook, first thing where FbGrootPlayer binds a video. The next start is a new reel's. */
    public static void bound(Object player) {
        try {
            HookStatus.invoked(FAMILY);
            if (player == null || !on()) return;
            BOUND.arm(player, SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player bind", failure);
        }
    }

    /**
     * The hook, first thing in FbGrootPlayer's maybeTrackVideoStart, which runs once the player has
     * started playing. The first start after a bind, on a player in the Reels viewer playing a reel
     * that's neither an ad nor live, gets the kept speed.
     */
    public static void started(Object player) {
        try {
            HookStatus.invoked(FAMILY);
            if (player == null || !on() || !BOUND.armed(player)) return;
            BOUND.disarm(player);
            float speed;
            synchronized (LOCK) {
                speed = kept;
            }
            if (same(speed, NORMAL) || !VIEWER.equals(originName(player)) || !plainReel(player)) return;
            HookStatus.bound(FAMILY, "player start");
            access.setSpeed(player, speed);
            HookStatus.counted(FAMILY, APPLIED);
            Logger.printDebug(() -> "Reel speed: next reel started at " + speed + "x");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player start", failure);
        }
    }

    // ------------------------------------------------------------------ the rule

    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_REEL_SPEED.get();
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < SAME;
    }

    /** Whether [player]'s video is a reel, not an ad and not live, by its VideoPlayerParams. */
    private static boolean plainReel(Object player) {
        return access.reel(player) && !access.ad(player) && !access.live(player);
    }

    /** The player a pick of [speed] at [now] set its speed on, or null when none did just before. */
    @Nullable
    static Object pickedPlayer(float speed, long now) {
        synchronized (LOCK) {
            Object player = lastSetPlayer == null ? null : lastSetPlayer.get();
            if (player == null || !same(lastSetSpeed, speed) || now < lastSetAt || now - lastSetAt > PICK_WINDOW_MS) {
                return null;
            }
            return player;
        }
    }

    /**
     * The viewer a player plays in: its PlayerOrigin's origin, what its toString() writes before
     * "::" (the part after names where in the viewer it started, which can differ from reel to reel).
     */
    @Nullable
    static String originName(Object player) {
        Object origin = access.origin(player);
        if (origin == null) return null;
        String name = origin.toString();
        int cut = name.indexOf("::");
        name = cut < 0 ? name : name.substring(0, cut);
        return name.isEmpty() ? null : name;
    }

    /** The kept speed, or {@link #NORMAL} when none is kept. For tests. */
    static float kept() {
        synchronized (LOCK) {
            return kept;
        }
    }

    /** Forgets the kept speed, the last speed set and every bound player. For tests. */
    static void forget() {
        synchronized (LOCK) {
            lastSetPlayer = null;
            lastSetSpeed = NORMAL;
            lastSetAt = Long.MIN_VALUE;
            kept = NORMAL;
        }
        BOUND.clear();
        access = PATCHED;
    }
}

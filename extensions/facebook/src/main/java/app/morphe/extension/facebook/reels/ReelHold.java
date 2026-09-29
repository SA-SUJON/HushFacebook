/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.view.MotionEvent;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hold a reel for 2x: a reel you hold plays at double speed until you let go.
 *
 * <p>Facebook's Reels controls already have this, behind server flags most accounts don't get: a
 * long press on a reel's left or right edge speeds it up, and the reel's touch listener puts the
 * speed back when the finger lifts. Without the flags, a long press opens Facebook's long-press
 * menu instead. The patch hands this class Facebook's answers where the controls decide:
 *
 * <ul>
 *   <li>{@link #longPress}: the speed-up flag, where a long press on a reel chooses between the
 *       speed-up and the menu. Yes while the switch is on, so the hold speeds the reel up instead
 *       of opening the menu. The menu is still one tap away on the reel's more button.</li>
 *   <li>{@link #anywhere}: the check of whether the press landed on an edge. Yes while the switch
 *       is on, so a hold anywhere on the reel counts.</li>
 *   <li>{@link #speedUp}: the speed-up flag where the controls decide whether to give a reel its
 *       release listener. Yes while the switch is on, so every reel has one.</li>
 *   <li>{@link #release}: both flags the release listener asks before it puts the speed back.
 *       Yes only during the gesture a hold began, since the listener hears every touch and would
 *       otherwise put back the speed the reel had before its last hold, undoing a speed picked in
 *       the menu since then. {@link #touch} sees each gesture start.</li>
 * </ul>
 *
 * <p>A tap still plays or pauses, a double tap and the side buttons work as before, and reels that
 * are ads keep Facebook's long-press menu. Off, paused, before the settings are ready, or when
 * anything here fails, every answer is Facebook's own.
 */
public final class ReelHold {
    /** Counted under the patch's name for each long press on a reel while the switch is on. */
    static final String HELD = "hold on a reel";

    private static final String FAMILY = FamilyNames.HOLD_REEL_FOR_2X;

    /** Whether the gesture going on now began a hold. A new gesture starts without one. */
    private static volatile boolean holding;

    private ReelHold() {
    }

    /** The hook, first thing in FbFragmentActivity.dispatchTouchEvent: a finger landing starts a gesture. */
    public static void touch(MotionEvent event) {
        try {
            if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) holding = false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "touch", failure);
        }
    }

    /** After the long-press handler asks Facebook's speed-up flag. Yes while on, and the gesture is a hold. */
    public static boolean longPress(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        if (!on("long press")) return facebooks;
        holding = true;
        HookStatus.counted(FAMILY, HELD);
        Logger.printDebug(() -> "Reel hold: a long press on a reel goes to the speed-up");
        return true;
    }

    /** After Facebook's check of whether a long press landed on a reel's edge. Yes while on. */
    public static boolean anywhere(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        return on("edge check") || facebooks;
    }

    /** After the controls ask the speed-up flag to decide on a reel's release listener. Yes while on. */
    public static boolean speedUp(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        return on("release listener") || facebooks;
    }

    /** After the release listener asks either flag. While on, yes only during a gesture a hold began. */
    public static boolean release(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        return on("release") ? holding : facebooks;
    }

    /** Whether the switch is on, with [where] bound. Never throws: a failure is reported and reads off. */
    private static boolean on(String where) {
        try {
            if (!Utils.settingsReady() || !Settings.HOLD_REEL_FOR_2X.get()) return false;
            HookStatus.bound(FAMILY, where);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, where, failure);
            return false;
        }
    }

    /** Forgets the gesture. For tests. */
    static void forget() {
        holding = false;
    }
}

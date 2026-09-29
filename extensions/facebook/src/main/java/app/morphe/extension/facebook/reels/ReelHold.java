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
 *       of opening the menu. The menu is still one tap away on the reel's more button. Ads open
 *       the menu whatever the flag says.</li>
 *   <li>{@link #held}: the handler has taken the speed-up path, past the flag, the ad check and
 *       the edge check. That's a hold.</li>
 *   <li>{@link #anywhere}: the check of whether the press landed on an edge. Yes while the switch
 *       is on, so a hold anywhere on the reel counts.</li>
 *   <li>{@link #speedUp}: the speed-up flag where the controls decide whether to give a reel its
 *       release listener. Yes while the switch is on, so every reel has one.</li>
 *   <li>{@link #release}: both flags the release listener asks before it puts the speed back.
 *       Yes from a hold until a lift the listener hears, since it hears every touch and would
 *       otherwise put back the speed the reel had before its last hold, undoing a speed picked in
 *       the menu since then. It puts the speed back on a lift or a cancel, so once one reaches it
 *       during a hold, the hold is over from the next gesture on. A lift it doesn't hear leaves the
 *       hold for the next one: the listener is drawn with the reel, and a lift before the speed-up
 *       has it drawn again can reach one that lets it go by, or none. {@link #touch} sees each
 *       gesture start and end first.</li>
 * </ul>
 *
 * <p>A tap still plays or pauses, a double tap and the side buttons work as before, and reels that
 * are ads keep Facebook's long-press menu. Off, paused, before the settings are ready, or when
 * anything here fails, every answer is Facebook's own.
 */
public final class ReelHold {
    /** Counted under the patch's name for each long press on a reel that went to the speed-up while the switch is on. */
    static final String HELD = "hold on a reel";

    private static final String FAMILY = FamilyNames.HOLD_REEL_FOR_2X;

    /** Whether a hold sped a reel up and the speed hasn't gone back since. */
    private static volatile boolean holding;

    /** Whether the gesture going on now has lifted or been cancelled. */
    private static volatile boolean lifted;

    /** Whether the release listener heard a lift during a hold, so the speed went back. The next gesture ends the hold. */
    private static volatile boolean restored;

    private ReelHold() {
    }

    /**
     * The hook, first thing in FbFragmentActivity.dispatchTouchEvent, before any view hears the
     * event: a finger landing starts a gesture, and ends a hold whose speed went back; the last
     * finger lifting or a cancel ends the gesture.
     */
    public static void touch(MotionEvent event) {
        try {
            if (event == null) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (restored) holding = false;
                restored = false;
                lifted = false;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lifted = true;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "touch", failure);
        }
    }

    /** After the long-press handler asks Facebook's speed-up flag. Yes while on. */
    public static boolean longPress(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        return on("long press") || facebooks;
    }

    /**
     * The hook, straight after the long-press handler loads its "speed_up" log name, which it does
     * only on its way to the speed-up: past the flag, the ad check and the edge check. A hold.
     */
    public static void held() {
        HookStatus.invoked(FAMILY);
        if (!on("hold")) return;
        holding = true;
        restored = false;
        HookStatus.counted(FAMILY, HELD);
        Logger.printDebug(() -> "Reel hold: a long press on a reel went to the speed-up");
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

    /**
     * After the release listener asks either flag. While on, yes from a hold until a lift the listener
     * hears, whose two questions both get yes; the gesture after it starts with the hold over.
     */
    public static boolean release(boolean facebooks) {
        HookStatus.invoked(FAMILY);
        if (!on("release")) return facebooks;
        if (holding && lifted) restored = true;
        return holding;
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

    /** Forgets the hold and the gesture. For tests. */
    static void forget() {
        holding = false;
        lifted = false;
        restored = false;
    }
}

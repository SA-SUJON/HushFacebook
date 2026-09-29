/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Open Messenger from the top bar patch asks before Facebook handles a tap on its
 * Messenger icon.
 *
 * <p>The icon at the top of the feed and of the other tabs opens Facebook's own Chats. The patch
 * runs {@link #open} first in the icon's tap, and in the Messenger button handler the older title
 * bar shares with it. While the switch is on and Messenger is installed, a tap starts Messenger's
 * launcher entry, the intent its home screen icon sends, and Facebook's own handling is skipped.
 * Any app may start a launcher entry, so nothing about the key Messenger is signed with matters.
 *
 * <p>It fails open: with the switch off, a pause, settings that aren't ready, a long press (Facebook
 * has its own use for that), no Messenger with a launcher entry, a start Messenger turns down, or
 * any failure in here, Facebook handles the tap as it always did.
 *
 * <p>A long press comes in two ways. Where Facebook gives the icon a long-click listener, which a
 * MobileConfig flag decides, the tap is told so. Where it doesn't, the top bar takes a press held on
 * the icon as a plain tap once the finger lifts, and Facebook opens Chats for it. The tap can't tell
 * that one apart, so the patch also hands {@link #touch} every touch on a Facebook screen before
 * Facebook sees it. A tap that comes as the release of a finger held past the long-press time is
 * left to Facebook too.
 */
public final class MessengerIcon {
    /** Counted under the patch's name when a tap opened Messenger. */
    static final String OPENED = "opened Messenger";

    /** Counted when the switch was on but no enabled Messenger had a launcher entry to start. */
    static final String NO_MESSENGER = "no Messenger to open";

    /** Counted when Android or Messenger turned the start down. */
    static final String REFUSED = "Messenger refused to start";

    /** Counted when a long press was left to Facebook while the switch was on. */
    static final String LONG_PRESS = "long press left to Facebook";

    /** How soon after a held finger lifts a tap still counts as its release. */
    static final long RELEASE_WINDOW_MS = 500;

    /** The source every event of this hook carries in the diagnostic report. */
    private static final String SOURCE = "MessengerIcon";

    private static final long NONE = Long.MIN_VALUE;

    /** When the hook saw the last finger lift after a long press, on the uptime clock, or NONE. */
    private static volatile long heldReleaseSeenAt = NONE;

    private MessengerIcon() {
    }

    /**
     * Injection point, first thing in FbFragmentActivity.dispatchTouchEvent: notes when a finger
     * lifts after staying down for the long-press time, and forgets it when the next one goes down.
     * It only reads the event. Never throws.
     */
    public static void touch(@Nullable MotionEvent event) {
        try {
            if (event == null) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                HookStatus.bound(FamilyNames.MESSENGER_ICON, "touches");
                heldReleaseSeenAt = NONE;
            } else if (action == MotionEvent.ACTION_UP) {
                // Held for as long as the event says, but timed from when it got here, so a busy
                // main thread that hands the lift on late doesn't push the tap out of the window.
                boolean held = event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout();
                heldReleaseSeenAt = held ? SystemClock.uptimeMillis() : NONE;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger icon touches", failure);
        }
    }

    /** Whether a finger held past the long-press time lifted no more than the window before {@code now}. */
    private static boolean heldPressJustLifted(long now) {
        long at = heldReleaseSeenAt;
        return at != NONE && now >= at && now - at <= RELEASE_WINDOW_MS;
    }

    /**
     * Injection point, first thing in the icon's tap. True when Messenger was started, so Facebook
     * skips its own handling of the tap. Never throws.
     *
     * @param context   what Facebook would start Chats from
     * @param longPress whether the icon was long-pressed rather than tapped
     */
    public static boolean open(@Nullable Context context, boolean longPress) {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            if (context == null) return false;
            if (!Utils.settingsReady() || !Settings.OPEN_MESSENGER_APP.get()) return false;
            if (longPress || heldPressJustLifted(SystemClock.uptimeMillis())) {
                HookStatus.counted(FamilyNames.MESSENGER_ICON, LONG_PRESS);
                return false;
            }
            // Null unless Messenger is installed, enabled and has a MAIN/LAUNCHER (or INFO) activity.
            // The intent comes with FLAG_ACTIVITY_NEW_TASK, so Messenger opens in its own task.
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(MessengerCard.MESSENGER);
            if (launch == null) {
                HookStatus.counted(FamilyNames.MESSENGER_ICON, NO_MESSENGER);
                return false;
            }
            try {
                context.startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException refused) {
                // Messenger went away since the lookup, or the start was refused. Only the class is
                // reported: the exception's own text quotes the intent.
                HookStatus.counted(FamilyNames.MESSENGER_ICON, REFUSED);
                final String kind = refused.getClass().getSimpleName();
                Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                        () -> "Messenger didn't start (" + kind + "). Facebook's Chats opens instead.", null);
                return false;
            }
            HookStatus.counted(FamilyNames.MESSENGER_ICON, OPENED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger icon", failure);
            return false;
        }
    }
}

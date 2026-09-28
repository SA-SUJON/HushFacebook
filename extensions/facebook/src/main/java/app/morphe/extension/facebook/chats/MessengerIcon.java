/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

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
 */
public final class MessengerIcon {
    /** Counted under the patch's name when a tap opened Messenger. */
    static final String OPENED = "opened Messenger";

    /** Counted when the switch was on but no enabled Messenger had a launcher entry to start. */
    static final String NO_MESSENGER = "no Messenger to open";

    /** Counted when Android or Messenger turned the start down. */
    static final String REFUSED = "Messenger refused to start";

    /** The source every event of this hook carries in the diagnostic report. */
    private static final String SOURCE = "MessengerIcon";

    private MessengerIcon() {
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
            if (longPress || context == null) return false;
            if (!Utils.settingsReady() || !Settings.OPEN_MESSENGER_APP.get()) return false;
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

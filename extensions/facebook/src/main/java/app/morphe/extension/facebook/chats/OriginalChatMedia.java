/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 * Ported from https://github.com/SysAdminDoc/HushMessenger (its original photo and original video controls)
 */
package app.morphe.extension.facebook.chats;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import java.io.IOException;
import java.util.Map;

/**
 * What the Send chat photos and videos at original quality patch asks before a chat inside Facebook
 * shrinks what it sends.
 *
 * <p>Photos: the chat's transcoder re-encodes every photo. While the switch is on, a JPEG goes out
 * as a copy of its own image data (see {@link OriginalPhoto}), without its metadata except the
 * rotation tag. Videos: the transcoder only skips the re-encode for a file smaller than its target
 * size plus a little, and the size check's answer comes through {@link #videoPassthrough}, which
 * says "smaller" for any file under {@link #VIDEO_MAX_BYTES}. Trims, overlays, muting and the
 * forced transcode still take their own path.
 *
 * <p>Off, paused, settings that aren't ready yet, a file this can't pass through, or a failure in
 * here, and Facebook does what it meant to.
 */
public final class OriginalChatMedia {
    /** Counted each time a photo goes out as its own image data. */
    static final String PHOTO_SENT = "photo sent as its original";

    /** Counted each time a video's size check is answered "small enough to send as it is". */
    static final String VIDEO_PASSED = "video sent without re-encoding";

    /** Larger videos keep Facebook's transcode, which fits them under the chat's upload limit. */
    static final long VIDEO_MAX_BYTES = 25_000_000;

    private static final String FAMILY = FamilyNames.ORIGINAL_CHAT_MEDIA;

    private OriginalChatMedia() {
    }

    /** Settings that aren't ready yet answer off, and Settings isn't touched until they are. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.ORIGINAL_CHAT_MEDIA.get();
    }

    /** Injection point, first in transcodeImage: the photo's own bytes, or null for Facebook's transcode. */
    public static byte[] photo(String url, double maxWidth, double maxHeight, String options, Map<?, ?> extras) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) return null;
            byte[] original = OriginalPhoto.sync(url, maxWidth, maxHeight, extras);
            if (original != null) HookStatus.counted(FAMILY, PHOTO_SENT);
            return original;
        } catch (IOException ordinary) {
            return null;
        } catch (Throwable failure) {
            failed("photo", failure);
            return null;
        }
    }

    /** Injection point, first in transcodeImageAsync: true when the callback is handled here, false for Facebook's transcode. */
    public static boolean photoAsync(String url, double maxWidth, double maxHeight, String options, Map<?, ?> extras,
            Object callback) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) return false;
            boolean handled = OriginalPhoto.async(url, maxWidth, maxHeight, extras, callback);
            if (handled) HookStatus.counted(FAMILY, PHOTO_SENT);
            return handled;
        } catch (IOException ordinary) {
            return false;
        } catch (Throwable failure) {
            failed("photo", failure);
            return false;
        }
    }

    /**
     * Injection point, right after the video size check's compare: a negative answer lets the file
     * skip the re-encode. A file that already skips, one with no size or one over
     * {@link #VIDEO_MAX_BYTES} gets Facebook's answer back.
     */
    public static int videoPassthrough(int comparison, long bytes) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || comparison < 0 || bytes <= 0 || bytes > VIDEO_MAX_BYTES) return comparison;
            HookStatus.counted(FAMILY, VIDEO_PASSED);
            return -1;
        } catch (Throwable failure) {
            failed("video", failure);
            return comparison;
        }
    }

    /** Records a failure in the diagnostic report. Facebook's own behavior was left alone. */
    static void failed(String what, Throwable failure) {
        HookStatus.threw(FAMILY, what, failure);
    }
}

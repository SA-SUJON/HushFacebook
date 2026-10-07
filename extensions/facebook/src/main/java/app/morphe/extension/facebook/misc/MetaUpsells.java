/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide Meta upsells patch asks wherever Facebook pushes Meta's other products outside the
 * Menu. Four switches, all off by default: Edits (the Reels composer header's button and badge, and
 * the server's Edits pill under feed videos, which the feed's requests stop asking for), Threads
 * cross-posting (the composer's onboarding), Meta Verified (the offer sheet after you post and the
 * label under some posts' headers) and avatar stickers (the upsell components in comments and
 * Facebook's promotion slots).
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, every answer is
 * Facebook's own.
 */
public final class MetaUpsells {
    /** Counted under the patch's name each time one of the four is answered away. */
    static final String EDITS_HIDDEN = "Edits promotion kept out";
    static final String THREADS_HIDDEN = "Threads cross-posting prompt kept out";
    static final String VERIFIED_HIDDEN = "Meta Verified offer kept out";
    static final String AVATAR_HIDDEN = "Avatar sticker promotion kept out";

    private static final String FAMILY = FamilyNames.META_UPSELLS;

    private static volatile boolean logged;

    private MetaUpsells() {
    }

    private static void hid(String hook, String counter) {
        HookStatus.bound(FAMILY, hook);
        HookStatus.counted(FAMILY, counter);
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Meta upsells: a promotion was kept out");
        }
    }

    /**
     * The hook after each read of the Reels composer's two Edits flags, the header button and its
     * badge, handed the flag. Answers false while the Edits switch is on.
     */
    public static boolean editsHeader(boolean show) {
        try {
            HookStatus.invoked(FAMILY);
            if (!show || !(Utils.settingsReady() && Settings.HIDE_EDITS_UPSELLS.get())) return show;
            hid("Edits header flag", EDITS_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Edits header flag", failure);
            return show;
        }
    }

    /**
     * The hook on Facebook's own gate for asking the server for the Edits pill under feed videos,
     * before it goes into the request. Answers false while the Edits switch is on, so the server
     * leaves the pill out.
     */
    public static boolean fetchEditsPill(boolean fetch) {
        try {
            HookStatus.invoked(FAMILY);
            if (!fetch || !(Utils.settingsReady() && Settings.HIDE_EDITS_UPSELLS.get())) return fetch;
            hid("Edits pill request", EDITS_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Edits pill request", failure);
            return fetch;
        }
    }

    /** {@link #fetchEditsPill(boolean)} for the video queries, which pass the gate's answer boxed. */
    @Nullable
    public static Boolean fetchEditsPill(@Nullable Boolean fetch) {
        try {
            HookStatus.invoked(FAMILY);
            if (Boolean.FALSE.equals(fetch) ||
                    !(Utils.settingsReady() && Settings.HIDE_EDITS_UPSELLS.get())) return fetch;
            hid("Edits pill request", EDITS_HIDDEN);
            return Boolean.FALSE;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Edits pill request", failure);
            return fetch;
        }
    }

    /**
     * The hook at each return of the composer capability that decides whether to show the Threads
     * cross-posting onboarding, handed its answer as an int: a boolean method may return a register
     * the verifier types as int. Answers false while the Threads switch is on.
     */
    public static boolean threadsOnboarding(int show) {
        boolean shows = show != 0;
        try {
            HookStatus.invoked(FAMILY);
            if (!shows || !(Utils.settingsReady() && Settings.HIDE_THREADS_CROSS_POSTING.get())) return shows;
            hid("Threads cross-posting onboarding", THREADS_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Threads cross-posting onboarding", failure);
            return shows;
        }
    }

    /**
     * The hook at each return of the after-post Meta Verified sheet's eligibility check, a suspend
     * method: handed either its answer, a Boolean, or Kotlin's marker for "not yet", which passes
     * untouched. A yes becomes a no while the Meta Verified switch is on, so the sheet isn't shown.
     */
    @Nullable
    public static Object metaVerifiedSheet(@Nullable Object answer) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Boolean.TRUE.equals(answer) ||
                    !(Utils.settingsReady() && Settings.HIDE_META_VERIFIED_UPSELLS.get())) return answer;
            hid("Meta Verified sheet eligibility", VERIFIED_HIDDEN);
            return Boolean.FALSE;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Meta Verified sheet eligibility", failure);
            return answer;
        }
    }

    /**
     * The hook where Facebook's header subtitle plugins ask whether a post wants its Meta Verified
     * label, handed the label's text. Answers no text, so no label, while the Meta Verified switch is
     * on.
     */
    @Nullable
    public static String metaVerifiedLabel(@Nullable String label) {
        try {
            HookStatus.invoked(FAMILY);
            if (label == null || !(Utils.settingsReady() && Settings.HIDE_META_VERIFIED_UPSELLS.get())) return label;
            hid("Meta Verified label", VERIFIED_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Meta Verified label", failure);
            return label;
        }
    }

    /**
     * Asked first thing by each avatar sticker upsell component as it draws. A yes, while the avatar
     * sticker switch is on, makes it draw nothing.
     */
    public static boolean hidesAvatarUpsell() {
        try {
            HookStatus.invoked(FAMILY);
            if (!(Utils.settingsReady() && Settings.HIDE_AVATAR_UPSELLS.get())) return false;
            hid("avatar sticker upsell", AVATAR_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "avatar sticker upsell", failure);
            return false;
        }
    }
}

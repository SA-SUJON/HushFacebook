/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide Meta upsells patch asks wherever Facebook pushes Meta's other products outside the
 * Menu. Five switches, all off by default: Edits (the Reels composer header's button and badge, and
 * the server's Edits pill under feed videos, which the feed's requests stop asking for), Threads
 * cross-posting (the composer's onboarding), Meta Verified (the offer sheet after you post and the
 * label under some posts' headers), avatar stickers (the upsell components in comments and
 * Facebook's promotion slots) and Meta AI's Imagine (the Imagine me button under posts, the post
 * composer's Imagine and Create story's Imagine tile).
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, every answer is
 * Facebook's own.
 */
public final class MetaUpsells {
    /** Counted under the patch's name each time one of the five is answered away. */
    static final String EDITS_HIDDEN = "Edits promotion kept out";
    static final String THREADS_HIDDEN = "Threads cross-posting prompt kept out";
    static final String VERIFIED_HIDDEN = "Meta Verified offer kept out";
    static final String AVATAR_HIDDEN = "Avatar sticker promotion kept out";
    static final String IMAGINE_HIDDEN = "Imagine entry kept out";

    /** The post call-to-action plugin for Imagine me, as the CTA selector's name table gives it. */
    public static final String IMAGINE_ME_PLUGIN =
            "com.facebook.feed.plugins.calltoaction.impl.imagineme.ImagineMePlugin";

    /** The name of Create story's Imagine tool, a constant of Facebook's enum of story tools. */
    static final String STORY_IMAGINE = "IMAGINE";

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

    /**
     * The hook, first thing in the post call-to-action selector's check of whether a plugin
     * applies, handed the plugin's name. True answers no for the Imagine me button while the Imagine
     * switch is on, so the selector goes on to the next button; false leaves the check to Facebook.
     */
    public static boolean hidesImagineCta(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!IMAGINE_ME_PLUGIN.equals(plugin)) return false;
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_IMAGINE.get()) return false;
            hid("Imagine me button", IMAGINE_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Imagine me button", failure);
            return false;
        }
    }

    /**
     * The hook after each time the post composer asks whether its Imagine capability is on, handed
     * the answer. Answers false while the Imagine switch is on, so the composer has no Imagine.
     */
    public static boolean imagineCapability(boolean on) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on || !Utils.settingsReady() || !Settings.HIDE_META_AI_IMAGINE.get()) return on;
            hid("composer Imagine capability", IMAGINE_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "composer Imagine capability", failure);
            return on;
        }
    }

    /**
     * The hook on the list of tools Create story builds its row of tiles from, constants of
     * Facebook's enum. Answers the same tools in the same order without Imagine while the Imagine
     * switch is on, and the list it was handed otherwise. The patch copies the answer back into an
     * ImmutableList.
     */
    @Nullable
    public static List<?> storyTools(@Nullable List<?> tools) {
        try {
            HookStatus.invoked(FAMILY);
            if (tools == null || tools.isEmpty()) return tools;
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_IMAGINE.get()) return tools;
            List<Object> kept = null;
            for (int index = 0; index < tools.size(); index++) {
                Object tool = tools.get(index);
                if (tool instanceof Enum && STORY_IMAGINE.equals(((Enum<?>) tool).name())) {
                    if (kept == null) kept = new ArrayList<>(tools.subList(0, index));
                } else if (kept != null) {
                    kept.add(tool);
                }
            }
            if (kept == null) return tools;
            hid("Create story Imagine tile", IMAGINE_HIDDEN);
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Create story tools", failure);
            return tools;
        }
    }
}

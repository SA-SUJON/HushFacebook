/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The row of Meta AI questions Facebook puts under some posts, like "How to earn Mythic
 * Achievements?" under a news link (issue #48). Facebook calls it a deep dive pill. One socket
 * draws every kind of pill: it goes through the pill plugins in a fixed order, asks each whether
 * it applies to the post, and draws the first one that does. Meta AI's questions are
 * GenAiDeepDivePillPlugin, a class name Facebook keeps and loads into the same register for each
 * plugin it asks about. With the switch on, the answer for Meta AI's plugin is a no, so the socket
 * goes on to the next plugin, and a post with only Meta AI's pill gets no row. The post's link card,
 * text and buttons are other parts of the post and stay.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class MetaAiQuestions {
    /** The class name of Meta AI's pill plugin, as the socket names each plugin it asks about. */
    public static final String META_AI_PILL =
            "com.facebook.feed.plugins.attachments.deepdivepill.impl.genai.GenAiDeepDivePillPlugin";

    /** Counted under the patch's name each time Meta AI's question row is answered away. */
    static final String HIDDEN = "Meta AI question row kept out";

    /** The member the report names once the socket has asked about Meta AI's plugin. */
    static final String CHECK = "pill check";

    private static final String FAMILY = FamilyNames.META_AI_QUESTIONS;

    private static volatile boolean logged;

    private MetaAiQuestions() {
    }

    /**
     * The hook, after each of the socket's checks of whether a pill plugin applies to a post, handed
     * the check's answer as an int (a boolean register the verifier may type as int) and the name of
     * the plugin it asked about. Answers false for Meta AI's plugin while the switch is on, and the
     * check's own answer for every other plugin and otherwise.
     */
    public static boolean keep(int applies, Object plugin) {
        boolean answer = applies != 0;
        try {
            HookStatus.invoked(FAMILY);
            if (!META_AI_PILL.equals(plugin)) return answer;
            HookStatus.bound(FAMILY, CHECK);
            if (!answer || !Utils.settingsReady() || !Settings.HIDE_META_AI_QUESTIONS.get()) return answer;
            HookStatus.counted(FAMILY, HIDDEN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Meta AI questions: a post's row of Meta AI questions was kept out");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHECK, failure);
            return answer;
        }
    }
}

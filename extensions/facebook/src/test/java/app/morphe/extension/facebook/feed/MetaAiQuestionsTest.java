/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide Meta AI questions under posts: with the switch on, the pill socket's yes for Meta AI's
 * plugin is answered as a no, and counted; every other plugin, the affiliate one included, keeps
 * the socket's answer, and so does Meta AI's no. Off or paused, Facebook's yes stands.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MetaAiQuestionsTest {
    private static final String AFFILIATE =
            "com.facebook.feed.plugins.attachments.deepdivepill.impl.affiliate.AffiliatePlugin";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_META_AI_QUESTIONS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.META_AI_QUESTIONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void metaAisRowIsAnsweredAwayAndEveryOtherPillStays() {
        assertTrue("the switch doesn't start on", Settings.HIDE_META_AI_QUESTIONS.get());
        assertFalse("Meta AI's questions kept their row", MetaAiQuestions.keep(1, MetaAiQuestions.META_AI_PILL));
        assertFalse("Meta AI's no became a yes", MetaAiQuestions.keep(0, MetaAiQuestions.META_AI_PILL));
        assertTrue("the affiliate pill lost its row", MetaAiQuestions.keep(1, AFFILIATE));
        assertFalse(MetaAiQuestions.keep(0, AFFILIATE));
        assertEquals(FamilyNames.META_AI_QUESTIONS + ": invoked 4, 1 found, 0 missing. Counted: "
                + MetaAiQuestions.HIDDEN + " 1", statusLine());
    }

    @Test
    public void offOrPausedMetaAisRowStays() {
        Settings.HIDE_META_AI_QUESTIONS.save(false);
        assertTrue(MetaAiQuestions.keep(1, MetaAiQuestions.META_AI_PILL));
        Settings.HIDE_META_AI_QUESTIONS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue("a Hushfacebook paused by " + reason + " hid Meta AI's row",
                    MetaAiQuestions.keep(1, MetaAiQuestions.META_AI_PILL));
            PauseForTests.resume();
        }
    }

    /** A name the hook can't read as Meta AI's, or no name at all, keeps the socket's answer. */
    @Test
    public void anythingButMetaAisNameKeepsTheAnswer() {
        assertTrue(MetaAiQuestions.keep(1, null));
        assertTrue(MetaAiQuestions.keep(1, new Object()));
        assertTrue(MetaAiQuestions.keep(1, "GenAiDeepDivePillPlugin"));
        assertEquals(FamilyNames.META_AI_QUESTIONS + ": invoked 3, 0 found, 0 missing", statusLine());
    }
}

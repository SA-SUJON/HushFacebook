/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

import com.facebook.stories.viewer.activity.StoryViewerActivity;

/**
 * The Mark as seen button: it follows the card the seen helper is about to count, shows over the
 * story viewer only with both switches on, and a tap marks the card or takes the mark back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StorySeenButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        StorySeenForTests.reset();
        StorySeenButton.cardIds = card -> (String) card;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        StorySeenForTests.reset();
    }

    private static ImageView eyeIn(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof StorySeenButton.Eye) {
                return (ImageView) child;
            }
        }
        return null;
    }

    @Test
    public void offOrPausedTheButtonFollowsNoCard() {
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertNull("the button's switch starts off", StorySeenButton.shown());
        Settings.MARK_STORIES_SEEN.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertNull(StorySeenButton.shown());
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertNull("the button showed with views going out", StorySeenButton.shown());
    }

    @Test
    public void onTheButtonFollowsTheCardOnItsAccount() {
        Settings.MARK_STORIES_SEEN.save(true);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertEquals("c1", StorySeenButton.shown().card);
        assertEquals("100", StorySeenButton.shown().account);
        StorySeenButton.onCard(new Object(), null, "c2");
        assertNull("a card with no account kept the button", StorySeenButton.shown());
    }

    @Test
    public void theEyeShowsOverTheStoryViewerAndATapMarksTheCard() {
        Settings.MARK_STORIES_SEEN.save(true);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();

        ImageView eye = eyeIn(viewer);
        assertNotNull("no eye over the story viewer", eye);
        assertEquals(View.VISIBLE, eye.getVisibility());
        assertEquals("Mark as seen", eye.getContentDescription());
        assertTrue(eye.performClick());
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertEquals(StoryMarks.State.MARKED, ((StorySeenButton.Eye) eye.getDrawable()).state());
        assertEquals("Marked as seen. Tap again to undo.", eye.getContentDescription());
        assertTrue(eye.performClick());
        assertEquals("a second tap kept the mark", StoryMarks.State.UNMARKED, StorySeen.MARKS.state("100", "c1"));

        // Once sent, the eye dims and a tap changes nothing.
        StorySeen.MARKS.toggle("100", "c1");
        StorySeenForTests.send(StorySeenForTests.ACCOUNT, StorySeenForTests.cards("c1"));
        StorySeenButton.refresh();
        assertFalse(eye.isEnabled());
        assertEquals("Marked as seen and sent", eye.getContentDescription());

        StorySeenButton.activityPaused(viewer);
        assertEquals("the eye stayed after the viewer left", View.GONE, eye.getVisibility());
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c2");
        ShadowLooper.idleMainLooper();
        assertEquals("the eye came back without the viewer in front", View.GONE, eye.getVisibility());
        controller.pause().stop().destroy();
    }

    @Test
    public void anotherActivityNeverGetsTheEye() {
        Settings.MARK_STORIES_SEEN.save(true);
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        StorySeenButton.activityResumed(other);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        assertNull(eyeIn(other));
    }

    @Test
    public void marksLapseAfterADay() {
        AtomicLong now = new AtomicLong(1_000);
        StoryMarks marks = new StoryMarks(now::get);
        marks.toggle("100", "c1");
        now.addAndGet(StoryMarks.LIFETIME_MS);
        assertSame(StoryMarks.State.UNMARKED, marks.state("100", "c1"));
        assertNull("a lapsed mark went out", marks.choose("100", StorySeenForTests.cards("c1"),
                new StorySeen.Call(null, null, null, null, null, null, false)));
    }
}

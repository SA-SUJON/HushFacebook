/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.modelutil.BaseModelWithTree;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** Hide posts from people, Pages and sites: the list, the read of a post, and the rule in the feed guard. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostSourcesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Map<Object, List<?>> authors = new HashMap<>();
    private final Map<Object, List<?>> links = new HashMap<>();
    private final StoryFlag.Accessor actors = authors::get;
    private final StoryFlag.Accessor attachments = links::get;

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_POSTS_FROM_SOURCES.resetToDefault();
        Settings.HIDDEN_SOURCES.resetToDefault();
    }

    private static BaseModelWithTree author(String id, String name) {
        return new BaseModelWithTree(0).with("id", id).with("name", name);
    }

    private static BaseModelWithTree link(String url) {
        return new BaseModelWithTree(0).with("url", url);
    }

    private GraphQLStory story(GraphQLStory shared, List<?> by, List<?> linking) {
        GraphQLStory story = new GraphQLStory(shared);
        authors.put(story, by);
        links.put(story, linking);
        return story;
    }

    private PostSources.Found read(Object unit) {
        return PostSources.read(unit, actors, attachments, story -> ((GraphQLStory) story).A04());
    }

    @Test
    public void eachLineIsAnIdASiteOrAName() {
        assertEquals(PostSources.Kind.ID, PostSources.rule("100044218155390", 1).kind);
        PostSources.Rule site = PostSources.rule("https://www.Example.com/news?x=1", 2);
        assertEquals(PostSources.Kind.SITE, site.kind);
        assertEquals("example.com", site.value);
        PostSources.Rule name = PostSources.rule("  Daily   Bugle ", 3);
        assertEquals(PostSources.Kind.NAME, name.kind);
        assertEquals("daily bugle", name.value);
        assertEquals("a name with a dot stays a name", PostSources.Kind.NAME, PostSources.rule("Dr. Ana Ruiz", 4).kind);
        assertEquals("two digits are a name", PostSources.Kind.NAME, PostSources.rule("42", 5).kind);
        assertNull(PostSources.rule("   ", 6));
        assertNull(PostSources.rule(String.join("", Collections.nCopies(PostSources.MAX_LENGTH + 1, "a")), 7));
    }

    @Test
    public void cleanKeepsOneRulePerLineInOrderAndSaysHowManyItLeftOut() {
        String typed = "example.com\n\n  Daily   Bugle \nEXAMPLE.com\nwww.example.com\n123456\n" + "x".repeat(101);
        assertEquals("example.com\nDaily Bugle\n123456", PostSources.clean(typed));
        assertEquals(3, PostSources.leftOut(typed));
        assertTrue(PostSources.isClean("example.com\nDaily Bugle"));
        assertFalse(PostSources.isClean("example.com\n\nDaily Bugle"));
        assertEquals(3, PostSources.count("example.com\nDaily Bugle\n123456"));
    }

    @Test
    public void theListStopsAtItsRuleCountAndItsRoom() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < PostSources.MAX_RULES + 5; i++) many.append("site").append(i).append(".com\n");
        assertEquals(PostSources.MAX_RULES, PostSources.count(PostSources.clean(many.toString())));
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < PostSources.MAX_RULES; i++) wide.append("名".repeat(PostSources.MAX_LENGTH - 4)).append(i).append('\n');
        String clean = PostSources.clean(wide.toString());
        assertTrue(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PostSources.MAX_LIST_BYTES);
        assertTrue(PostSources.count(clean) < PostSources.MAX_RULES);
    }

    @Test
    public void aLinkCountsAsItsSiteEvenThroughFacebooksRedirect() {
        assertEquals("example.com", PostSources.host("https://www.example.com/a/b"));
        assertEquals("news.example.com", PostSources.host("http://news.example.com"));
        assertEquals("example.org", PostSources.host(
                "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.example.org%2Fstory&h=AT0"));
        assertEquals("l.facebook.com", PostSources.host("https://l.facebook.com/other"));
        assertNull(PostSources.host("not a link"));
        assertNull(PostSources.host(null));
    }

    @Test
    public void aRuleMatchesAnAuthorsIdOrNameOrALinksSiteAndItsSubdomains() {
        GraphQLStory post = story(null, Arrays.asList(author("100044218155390", "Daily Bugle")),
                Arrays.asList(link("https://news.example.com/x")));
        PostSources.Found found = read(post);
        assertEquals(PostSources.Outcome.READ, found.outcome);
        assertEquals(1, PostSources.match(PostSources.rules("100044218155390"), found));
        assertEquals(2, PostSources.match(PostSources.rules("other.com\ndaily BUGLE"), found));
        assertEquals(1, PostSources.match(PostSources.rules("example.com"), found));
        assertEquals("a site's name inside another doesn't match",
                0, PostSources.match(PostSources.rules("ample.com\nDaily"), found));
        assertEquals(0, PostSources.match(PostSources.rules("10004421815539"), found));
    }

    @Test
    public void aShareMatchesTheSharedPostsAuthorAndLinks() {
        GraphQLStory original = story(null, Arrays.asList(author("555", "Daily Bugle")),
                Arrays.asList(link("https://example.org/a")));
        GraphQLStory share = story(original, Arrays.asList(author("777", "A Friend")), Collections.emptyList());
        PostSources.Found found = read(share);
        assertEquals(1, PostSources.match(PostSources.rules("Daily Bugle"), found));
        assertEquals(1, PostSources.match(PostSources.rules("example.org"), found));
        assertEquals(1, PostSources.match(PostSources.rules("A Friend"), found));
    }

    @Test
    public void whatCantBeReadKeepsThePost() {
        assertEquals(PostSources.Outcome.NO_UNIT, read(null).outcome);
        assertEquals(PostSources.Outcome.NOT_A_STORY, read(new Object()).outcome);
        GraphQLStory post = new GraphQLStory();
        assertEquals("unpatched stubs", PostSources.Outcome.NO_ACCESSOR,
                PostSources.read(post, PostSources.ACTORS, PostSources.ATTACHMENTS, PostText.ATTACHED).outcome);
        StoryFlag.Accessor throwing = story -> {
            throw new IllegalStateException("renamed");
        };
        assertEquals(PostSources.Outcome.READ_FAILED, PostSources.read(post, throwing, attachments, s -> null).outcome);
        // A story with no authors or links reads cleanly as nothing to match.
        PostSources.Found empty = PostSources.read(post, s -> null, s -> null, s -> null);
        assertEquals(PostSources.Outcome.READ, empty.outcome);
        assertEquals(0, PostSources.match(PostSources.rules("example.com\nDaily Bugle"), empty));
    }

    @Test
    public void theFeedGuardHidesAMatchOnlyWithTheSwitchOnAndAListAndNoPause() {
        GraphQLStory post = story(null, Arrays.asList(author("555", "Daily Bugle")), Collections.emptyList());
        assertFalse("the switch starts off", Settings.HIDE_POSTS_FROM_SOURCES.get());
        assertNull(FeedFilter.sourcesReason(post, actors, attachments, s -> null));

        Settings.HIDE_POSTS_FROM_SOURCES.save(true);
        assertNull("an empty list hides nothing", FeedFilter.sourcesReason(post, actors, attachments, s -> null));
        Settings.HIDDEN_SOURCES.save("other.com\nDaily Bugle");
        assertEquals(FeedFilter.SOURCES_REASON + " 2", FeedFilter.sourcesReason(post, actors, attachments, s -> null));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(FeedFilter.sourcesReason(post, actors, attachments, s -> null));
    }
}

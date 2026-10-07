/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Topic packs: short word lists the Words to hide editor adds as whole-word patterns, ordinary lines
 * the person can edit. A pack's words match only on their own, so an everyday post from a friend
 * stays, and its topic's posts still go. Adding skips a line that's already there, stops at the
 * list's limits and shared room, and keeps what was typed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TopicPacksTest {
    @Test
    public void everyPackIsShortAndEveryLineIsAWholeWordPattern() {
        Set<String> names = new HashSet<>();
        for (TopicPacks.Pack pack : TopicPacks.Pack.values()) {
            List<String> words = pack.words();
            assertTrue(pack + " has " + words.size(), words.size() >= 10 && words.size() <= 40);
            assertTrue(names.add(pack.name()));
            assertEquals(pack + " repeats a word", words.size(), new HashSet<>(words).size());
            for (String word : words) {
                assertTrue(word + " isn't plain lowercase text with an optional plural",
                        word.matches("[a-z0-9]([a-z0-9 -]*[a-z0-9])?(s\\?)?"));
            }
            List<String> lines = pack.lines();
            assertEquals(pack + ": a line is out of bounds, a repeat or can't be read", lines,
                    PostWords.phrases(String.join("\n", lines)));
            int counted = 0;
            List<String> joined = new ArrayList<>();
            for (String line : lines) {
                String body = PostWords.patternBody(line);
                assertNotNull(line + " isn't a pattern", body);
                assertTrue(line + " is too long", body.length() <= PostWords.MAX_PATTERN_LENGTH);
                assertTrue(line + " isn't whole words", body.startsWith("\\b(") && body.endsWith(")\\b"));
                joined.addAll(Arrays.asList(body.substring(3, body.length() - 3).split("\\|")));
                counted += TopicPacks.wordsIn(line);
            }
            assertEquals(pack + "'s lines don't hold its words in order", words, joined);
            assertEquals(words.size(), counted);
        }
    }

    @Test
    public void everyPackFitsInOneListTogether() {
        String text = "";
        for (TopicPacks.Pack pack : TopicPacks.Pack.values()) {
            TopicPacks.Result result = TopicPacks.add(text, pack, 0);
            assertFalse(pack + " didn't fit", result.full);
            assertEquals(pack.words().size(), result.added);
            text = result.text;
        }
        PostWords.Size size = PostWords.size(text, 0);
        assertTrue("seven packs don't save", size.fits());
        assertTrue("seven packs leave too few patterns for the person's own: " + size.patterns,
                size.patterns <= PostWords.MAX_PATTERNS - 30);
    }

    @Test
    public void aPackAddsItsLinesAsOrdinaryLines() {
        TopicPacks.Pack pack = TopicPacks.Pack.CRYPTO;
        TopicPacks.Result result = TopicPacks.add("", pack, 0);
        assertEquals("the result counts words", pack.words().size(), result.added);
        assertEquals(0, result.duplicates);
        assertFalse(result.full);
        assertEquals(String.join("\n", pack.lines()), result.text);
        assertTrue("it saves as it stands", PostWords.isClean(result.text));
        assertEquals(pack.lines().size(), PostWords.count(result.text));
    }

    @Test
    public void whatWasTypedStaysFirstAndALineAlreadyThereIsSkipped() {
        TopicPacks.Pack pack = TopicPacks.Pack.CRYPTO;
        String first = pack.lines().get(0);
        TopicPacks.Result result = TopicPacks.add("my own phrase\nBITCOIN\n" + first + "\n", pack, 0);
        List<String> lines = Arrays.asList(result.text.split("\n"));
        assertEquals(Arrays.asList("my own phrase", "BITCOIN", first, pack.lines().get(1)), lines);
        assertEquals(TopicPacks.wordsIn(first), result.duplicates);
        assertEquals(pack.words().size() - TopicPacks.wordsIn(first), result.added);
        assertFalse(result.text.contains("\n\n"));
    }

    @Test
    public void addingThePackAgainChangesNothing() {
        String once = TopicPacks.add("", TopicPacks.Pack.SPORTS, 0).text;
        TopicPacks.Result again = TopicPacks.add(once, TopicPacks.Pack.SPORTS, 0);
        assertEquals(0, again.added);
        assertEquals(TopicPacks.Pack.SPORTS.words().size(), again.duplicates);
        assertFalse(again.full);
        assertEquals(once, again.text);
    }

    @Test
    public void twoPacksAddDistinctLines() {
        String politics = TopicPacks.add("", TopicPacks.Pack.POLITICS, 0).text;
        TopicPacks.Result both = TopicPacks.add(politics, TopicPacks.Pack.ELECTIONS, 0);
        assertEquals(TopicPacks.Pack.ELECTIONS.words().size(), both.added);
        assertEquals(PostWords.count(both.text), PostWords.phrases(both.text).size());
        assertEquals("every line is a distinct phrase", both.text.split("\n").length, PostWords.count(both.text));
    }

    @Test
    public void aPackStopsWhereTheListWouldPassItsPhraseLimit() {
        List<String> filler = new ArrayList<>();
        for (int i = 0; i < PostWords.MAX_PHRASES - 1; i++) filler.add("zzfiller" + i);
        String typed = String.join("\n", filler);
        TopicPacks.Pack pack = TopicPacks.Pack.SPORTS;
        TopicPacks.Result result = TopicPacks.add(typed, pack, 0);
        assertEquals(TopicPacks.wordsIn(pack.lines().get(0)), result.added);
        assertTrue(result.full);
        assertEquals(PostWords.MAX_PHRASES, PostWords.count(result.text));
        assertTrue(PostWords.size(result.text, 0).fits());

        TopicPacks.Result none = TopicPacks.add(result.text, TopicPacks.Pack.POLITICS, 0);
        assertEquals(0, none.added);
        assertTrue(none.full);
        assertEquals("a full list is left as it was", result.text, none.text);
    }

    @Test
    public void aPackStopsWhereTheListWouldPassItsPatternLimit() {
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i < PostWords.MAX_PATTERNS - 1; i++) patterns.add("/zzpattern" + i + "/");
        TopicPacks.Pack pack = TopicPacks.Pack.POLITICS;
        TopicPacks.Result result = TopicPacks.add(String.join("\n", patterns), pack, 0);
        assertEquals(TopicPacks.wordsIn(pack.lines().get(0)), result.added);
        assertTrue(result.full);
        assertTrue(PostWords.size(result.text, 0).fits());
    }

    @Test
    public void aPackStopsWhereTheSharedRoomRunsOut() {
        TopicPacks.Pack pack = TopicPacks.Pack.POLITICS;
        String first = pack.lines().get(0);
        int roomForOneLine = PostWords.MAX_LIST_BYTES - PostWords.encodedBytes(first) - 4;
        TopicPacks.Result result = TopicPacks.add("", pack, roomForOneLine);
        assertTrue(result.full);
        assertEquals("the first line fits and the second doesn't", TopicPacks.wordsIn(first), result.added);
        assertEquals(first, result.text);
        assertTrue(PostWords.size(result.text, roomForOneLine).fits());

        TopicPacks.Result none = TopicPacks.add("", pack, PostWords.MAX_LIST_BYTES - 2);
        assertEquals(0, none.added);
        assertTrue(none.full);
        assertEquals("", none.text);
    }

    @Test
    public void aNullListIsAnEmptyOne() {
        assertEquals(TopicPacks.Pack.ELECTIONS.words().size(), TopicPacks.add(null, TopicPacks.Pack.ELECTIONS, 0).added);
    }

    /** Every pack's lines in one list, as someone who picked them all would have it. */
    private static String everyPack() {
        String text = "";
        for (TopicPacks.Pack pack : TopicPacks.Pack.values()) text = TopicPacks.add(text, pack, 0).text;
        return text;
    }

    /**
     * Posts a friend writes about their own life, each with a word a pack used to hold or a word a
     * pack's term sits inside. None is hidden with every pack added, whole-word matching on or off.
     */
    @Test
    public void everydayPostsStayWithEveryPackAdded() {
        List<String> everyday = Arrays.asList(
                "Love you to the moon and back",
                "We just got engaged!",
                "Kitchen remodel before and after",
                "Giving away our old couch, free to a good home",
                "She recounted the whole story at dinner",
                "Touchdown in Denver, see you all soon",
                "Thanks for the gift card, Aunt Jo",
                "Raffle tickets for the school fundraiser are on sale",
                "Our baby bump at 20 weeks",
                "Digital detox this weekend",
                "My tummy hurts after that pie",
                "I'm a free agent this Saturday if anyone needs help moving",
                "He hit a home run with that lasagna",
                "Thanks to my running mate for getting me through the 10k",
                "Spotted with my best friend at the zoo",
                "We painted the white house on the corner",
                "I'm studying cryptography this semester",
                "Our partisanship workshop at the library",
                "The bitcoiners' meetup, said nobody",
                "Rolled out the red carpet for grandma's birthday");
        String hide = everyPack();
        for (boolean wholeWords : new boolean[] {false, true}) {
            PostWords.Rules rules = PostWords.rules(hide, "", wholeWords);
            for (String post : everyday) {
                assertEquals("\"" + post + "\" was hidden, whole words " + wholeWords, PostWords.Verdict.NO_MATCH,
                        rules.judge(Collections.singletonList(post)));
            }
        }
    }

    /** A post about a pack's topic is still hidden by that pack alone, its capitals and plurals aside. */
    @Test
    public void aPostAboutAPacksTopicIsStillHidden() {
        Map<TopicPacks.Pack, String> topics = new LinkedHashMap<>();
        topics.put(TopicPacks.Pack.POLITICS, "The Senators finally passed the legislation");
        topics.put(TopicPacks.Pack.ELECTIONS, "Polls close at 8 on Election Day, go vote!");
        topics.put(TopicPacks.Pack.CRYPTO, "BITCOIN just hit a new high");
        topics.put(TopicPacks.Pack.SPORTS, "What a Super Bowl that was");
        topics.put(TopicPacks.Pack.CELEBRITY_GOSSIP, "The paparazzi caught them leaving the party");
        topics.put(TopicPacks.Pack.WEIGHT_LOSS_ADS, "Lose weight fast with these keto gummies");
        topics.put(TopicPacks.Pack.GIVEAWAYS_AND_BAIT, "GIVEAWAY time! Tag a friend to enter");
        assertEquals(TopicPacks.Pack.values().length, topics.size());
        for (Map.Entry<TopicPacks.Pack, String> topic : topics.entrySet()) {
            String hide = TopicPacks.add("", topic.getKey(), 0).text;
            for (boolean wholeWords : new boolean[] {false, true}) {
                assertEquals(topic.getKey() + " kept \"" + topic.getValue() + "\"", PostWords.Verdict.HIDE_PATTERN,
                        PostWords.rules(hide, "", wholeWords).judge(Collections.singletonList(topic.getValue())));
            }
        }
        assertEquals("a plural", PostWords.Verdict.HIDE_PATTERN, PostWords.rules(everyPack(), "")
                .judge(Collections.singletonList("Three more NFTs dropped today")));
    }
}

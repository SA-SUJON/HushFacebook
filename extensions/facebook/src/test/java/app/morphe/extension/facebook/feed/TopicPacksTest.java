/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Topic packs: short plain word lists the Words to hide editor adds as ordinary lines. Adding skips
 * what's already there, stops at the list's limits and shared room, and keeps what was typed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TopicPacksTest {
    @Test
    public void everyPackIsShortAndEveryWordIsAValidDistinctPhrase() {
        Set<String> names = new HashSet<>();
        for (TopicPacks.Pack pack : TopicPacks.Pack.values()) {
            List<String> words = pack.words();
            assertTrue(pack + " has " + words.size(), words.size() >= 10 && words.size() <= 40);
            assertTrue(names.add(pack.name()));
            assertEquals(pack + ": a word is out of bounds, a repeat or a pattern", words,
                    PostWords.phrases(String.join("\n", words)));
            for (String word : words) {
                assertTrue(word + " is too short to sit safely in a post", word.length() >= 4);
                assertTrue(word + " isn't plain English text", word.matches("[a-z0-9' -]+"));
                assertEquals(word + " isn't trimmed", word, word.trim());
            }
        }
    }

    @Test
    public void aPackAddsItsWordsAsOrdinaryLines() {
        TopicPacks.Pack pack = TopicPacks.Pack.CRYPTO;
        TopicPacks.Result result = TopicPacks.add("", pack, 0);
        assertEquals(pack.words().size(), result.added);
        assertEquals(0, result.duplicates);
        assertFalse(result.full);
        assertEquals(String.join("\n", pack.words()), result.text);
        assertTrue("it saves as it stands", PostWords.isClean(result.text));
        assertEquals(pack.words().size(), PostWords.count(result.text));
    }

    @Test
    public void whatWasTypedStaysFirstAndAWordAlreadyThereIsSkipped() {
        TopicPacks.Result result = TopicPacks.add("my own phrase\nBITCOIN\n", TopicPacks.Pack.CRYPTO, 0);
        List<String> lines = Arrays.asList(result.text.split("\n"));
        assertEquals("my own phrase", lines.get(0));
        assertEquals("BITCOIN", lines.get(1));
        assertEquals("the pack follows, with no blank line", TopicPacks.Pack.CRYPTO.words().get(1), lines.get(2));
        assertEquals(1, result.duplicates);
        assertEquals(TopicPacks.Pack.CRYPTO.words().size() - 1, result.added);
        assertEquals("a word in another form counts once", 1, count(lines, "bitcoin"));
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
    public void twoPacksShareWhatTheyHaveInCommon() {
        String politics = TopicPacks.add("", TopicPacks.Pack.POLITICS, 0).text;
        TopicPacks.Result both = TopicPacks.add(politics, TopicPacks.Pack.ELECTIONS, 0);
        assertTrue(both.added > 0);
        assertEquals(PostWords.count(both.text), PostWords.phrases(both.text).size());
        assertEquals("every line is a distinct phrase", both.text.split("\n").length, PostWords.count(both.text));
    }

    @Test
    public void aPackStopsWhereTheListWouldPassItsPhraseLimit() {
        List<String> filler = new ArrayList<>();
        for (int i = 0; i < PostWords.MAX_PHRASES - 3; i++) filler.add("zzfiller" + i);
        String typed = String.join("\n", filler);
        TopicPacks.Result result = TopicPacks.add(typed, TopicPacks.Pack.SPORTS, 0);
        assertEquals(3, result.added);
        assertTrue(result.full);
        assertEquals(PostWords.MAX_PHRASES, PostWords.count(result.text));
        assertTrue(PostWords.size(result.text, 0).fits());

        TopicPacks.Result none = TopicPacks.add(result.text, TopicPacks.Pack.POLITICS, 0);
        assertEquals(0, none.added);
        assertTrue(none.full);
        assertEquals("a full list is left as it was", result.text, none.text);
    }

    @Test
    public void aPackStopsWhereTheSharedRoomRunsOut() {
        int nearlyFull = PostWords.MAX_LIST_BYTES - 40;
        TopicPacks.Result result = TopicPacks.add("", TopicPacks.Pack.POLITICS, nearlyFull);
        assertTrue(result.full);
        assertTrue("some words fit in 40 bytes", result.added > 0 && result.added < TopicPacks.Pack.POLITICS.words().size());
        assertTrue(PostWords.size(result.text, nearlyFull).fits());

        TopicPacks.Result none = TopicPacks.add("", TopicPacks.Pack.POLITICS, PostWords.MAX_LIST_BYTES - 2);
        assertEquals(0, none.added);
        assertTrue(none.full);
        assertEquals("", none.text);
    }

    @Test
    public void aNullListIsAnEmptyOne() {
        assertEquals(TopicPacks.Pack.ELECTIONS.words().size(), TopicPacks.add(null, TopicPacks.Pack.ELECTIONS, 0).added);
    }

    private static int count(List<String> lines, String word) {
        int found = 0;
        for (String line : lines) {
            if (line.equalsIgnoreCase(word)) found++;
        }
        return found;
    }
}

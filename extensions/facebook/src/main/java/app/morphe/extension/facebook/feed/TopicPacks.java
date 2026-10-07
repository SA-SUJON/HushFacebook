/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Short, plain word lists for common topics, which the Words to hide editor can add in one tap. A
 * pack isn't a rule of its own: adding one writes its words into the list being typed as ordinary
 * lines, so they're editable and removable afterwards, and nothing is saved until the person saves.
 *
 * <p>Each pack is topic terms only, English, chosen so that a phrase is unlikely to sit inside an
 * unrelated longer word, since a phrase matches anywhere in a post's text. A word already in the
 * list, in any capitalisation or compatibility form, is skipped, and a pack stops where the list
 * would pass {@link PostWords#MAX_PHRASES} phrases or the room both lists share.
 */
public final class TopicPacks {
    /** The packs the editor offers, in the order it lists them. */
    public enum Pack {
        POLITICS("democrat", "republican", "congress", "senator", "white house", "supreme court", "impeach",
                "presidential", "ballot", "midterm elections", "voters", "partisan", "legislation", "lawmakers", "political",
                "politician", "governor", "prime minister", "parliament", "left wing", "right wing",
                "mainstream media", "border wall", "immigration", "tax cuts", "campaign trail"),
        ELECTIONS("election day", "election results", "election night", "polling place", "polling station",
                "early voting", "mail-in ballot", "absentee ballot", "ballot box", "voter registration",
                "registered voters", "swing state", "electoral college", "primary election", "runoff election",
                "exit poll", "campaign rally", "running mate", "presidential candidate", "go vote", "get out the vote",
                "recount", "voter turnout", "poll numbers", "debate night", "electoral votes"),
        CRYPTO("bitcoin", "ethereum", "cryptocurrency", "crypto coin", "crypto market", "crypto trading",
                "crypto investing", "blockchain", "altcoin", "dogecoin", "memecoin", "stablecoin", "crypto airdrop",
                "hodl", "to the moon", "crypto wallet", "binance", "coinbase", "solana coin", "mining rig",
                "pump and dump", "trading signals", "token sale", "crypto exchange"),
        SPORTS("touchdown", "quarterback", "super bowl", "playoffs", "world series", "home run", "slam dunk",
                "free agent", "fantasy football", "transfer window", "premier league", "champions league",
                "world cup", "march madness", "stanley cup", "final score", "double overtime", "halftime", "box score",
                "game recap", "draft pick", "nfl draft", "nba finals", "nba playoffs", "head coach",
                "starting lineup"),
        CELEBRITY_GOSSIP("celebrity", "red carpet", "paparazzi", "spotted with", "dating rumors", "split rumors",
                "baby bump", "tell-all", "breakup", "engaged to", "caught cheating", "wardrobe malfunction",
                "reality star", "royal family", "met gala", "love triangle", "exclusive photos",
                "shocking transformation", "net worth", "public feud"),
        WEIGHT_LOSS_ADS("weight loss", "lose weight", "lose 10 pounds", "belly fat", "fat burner", "fat burning",
                "burn fat", "melt fat", "keto", "diet pill", "diet plan", "slimming", "detox", "intermittent fasting",
                "meal replacement", "cleanse", "miracle cure", "metabolism booster", "tummy", "ozempic",
                "weight loss gummies", "body transformation", "calorie deficit", "before and after"),
        GIVEAWAYS_AND_BAIT("giveaway", "give away", "giving away", "tag a friend", "tag someone", "share this post",
                "like and share", "comment below", "comment yes", "comment amen", "type amen", "share if you",
                "like if you", "repost if", "follow and share", "enter to win", "win a free", "free gift",
                "gift card", "sweepstakes", "raffle", "contest alert", "must share", "share to win",
                "comment done");

        private final List<String> words;

        Pack(String... words) {
            this.words = Collections.unmodifiableList(java.util.Arrays.asList(words));
        }

        /** The pack's words, one phrase each, in the order they're added. */
        public List<String> words() {
            return words;
        }
    }

    /** What adding a pack to a typed list came to. */
    public static final class Result {
        /** The list with the pack's new words added at the end, or the list as it was when none were. */
        public final String text;
        /** How many words were added. */
        public final int added;
        /** How many were already in the list. */
        public final int duplicates;
        /** Whether words were left out because the list or the room both lists share was full. */
        public final boolean full;

        Result(String text, int added, int duplicates, boolean full) {
            this.text = text;
            this.added = added;
            this.duplicates = duplicates;
            this.full = full;
        }
    }

    private TopicPacks() {
    }

    /**
     * [pack]'s words added to [typed], the list as it's being edited, each on a line of its own
     * after what's there. A word already in the list is skipped, and adding stops at the first one
     * that wouldn't fit the phrase limit or the room beside the other list.
     *
     * @param otherBytes the other list's {@link PostWords#encodedBytes}, as it's stored.
     */
    public static Result add(@Nullable String typed, Pack pack, int otherBytes) {
        String text = typed == null ? "" : typed;
        Set<String> seen = new HashSet<>();
        for (String phrase : PostWords.phrases(text)) seen.add(PostWords.fold(phrase));
        StringBuilder grown = new StringBuilder(text);
        int added = 0;
        int duplicates = 0;
        boolean full = false;
        for (String word : pack.words()) {
            if (!seen.add(PostWords.fold(word))) {
                duplicates++;
                continue;
            }
            int before = grown.length();
            if (before > 0 && grown.charAt(before - 1) != '\n') grown.append('\n');
            grown.append(word);
            PostWords.Size size = PostWords.size(grown.toString(), otherBytes);
            if (size.tooMany() || size.tooManyPatterns() || size.bytes > PostWords.MAX_LIST_BYTES) {
                grown.setLength(before);
                full = true;
                break;
            }
            added++;
        }
        return new Result(added == 0 ? text : grown.toString(), added, duplicates, full);
    }
}

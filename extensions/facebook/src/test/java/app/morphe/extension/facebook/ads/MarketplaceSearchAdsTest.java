/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Marketplace search's answers as Facebook's Networking module hands them to JavaScript, whole or
 * in the pieces Tigon reads them in: the ad results leave the list, every organic listing and every
 * byte around them stays, a streamed list stays contiguous, and other queries, the switch off, a
 * pause or anything it can't read leave the text as Facebook's servers wrote it.
 *
 * <p>No answer of the app's own search has been captured yet. The shape here is the web search's
 * (the same GraphQL schema: {@code data.marketplace_search.feed_units.edges}, ad nodes typed
 * MarketplaceFeedAdStory, a sponsored listing's {@code story.sponsored_data}, edges typed
 * MarketplaceSearchFeedStoriesEdge), and Relay's own form for streamed and deferred payloads.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MarketplaceSearchAdsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String HEAD = "MarketplaceSearchApp_MarketplaceSearchFeedHeadQuery";
    private static final String EDGES = "\"marketplace_search\",\"feed_units\",\"edges\"";

    @Before
    public void inBuild() {
        MarketplaceAdFilterForTests.inBuild(Boolean.TRUE);
        MarketplaceAdFilterForTests.forget();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        MarketplaceAdFilterForTests.inBuild(null);
        MarketplaceAdFilter.failNextForTests = null;
        MarketplaceAdFilterForTests.forget();
        PauseForTests.resume();
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(MarketplaceSearchAds.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SPONSORED_MARKETPLACE + ":")) return line;
        }
        return null;
    }

    /** An organic listing's edge. Its title holds JSON's own characters, escaped the way a server writes them. */
    static String listing(int id) {
        return "{\"node\":{\"__typename\":\"MarketplaceFeedListingStoryObject\",\"story_type\":\"POST\",\"story_key\":\"" + id
                + "\",\"listing\":{\"__typename\":\"GroupCommerceProductItem\",\"id\":\"" + id
                + "\",\"marketplace_listing_title\":\"Kid's bike {red} [20\\\" wheels] \\\\ AdStory \\u00e9 " + id
                + "\",\"listing_price\":{\"formatted_amount\":\"$20\"},\"location\":{\"reverse_geocode\":{\"city\":\"Venice\"}},"
                + "\"primary_listing_photo\":{\"image\":{\"uri\":\"https://scontent.xx.fbcdn.net/" + id + ".jpg\"}}},"
                + "\"story\":{\"sponsored_data\":null,\"note\":\"\\\"sponsored_data\\\":{}\"}},"
                + "\"cursor\":\"{\\\"pos\\\":" + id + "}\",\"__typename\":\"MarketplaceSearchFeedStoriesEdge\"}";
    }

    /** An advertiser's result, the kind with an "Ad" line under its title. */
    static String adStory(int id) {
        return "{\"node\":{\"__typename\":\"MarketplaceFeedAdStory\",\"story_type\":\"AD\",\"story_key\":\"" + id
                + "\",\"ad_id\":\"" + id + "\",\"title\":\"Vogue x eBay: Molly Finds\",\"sponsored_data\":{\"ad_id\":\"" + id
                + "\",\"client_token\":\"t\"}},\"cursor\":null,\"__typename\":\"MarketplaceSearchFeedStoriesEdge\"}";
    }

    /** A seller's listing shown as sponsored: a listing story carrying sponsored data. */
    static String sponsoredListing(int id) {
        return "{\"node\":{\"__typename\":\"MarketplaceFeedListingStoryObject\",\"story_type\":\"POST\",\"story_key\":\"" + id
                + "\",\"listing\":{\"id\":\"" + id + "\",\"marketplace_listing_title\":\"Temu bike\"},"
                + "\"story\":{\"sponsored_data\":{\"ad_id\":\"" + id + "\"}}},\"cursor\":\"c" + id
                + "\",\"__typename\":\"MarketplaceSearchFeedStoriesEdge\"}";
    }

    /** The first payload of an answer, holding [edges]. */
    static String answer(String... edges) {
        return "{\"data\":{\"marketplace_search\":{\"feed_units\":{\"edges\":[" + String.join(",", edges)
                + "],\"page_info\":{\"end_cursor\":\"e\",\"has_next_page\":true}},\"__typename\":\"MarketplaceSearch\"}},"
                + "\"extensions\":{\"is_final\":false}}";
    }

    /** A result Relay streams on its own, at [index] of the list. */
    static String streamed(int index, String edge) {
        return "{\"label\":\"MarketplaceSearchFeed$stream$edges\",\"path\":[" + EDGES + "," + index + "],\"data\":" + edge
                + ",\"extensions\":{\"is_final\":false}}";
    }

    /** Fields Relay deferred for the result at [index]. */
    static String deferred(int index) {
        return "{\"label\":\"MarketplaceSearchFeedItem$defer$badges\",\"path\":[" + EDGES + "," + index
                + ",\"node\"],\"data\":{\"badges\":[]},\"extensions\":{\"is_final\":false}}";
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    @Test
    public void theSwitchStartsOnAndSearchAdsLeaveTheAnswer() {
        assertTrue(Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.get());
        String whole = answer(listing(1), adStory(2), listing(3), sponsoredListing(4), listing(5));
        assertEquals(answer(listing(1), listing(3), listing(5)), MarketplaceAdFilterForTests.responseWhole(HEAD, whole));
        assertEquals(MarketplaceSearchAds.ROUTE + ": 1 lists, 5 items, 2 removed. Last reason: "
                + MarketplaceSearchAds.REMOVED + ". Removed: " + MarketplaceSearchAds.REMOVED + " 2. Kinds: "
                + "MarketplaceFeedListingStoryObject 4, MarketplaceFeedAdStory 1", counterLine());
        assertEquals(FamilyNames.SPONSORED_MARKETPLACE + ": invoked 1, 1 found, 0 missing", statusLine());
    }

    /** Whatever the pieces are, the text passed on adds up to the same answer, and nothing is lost. */
    @Test
    public void piecesAnywhereAddUpToTheSameAnswer() {
        String first = answer(adStory(0), listing(1), sponsoredListing(2), listing(3));
        String whole = "for (;;);" + first + "\r\n" + first + "\r\n";
        String expected = "for (;;);" + answer(listing(1), listing(3)) + "\r\n" + answer(listing(1), listing(3)) + "\r\n";
        for (int cut = 0; cut <= whole.length(); cut++) {
            Object request = new Object();
            String out = MarketplaceAdFilterForTests.responsePiece(HEAD, whole.substring(0, cut), request)
                    + MarketplaceAdFilterForTests.responsePiece(HEAD, whole.substring(cut), request);
            assertEquals("cut at " + cut, expected, out);
        }
        for (int size = 1; size < 40; size += 3) {
            Object request = new Object();
            StringBuilder out = new StringBuilder();
            for (int at = 0; at < whole.length(); at += size) {
                out.append(MarketplaceAdFilterForTests.responsePiece(HEAD,
                        whole.substring(at, Math.min(whole.length(), at + size)), request));
            }
            assertEquals("pieces of " + size, expected, out.toString());
        }
    }

    /** Text before and between payloads goes straight on; only an unfinished payload waits. */
    @Test
    public void onlyAnUnfinishedPayloadWaits() {
        Object request = new Object();
        String first = answer(listing(1));
        assertEquals("", MarketplaceAdFilterForTests.responsePiece(HEAD, first.substring(0, 10), request));
        assertEquals(first + "\r\n", MarketplaceAdFilterForTests.responsePiece(HEAD, first.substring(10) + "\r\n", request));
        String organic = "\r\n";
        assertSame(organic, MarketplaceAdFilterForTests.responsePiece(HEAD, organic, request));
    }

    /**
     * A streamed ad isn't passed on, and every later index of its list, streamed results and deferred
     * fields alike, moves down past the ads taken out. A deferred part of an ad goes where no result
     * is. Everything else in each payload stays.
     */
    @Test
    public void aStreamedListStaysContiguous() {
        String[] payloads = {
                answer(listing(0), adStory(1)),
                streamed(2, adStory(2)),
                streamed(3, listing(3)),
                deferred(1),
                streamed(4, sponsoredListing(4)),
                streamed(5, listing(5)),
                deferred(3),
                deferred(5),
                "{\"data\":null,\"extensions\":{\"is_final\":true}}",
        };
        String[] expected = {
                answer(listing(0)),
                "",
                streamed(1, listing(3)),
                deferred(MarketplaceSearchAds.NOWHERE + 1),
                "",
                streamed(2, listing(5)),
                deferred(1),
                deferred(2),
                payloads[8],
        };
        Object request = new Object();
        StringBuilder out = new StringBuilder();
        for (String payload : payloads) out.append(MarketplaceAdFilterForTests.responsePiece(HEAD, payload + "\r\n", request));
        assertEquals(String.join("\r\n", expected) + "\r\n", out.toString());
        assertTrue(counterLine(), counterLine().startsWith(MarketplaceSearchAds.ROUTE + ": 5 lists, 6 items, 3 removed."));

        // The same answer read whole is taken apart the same way.
        String whole = String.join("\r\n", payloads);
        assertEquals(String.join("\r\n", expected), MarketplaceAdFilterForTests.responseWhole(HEAD, whole));
    }

    /** Relay's payloads sent together as one list come out as one list, less the streamed ads. */
    @Test
    public void aListOfPayloadsIsReadPayloadByPayload() {
        String batch = "[" + answer(listing(0), adStory(1)) + "," + streamed(2, adStory(2)) + "," + streamed(3, listing(3)) + "]";
        assertEquals("[" + answer(listing(0)) + "," + streamed(1, listing(3)) + "]",
                MarketplaceAdFilterForTests.responseWhole(HEAD, batch));
    }

    /** Negative control: listings whose text or empty fields look like an ad's stay, and so does a list of other things. */
    @Test
    public void lookalikesStay() {
        String lookalike = "{\"node\":{\"__typename\":\"MarketplaceFeedListingStoryObject\",\"title\":\"MarketplaceFeedAdStory\","
                + "\"seller\":{\"name\":\"Sponsored\",\"sponsored_data\":null},\"ad_id\":null},\"cursor\":\"a\"}";
        String others = "{\"data\":{\"marketplace_search\":{\"filters\":[{\"__typename\":\"MarketplaceFeedAdStory\"},"
                + "{\"sponsored_data\":{\"ad_id\":\"1\"}}]}}}";
        String whole = answer(listing(1), lookalike) + others;
        assertSame(whole, MarketplaceAdFilterForTests.responseWhole(HEAD, whole));
        assertTrue(counterLine(), counterLine().startsWith(MarketplaceSearchAds.ROUTE + ": 1 lists, 2 items, 0 removed"));
    }

    /** The typeahead, the search history and every other query's answers pass untouched and uncounted. */
    @Test
    public void otherAnswersAreLeftAlone() {
        String whole = answer(listing(1), adStory(2));
        String[] queries = {
                "MarketplaceSearchServerTypeaheadSuggestionsQuery",
                "MarketplaceHistoryAddInterestedSearchQueryMutation",
                "MarketplaceContinueShoppingAddSearchQueryMutation",
                "MarketplaceHomeFeedQueryRendererQuery",
                "MarketplacePDPContainerQuery",
        };
        for (String query : queries) {
            assertSame(query, whole, MarketplaceAdFilterForTests.responseWhole(query, whole));
            assertSame(query, whole, MarketplaceAdFilterForTests.responsePiece(query, whole, new Object()));
        }
        assertSame(whole, MarketplaceAdFilter.responseWhole(whole, "RelayFBNetwork_EventsBookmarkSurfaceQuery"));
        assertSame(whole, MarketplaceAdFilter.responseWhole(whole, "react_native"));
        assertSame(whole, MarketplaceAdFilter.responseWhole(whole, null));
        assertSame(whole, MarketplaceAdFilter.responsePiece(whole, "SearchFeed", new Object()));
        assertSame("no request to key the answer by", whole,
                MarketplaceAdFilter.responsePiece(whole, "RelayFBNetwork_" + HEAD, null));
        assertNull(MarketplaceAdFilter.responsePiece(null, "RelayFBNetwork_" + HEAD, new Object()));
        assertNull(counterLine());
        assertNull(statusLine());
    }

    /** A payload it can't read goes on as it was; a whole answer that never closes a payload goes on at its end. */
    @Test
    public void whatItCantReadGoesOnAsItWas() {
        String[] bodies = {
                "{\"data\":{\"marketplace_search\":{\"feed_units\":{\"edges\":[" + adStory(1) + "]}}}",
                "{\"data\" " + adStory(1) + "}",
                "{\"data\":[" + adStory(1) + ",]}",
                "{\"data\":{\"edges\":[" + adStory(1) + "}}]",
                "<html>error</html>",
                "",
        };
        for (String body : bodies) {
            assertEquals(body, body, MarketplaceAdFilterForTests.responseWhole(HEAD, body));
        }
        assertEquals(0, occurrences(String.valueOf(counterLine()), " removed. Last reason"));
    }

    @Test
    public void offTheAnswerIsFacebooks() {
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(false);
        String whole = answer(listing(1), adStory(2));
        assertSame(whole, MarketplaceAdFilterForTests.responseWhole(HEAD, whole));
        Object request = new Object();
        String head = whole.substring(0, 30);
        assertSame(head, MarketplaceAdFilterForTests.responsePiece(HEAD, head, request));
        // An answer that started with the switch off stays unread when it's turned on halfway.
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(true);
        String tail = whole.substring(30);
        assertSame(tail, MarketplaceAdFilterForTests.responsePiece(HEAD, tail, request));
        assertNull(counterLine());
        assertTrue(statusLine(), statusLine().startsWith(FamilyNames.SPONSORED_MARKETPLACE + ": invoked 2, 1 found"));
    }

    @Test
    public void pausedTheAnswerIsFacebooks() {
        String whole = answer(listing(1), adStory(2));
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[] {HushfacebookPause.Reason.SWITCH,
                HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertSame(why.name(), whole, MarketplaceAdFilterForTests.responseWhole(HEAD, whole));
            assertFalse(why.name(), MarketplaceAdFilterForTests.dropsASearchAd());
        }
        PauseForTests.resume();
        assertTrue(MarketplaceAdFilterForTests.dropsASearchAd());
    }

    @Test
    public void withoutThePatchNothingIsRead() {
        MarketplaceAdFilterForTests.inBuild(Boolean.FALSE);
        String whole = answer(listing(1), adStory(2));
        assertSame(whole, MarketplaceAdFilterForTests.responseWhole(HEAD, whole));
        assertSame(whole, MarketplaceAdFilterForTests.responsePiece(HEAD, whole, new Object()));
        assertNull(statusLine());
        assertNull(counterLine());
    }

    /** A failure passes the text on, and a piece of an answer that was waiting goes with it. */
    @Test
    public void aFailurePassesTheTextOn() {
        String whole = answer(listing(1), adStory(2));
        MarketplaceAdFilter.failNextForTests = new IllegalStateException("probe");
        assertSame(whole, MarketplaceAdFilterForTests.responseWhole(HEAD, whole));
        assertTrue(statusLine(), statusLine().contains("threw java.lang.IllegalStateException"));
        assertEquals(answer(listing(1)), MarketplaceAdFilterForTests.responseWhole(HEAD, whole));

        Object request = new Object();
        assertEquals("", MarketplaceAdFilterForTests.responsePiece(HEAD, whole.substring(0, 40), request));
        MarketplaceSearchAds.failNextPieceForTests = new IllegalStateException("probe");
        assertEquals(whole, MarketplaceAdFilterForTests.responsePiece(HEAD, whole.substring(40), request));
        String after = answer(listing(3), adStory(4));
        assertSame("the rest of that answer goes on unread", after,
                MarketplaceAdFilterForTests.responsePiece(HEAD, after, request));
    }

    /**
     * With Debug logging on, each part of a search answer has a line saying how many listings it
     * held and how many ads came out. No line quotes a title, an id or a link.
     */
    @Test
    public void debugLoggingCountsWithoutQuotingAnything() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Object request = new Object();
        MarketplaceAdFilterForTests.responsePiece(HEAD, answer(listing(1), adStory(2), listing(3)) + "\r\n", request);
        MarketplaceAdFilterForTests.responsePiece(HEAD, streamed(3, sponsoredListing(3)) + "\r\n", request);
        MarketplaceAdFilterForTests.responsePiece(HEAD, "{\"extensions\":{\"is_final\":true}}", request);
        Settings.HIDE_SPONSORED_MARKETPLACE_LISTINGS.save(false);
        MarketplaceAdFilterForTests.responseWhole(HEAD, answer(listing(1)));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Marketplace ads: " + HEAD
                + " answer, part 1: 3 listings, took out 1 ad (MarketplaceFeedAdStory)."));
        assertEquals(report, 1, occurrences(report, "Marketplace ads: " + HEAD
                + " answer, part 2: streamed listing 3, took it out (MarketplaceFeedListingStoryObject, sponsored data)."));
        assertEquals(report, 1, occurrences(report, "Marketplace ads: " + HEAD + " answer, part 3: no listings in it."));
        assertEquals(report, 1, occurrences(report, "Marketplace ads: " + HEAD + " answer went on unread, the switch is off."));
        assertEquals(report, 0, occurrences(report, "bike"));
        assertEquals(report, 0, occurrences(report, "fbcdn"));
        assertEquals(report, 0, occurrences(report, "Vogue"));
    }
}

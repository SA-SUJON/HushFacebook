/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A person's name has no shape a pattern can find, and the toast that carries it is written to
 * the buffer in whichever language the phone speaks. The bundle marks the name instead, with the
 * isolate pair {@link #label(String)} wraps it in, and the redactor finds the marks.
 */
public class DiagnosticRedactorTest {

    private static final String FSI = "⁨";
    private static final String PDI = "⁩";

    /**
     * What Hushfeed's VideoAuthor.label() wraps a name in, kept here so the redactor's contract is
     * tested without that class: one isolate run, with breaks flattened and any marks inside the
     * name dropped, or the bare words when there is nobody to name.
     */
    private static String label(String name) {
        if (name == null || name.isEmpty()) return "this account";
        StringBuilder flat = new StringBuilder(name.length() + 2).append('⁨');
        for (int at = 0; at < name.length(); at++) {
            char character = name.charAt(at);
            if (character == '⁨' || character == '⁩') continue;
            flat.append(character == '\n' || character == '\r' ? ' ' : character);
        }
        return flat.append('⁩').toString();
    }

    @Test public void theToastLineLosesTheNameAndKeepsTheAction() {
        String line = DiagnosticRedactor.redact("Showing toast: Hid posts from " + FSI + "Dana Q" + PDI);

        assertEquals("Showing toast: Hid posts from [name omitted]", line);
    }

    @Test public void theNameIsFoundByItsMarksInAnyLanguage() {
        String german = DiagnosticRedactor.redact(
                "Showing toast: " + FSI + "Dana Q" + PDI + " wird nicht mehr angezeigt");

        assertFalse("a name in a translated toast survived: " + german, german.contains("Dana Q"));
        assertTrue("the words around the name were lost: " + german,
                german.contains("[name omitted] wird nicht mehr angezeigt"));
    }

    @Test public void aHandleOrAnIdInsideTheMarksGoesWithThem() {
        String handle = DiagnosticRedactor.redact("Hidden " + FSI + "@dana.q" + PDI + " locally");
        String vanity = DiagnosticRedactor.redact("Unfollowed " + FSI + "dana.q.1987" + PDI);

        assertEquals("Hidden [name omitted] locally", handle);
        assertEquals("Unfollowed [name omitted]", vanity);
    }

    @Test public void aNameThatLostItsClosingMarkIsCutToTheEndOfItsLine() {
        String text = DiagnosticRedactor.redact("Hidden " + FSI + "Dana Q locally\nnext line");

        assertEquals("Hidden [name omitted]\nnext line", text);
    }

    /**
     * A display name is whatever the account chose, so it can carry a line break or a copy of
     * the marks themselves. Either one used to split the run and leave the rest of the name in
     * the report. The label takes both out, so what arrives here is one run.
     */
    @Test public void aNameCarryingABreakOrTheMarksThemselvesIsStillOneRun() {
        String broken = label("Dana\nSecretHandle");
        String nested = label("A" + PDI + "B" + FSI + "C");

        assertEquals("Showing toast: Hid [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Hid " + broken));
        assertEquals("Showing toast: Hid [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Hid " + nested));
        assertFalse("half the name survived the break: " + broken,
                DiagnosticRedactor.redact(broken).contains("SecretHandle"));
        assertFalse("the middle of the name survived its own marks: " + nested,
                DiagnosticRedactor.redact(nested).contains("B"));
    }

    /**
     * The control for the pair above. Without the flattening the same two names leak, which is
     * what makes the flattening the thing being tested rather than the pattern.
     */
    @Test public void theSameTwoNamesLeakWhenTheyAreNotFlattenedFirst() {
        String rawBreak = FSI + "Dana\nSecretHandle" + PDI;
        String rawNested = FSI + "A" + PDI + "B" + FSI + "C" + PDI;

        assertTrue(DiagnosticRedactor.redact(rawBreak).contains("SecretHandle"));
        assertTrue(DiagnosticRedactor.redact(rawNested).contains("B"));
    }

    @Test public void anAccountWithNothingToNameIsNotHiddenAtAll() {
        // "this account" names nobody, so hiding it says less than the truth.
        assertEquals("Hid this account", DiagnosticRedactor.redact("Hid " + label(null)));
    }

    @Test public void aHandleOnItsOwnGoesWhileAnEmailAndAnObjectHashStay() {
        String text = DiagnosticRedactor.redact(
                "@dana.q wrote to dana@example.com from Foo@1a2b3c and mentioned @dana_q2");

        assertEquals("[handle omitted] wrote to dana@example.com from Foo@1a2b3c"
                + " and mentioned [handle omitted]", text);
    }

    @Test public void theStoryReferenceLineKeepsThePseudonymAndDropsThePostId() {
        String line = DiagnosticRedactor.redact(
                "Hidden story: author 3f9a2c1b0e7d story_fbid=1022345678901234567");

        assertTrue("the pseudonym is what makes two lines comparable: " + line,
                line.contains("author 3f9a2c1b0e7d"));
        assertFalse("the story id names what was read: " + line,
                line.contains("1022345678901234567"));
    }

    @Test public void facebookHostsAndSessionCookiesGo() {
        String line = DiagnosticRedactor.redact(
                "GET scontent-iad3-1.xx.fbcdn.net/v/t39.30808-6/1.jpg from b-graph.facebook.com"
                        + " cookie c_user=100012345678901; xs=12%3Aabc datr=Zx9");

        assertFalse("a CDN address survived: " + line, line.contains("fbcdn.net"));
        assertFalse("the graph host survived: " + line, line.contains("b-graph"));
        assertFalse("the account id cookie survived: " + line, line.contains("100012345678901"));
        assertFalse("the session cookie survived: " + line, line.contains("12%3Aabc"));
        assertFalse("the browser id cookie survived: " + line, line.contains("Zx9"));
    }

    /**
     * A Facebook address with no scheme and no subdomain. The host rule asked for a subdomain, so
     * "facebook.com/dana.q.1987" reached the report as written while the same address with "www."
     * in front of it was caught.
     */
    @Test public void aFacebookHostGoesWithOrWithoutASubdomain() {
        assertEquals("opened [host omitted] and [host omitted]", DiagnosticRedactor.redact(
                "opened facebook.com/dana.q.1987 and www.facebook.com/dana.q.1987"));
        assertEquals("[host omitted] then [host omitted]",
                DiagnosticRedactor.redact("fb.watch/abc123XYZ then fb.me/1a2b3c"));
        assertEquals("[host omitted] then [host omitted] and [host omitted]",
                DiagnosticRedactor.redact("facebook.com:443/profile.php then m.facebook.com and messenger.com/t/2"));
        assertEquals("cookie domain=.[host omitted]; path=/",
                DiagnosticRedactor.redact("cookie domain=.facebook.com; path=/"));
    }

    /**
     * What only looks like a Facebook address: the package and class names a report prints, and
     * names that start the same way and go on. They're what a maintainer reads a report for.
     */
    @Test public void aNameThatOnlyLooksLikeAFacebookHostStays() {
        String[] lines = {
                "app: com.facebook.katana 580.0.0.51.74 (475019344)",
                "at com.facebook.common.util.TriState.valueOf(TriState.java:12)",
                "at com.facebook.messenger.app.Thread.run(Thread.java:1012)",
                "notfacebook.com/page and facebook.community/page",
                "Hushfacebook: hid 3 rows from the feed",
        };
        for (String line : lines) assertEquals(line, DiagnosticRedactor.redact(line));
    }

    @Test public void aBareAccountIdGoesAndATimestampStays() {
        String line = DiagnosticRedactor.redact("Hidden 100012345678901 at 1790000000000");

        assertEquals("Hidden [id omitted] at 1790000000000", line);
    }

    /**
     * A CDN file name joins its ids with underscores, and an underscore is a word character, so a
     * rule bounded by word edges never saw them. The middle number is the photo's own id.
     */
    @Test public void idsJoinedByUnderscoresInAFileNameGo() {
        String line = DiagnosticRedactor.redact(
                "saving image 475148478_1134540631592283_1316146539584337463_n.jpg and id1234567890123456x");

        assertEquals("saving image 475148478_[id omitted]_[id omitted]_n.jpg and id[id omitted]x", line);
    }

    /** The mutation control: a line with no name, handle or id is returned as it came. */
    @Test public void aLineWithNothingToHideIsLeftAlone() {
        String line = "Removed 3 of 12 feed units (maxsize=40, boxes=2) for author 3f9a2c1b0e7d";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }

    /**
     * Synthetic credentials in the shapes a network, JSON or header dump prints them: quoted and
     * escaped keys, nested objects, JSON inside a JSON string, odd casing and separators, cookie
     * headers and whole Authorization values, one of them split over two lines. Each line is
     * followed by the secrets it carries, and every secret is a string nothing else here holds,
     * so a survivor is named in the failure.
     */
    /** A quote written as a JSON unicode escape, backslash u 0022, built so no tool decodes it. */
    private static final String U = "\\" + "u0022";

    public static final String[][] CREDENTIAL_CORPUS = {
            {"{\"access_token\":\"EAABjsonKeyA1\",\"locale\":\"en_US\"}", "EAABjsonKeyA1"},
            {"{\"data\":{\"viewer\":{\"session\":{\"sessionid\":\"sessNestB2\",\"uid\":\"77\"}}}}", "sessNestB2"},
            {"{\"auth\":{\"tokens\":{\"access\":\"deepAccessC3\",\"refresh\":\"deepRefreshC4\"}}}",
                    "deepAccessC3", "deepRefreshC4"},
            {"body=\"{\\\"access_token\\\":\\\"EAABescapedD5\\\",\\\"post_id\\\":\\\"4242\\\"}\"",
                    "EAABescapedD5", "4242"},
            {"body={\\\\\\\"password\\\\\\\":\\\\\\\"twiceEscapedE6\\\\\\\"}", "twiceEscapedE6"},
            {"{'password': 'correct horse battery'}", "horse", "battery"},
            {"\"PassWord\" : \"p\\\"ss w0rdF7\"", "w0rdF7"},
            {"ACCESS-TOKEN=EAABcasingG8.", "EAABcasingG8"},
            {"accessToken => 'arrowTokenH9'", "arrowTokenH9"},
            {"fb_dtsg%3AdtsgEncodedI10%26next", "dtsgEncodedI10"},
            {"Cookie: c_user=100012345678901; xs=12%3AxsCookieJ11; fr=frCookieJ12; sb=sbCookieJ13; datr=datrCookieJ14",
                    "100012345678901", "xsCookieJ11", "frCookieJ12", "sbCookieJ13", "datrCookieJ14"},
            {"set-cookie: fr=frSetCookieK15; expires=Sat, 26-Dec-2026 12:00:00 GMT; Max-Age=7776000; secure",
                    "frSetCookieK15"},
            {"{\"cookies\":[{\"name\":\"xs\",\"value\":\"xsListL16\"},{\"name\":\"fr\",\"value\":\"frListL17\"}]}",
                    "xsListL16", "frListL17"},
            {"Authorization: Bearer EAABbearerM18", "EAABbearerM18"},
            {"authorization: Basic dXNlcjpiYXNpY1NlY3JldE4xOQ==", "dXNlcjpiYXNpY1NlY3JldE4xOQ=="},
            {"AUTHORIZATION: Bearer\n    EAABfoldedO20", "EAABfoldedO20"},
            {"Authorization:\nOAuth EAABnextLineP21", "EAABnextLineP21"},
            {"{\"Authorization\":\"OAuth EAABquotedQ22\",\"x-fb-friendly-name\":\"FeedQuery\"}", "EAABquotedQ22"},
            {"Proxy-Authorization: Basic cHJveHlTZWNyZXRSMjM=", "cHJveHlTZWNyZXRSMjM="},
            {"retrying with Bearer EAABbareS24 after 401", "EAABbareS24"},
            {"{\"authorization\":\"Bearer\\nEAABjsonBreakT25\"}", "EAABjsonBreakT25"},
            // What a review found still leaking, one row each.
            {"{\"name\":\"Authorization\",\"value\":\"Basic dXNlcjpsZWFrQmFzaWMx\"}", "dXNlcjpsZWFrQmFzaWMx"},
            {"{\"name\":\"xs\",\"value\":\"12%3AxsPairLeak\"}", "xsPairLeak"},
            {"Authorization -> Basic dXNlcjpsZWFrQXJyb3c=", "dXNlcjpsZWFrQXJyb3c="},
            {"retried with Basic dXNlcjpiYXJlQmFzaWM5", "dXNlcjpiYXJlQmFzaWM5"},
            {"{\"auth\":{\"a\":{\"b\":{\"c\":{\"access\":\"leakDeep4\"}}}}}", "leakDeep4"},
            {"{\"auth\":{\"hint\":\"}\",\"access\":\"leakBrace\"}}", "leakBrace"},
            {U + "access_token" + U + ":" + U + "EAABuniLeak" + U, "EAABuniLeak"},
            {"&quot;access_token&quot;:&quot;EAABhtmlLeak&quot;", "EAABhtmlLeak"},
            {"{\"api_key\":\"apiKeyLeak7\"}", "apiKeyLeak7"},
            {"{\"pwd\":\"pwdLeak8\"}", "pwdLeak8"},
            {"advertising_id=adIdLeak9", "adIdLeak9"},
            {"X-FB-Device-ID: deviceIdLeak10", "deviceIdLeak10"},
            {"{\"token \": \"spaceKeyLeak\"}", "spaceKeyLeak"},
            {"Authorization: Bearer EAABfirstHalf\nsecondHalfLeak", "EAABfirstHalf", "secondHalfLeak"},
            {"password=p@ss;w0rdSemiLeak", "w0rdSemiLeak"},
            {"password=staple spaced unquotedPassLeak", "staple", "unquotedPassLeak"},
    };

    @Test public void noSyntheticCredentialSurvives() {
        StringBuilder leaks = new StringBuilder();
        for (String[] row : CREDENTIAL_CORPUS) {
            String redacted = DiagnosticRedactor.redact(row[0]);
            for (int i = 1; i < row.length; i++) {
                if (redacted.contains(row[i])) {
                    leaks.append('\n').append(row[i]).append(" survived in: ").append(redacted);
                }
            }
        }
        assertEquals("", leaks.toString());
    }

    /** The same corpus as one block of text, the way it reaches the report: nothing leaks between lines. */
    @Test public void noSyntheticCredentialSurvivesInOneBlock() {
        StringBuilder block = new StringBuilder();
        for (String[] row : CREDENTIAL_CORPUS) block.append(row[0]).append('\n');
        String redacted = DiagnosticRedactor.redact(block.toString());
        for (String[] row : CREDENTIAL_CORPUS) {
            for (int i = 1; i < row.length; i++) {
                assertFalse(row[i] + " survived in:\n" + redacted, redacted.contains(row[i]));
            }
        }
    }

    /**
     * Short ids behind a quoted or camel-cased name. A post or story id of a few digits is too
     * short for the bare-number rule, so only its name gives it away.
     */
    @Test public void aShortIdGoesWhateverItsNameLooksLike() {
        assertEquals("{\"story_id=[omitted],\"kind\":\"reel\"}",
                DiagnosticRedactor.redact("{\"story_id\":\"7\",\"kind\":\"reel\"}"));
        assertEquals("postId=[omitted] shown", DiagnosticRedactor.redact("postId=12 shown"));
        assertEquals("{'feedback_id=[omitted]}", DiagnosticRedactor.redact("{'feedback_id': 'ZmVlZGJhY2s6MTI='}"));
        assertEquals("\\\"video_id=[omitted]}", DiagnosticRedactor.redact("\\\"video_id\\\":\\\"31\\\"}"));
        assertEquals("topLevelPostId=[omitted] kept", DiagnosticRedactor.redact("topLevelPostId: 42 kept"));
        assertEquals("post-id=[omitted] kept", DiagnosticRedactor.redact("post-id: 99 kept"));
    }

    /**
     * A header name with nothing after it on its line takes nothing from the next. A Java trace
     * printed after it keeps every frame and its Suppressed line.
     */
    @Test public void anEmptyHeaderValueTakesNoFrameFromTheNextLine() {
        String emptyCookie = "java.io.IOException: missing Cookie:\n"
                + "\tat app.Foo.bar(Foo.java:1)\n\tat app.Baz.q(Baz.java:2)";
        assertEquals("java.io.IOException: missing Cookie=[omitted]\n"
                + "\tat app.Foo.bar(Foo.java:1)\n\tat app.Baz.q(Baz.java:2)", DiagnosticRedactor.redact(emptyCookie));

        String emptyBearer = "failed with Authorization: Bearer\n\tat app.Foo.bar(Foo.java:1)";
        assertEquals("failed with Authorization=[omitted]\n\tat app.Foo.bar(Foo.java:1)",
                DiagnosticRedactor.redact(emptyBearer));

        String suppressed = "java.io.IOException: Cookie: xs=1\n"
                + "\tSuppressed: java.io.IOException: close failed\n\t\tat app.Foo.close(Foo.java:9)";
        assertEquals("java.io.IOException: Cookie=[omitted]\n"
                + "\tSuppressed: java.io.IOException: close failed\n\t\tat app.Foo.close(Foo.java:9)",
                DiagnosticRedactor.redact(suppressed));
    }

    /** A word after Bearer or OAuth is a credential only when it looks like one. */
    @Test public void anOrdinaryWordAfterBearerOrOAuthStays() {
        for (String line : new String[]{"OAuth callback received", "Bearer credentials expired",
                "Basic settings opened", "Basic authentication failed"}) {
            assertEquals(line, DiagnosticRedactor.redact(line));
        }
    }

    /** Shapes the rules already handled, kept that way. */
    @Test public void shapesThatAlreadyWorkedStayWorking() {
        assertEquals("Install beside Meta's apps: invoked 3, 1 found, 0 missing",
                DiagnosticRedactor.redact("Install beside Meta's apps: invoked 3, 1 found, 0 missing"));
        assertEquals("{ \"access_token=[omitted] }", DiagnosticRedactor.redact("{ \"access_token\" : \"EAABspaced12\" }"));
        assertEquals("access_token=[omitted]&next=1", DiagnosticRedactor.redact("access_token=EAABquery123&next=1"));
        assertEquals("Authorization=[omitted]", DiagnosticRedactor.redact("Authorization:Bearer EAABnospace12"));
        assertEquals("sent Bearer [omitted]", DiagnosticRedactor.redact("sent Bearer%20EAABpercent12"));
        assertEquals("app: com.facebook.katana 580.0.0.51.74 (475019344) at 1790000000000",
                DiagnosticRedactor.redact("app: com.facebook.katana 580.0.0.51.74 (475019344) at 1790000000000"));
    }

    /**
     * What the credential rules must leave: the report's build lines, timestamps, stack frames
     * (one of them from a class whose name contains "Auth"), ordinary settings and a sentence that
     * only uses the word Basic.
     */
    @Test public void buildDataTimestampsAndStackFramesStay() {
        String[] lines = {
                "app: com.facebook.katana 580.0.0.51.74 (475019344)",
                "abi: app arm64, process 64-bit, device arm64-v8a,armeabi-v7a",
                "morphe: 0.3.4",
                "generated_utc: 2026-09-28T12:00:00.000Z",
                "downloads | 2026-09-28T12:00:01.234Z | main | ReelDownload | INFO | reel download tapped",
                "\tat app.morphe.extension.facebook.download.Downloader.connect(Downloader.java:120)",
                "\tat com.facebook.auth.login.AuthStateMachine.run(AuthStateMachine.java:44)",
                "Caused by: java.net.SocketTimeoutException: timeout=30000 attempts=3",
                "hide_paid_partnership=on, download_quality=best",
                "Basic settings opened",
        };
        for (String line : lines) assertEquals(line, DiagnosticRedactor.redact(line));
    }

    /** An Authorization value ends at its line, so the stack trace printed after it stays. */
    @Test public void aStackTraceAfterAnAuthorizationLineStays() {
        String trace = "java.io.IOException: 401 for Authorization: Bearer EAABtraceU26\n"
                + "\tat app.morphe.extension.facebook.download.Downloader.connect(Downloader.java:120)\n"
                + "\tat java.lang.Thread.run(Thread.java:1012)";

        assertEquals("java.io.IOException: 401 for Authorization=[omitted]\n"
                + "\tat app.morphe.extension.facebook.download.Downloader.connect(Downloader.java:120)\n"
                + "\tat java.lang.Thread.run(Thread.java:1012)", DiagnosticRedactor.redact(trace));
    }
}

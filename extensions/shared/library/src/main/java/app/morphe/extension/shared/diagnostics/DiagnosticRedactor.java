/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

/** Removes request addresses, credentials and device identifiers from exported text. */
public final class DiagnosticRedactor {
    /** Facebook's own hosts, its CDN and short-link domains, and Messenger's. */
    private static final String HOST_SUFFIXES =
            "(?:facebook\\.com|facebook\\.net|fbcdn\\.net|fbsbx\\.com|fb\\.com|fb\\.me|fb\\.watch"
                    + "|fb\\.gg|messenger\\.com|meta\\.com|meta\\.ai)";
    /**
     * Credential and device names. c_user, xs and datr are the cookies that make up a Facebook
     * session, and fr and sb go with them; fb_dtsg is its request token; family_device_id and
     * advertiser_id identify the phone across Meta's apps. xs, fr and sb are matched whole below,
     * being too short to look for inside a word.
     */
    private static final String CREDENTIAL_NAMES =
            "[a-z0-9_-]*(?:token|session|sessionid|sid|secret|password|passwd|signature|cookie"
                    + "|auth|credential|device_id|deviceid|install_id|installid|iid|openudid"
                    + "|uid|c_user|datr|fb_dtsg|machine_id|advertiser_id|adid)[a-z0-9_-]*|xs|fr|sb";
    /**
     * What comes between a name and its value: the name's closing quote, escaped once or more when
     * the JSON is itself inside a string, then =, : or =>, written plain or percent-encoded.
     */
    private static final String SEPARATOR = "(?:\\\\*[\"']|%22)?\\s*(?:=>|[=:]|%3[ad])\\s*";
    /**
     * A quoted value, whole. A double-quoted one ends at a quote escaped exactly as its opening
     * one was, so an escaped quote inside a JSON string, or JSON inside a string, doesn't end it
     * early. An unclosed one runs to the end of its line.
     */
    private static final String QUOTED =
            "(?:(?<q>\\\\*)\"(?:[^\\\\\"\\r\\n]|\\\\++(?!\")|(?!\\k<q>\")\\\\+\")*(?:\\k<q>\")?"
                    + "|'(?:[^\\\\'\\r\\n]|\\\\.)*'?)";
    /** An object or list held by a credential's name, nested up to three deep. */
    private static final String BLOCK;
    static {
        String plain = "[^{}\\[\\]\\r\\n]";
        String block = "(?:\\{" + plain + "*\\}|\\[" + plain + "*\\])";
        for (int depth = 1; depth < 3; depth++) {
            String inner = "(?:" + plain + "|" + block + ")*";
            block = "(?:\\{" + inner + "\\}|\\[" + inner + "\\])";
        }
        BLOCK = block;
    }
    /**
     * An Authorization or cookie header, whose whole value is private: every cookie in it, and the
     * credential after Bearer, Basic or OAuth, not only the scheme's name. An unquoted value runs
     * to the end of its line; a scheme left alone at a line's end takes the next line's token, and
     * a folded header takes its indented continuation lines, though never a stack frame.
     */
    private static final String HEADER =
            "(?i)\\b((?:proxy-)?authorization|set-cookie|cookie)" + SEPARATOR + "(?:" + QUOTED
                    + "|(?:(?:bearer|basic|digest|oauth|negotiate)\\s+)?[^\\r\\n]*"
                    + "(?:\\r?\\n[ \\t]++(?!at\\s)[^\\r\\n]*)*)";
    /** A bearer or OAuth credential written with no header name in front of it. */
    private static final String BARE_SCHEME = "(?i)\\b(bearer|oauth)(?:\\s+|%20)(?!\\[)[a-z0-9._~+/=%-]{8,}";
    /**
     * Names carrying the id of one post, story, comment or message. Each of these resolves to
     * something somebody can open, so a shared report would otherwise carry a slice of what was
     * read. They are matched whole rather than as a substring, so an ordinary setting such as
     * {@code hide_paid_partnership} keeps its value.
     */
    private static final String CONTENT_ID_NAMES =
            "(?:[a-z0-9]+_)*(?:aid|fbid|story_?fbid|story_?id|post_?id|feedback_?id|video_?id|item_?id"
                    + "|group_?id|page_?id|profile_?id|actor_?id|thread_?id|cid|comment_?id|msg_?id"
                    + "|message_?id)";
    /**
     * Ids are printed in comma separated lists, and the value pattern the credential rule uses
     * stops at the first comma, so everything after the first id stayed in the report.
     */
    private static final String CONTENT_ID_VALUE = "\"?[^\\s;&\"'<>]+";
    /**
     * A bare id, for the places that print a list of them with no name in front. Facebook's
     * account ids are fifteen digits (the newer ones seventeen) and its post and story ids run to
     * nineteen; nothing else these reports carry is a number that long. A millisecond timestamp
     * is thirteen digits, so it stays readable.
     *
     * <p>Bounded by digits rather than by word edges. A CDN file name joins its ids with
     * underscores ({@code 475148478_1134540631592283_1316146539584337463_n.jpg}), an underscore is
     * a word character, and a word-bounded rule found no edge there.
     */
    private static final String BARE_CONTENT_ID = "(?<!\\d)\\d{15,21}(?!\\d)";
    /**
     * A creator's name, as the bundle writes it into a toast or a banner: between Unicode's
     * first-strong isolate U+2068 and its pop U+2069. Every toast is written to the buffer as it
     * is shown, in whatever language the phone speaks, so the name cannot be found by the words
     * around it. The isolate pair marks it in every language. A run with no pop is cut to the
     * end of the line, so a name that lost its closing mark is still not printed.
     */
    private static final String ISOLATED_NAME = "⁨[^⁩\\n]*⁩?";
    /**
     * An account handle, which starts with @ and stands on its own. One glued to something in
     * front of it is not a handle: an email address, or the identity hash Java prints after a
     * class name.
     */
    private static final String HANDLE = "(?<![\\w.@/:])@[A-Za-z0-9_.]{2,}";
    /**
     * One of Facebook's hosts without a scheme, with any port or path after it. The subdomain is
     * optional: the rule asked for one, so {@code facebook.com/dana.q.1987} passed while
     * {@code www.facebook.com/dana.q.1987} didn't. A host has to end at a word edge, so a package
     * name ({@code com.facebook.katana}) and a longer name ({@code facebook.community}) stay.
     */
    private static final String HOST = "(?i)\\b(?:[a-z0-9-]+\\.)*" + HOST_SUFFIXES + "\\b(?:[:/][^\\s\"'<>]*)?";

    private DiagnosticRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return "";
        return text
                .replaceAll(ISOLATED_NAME, "[name omitted]")
                .replaceAll(HANDLE, "[handle omitted]")
                .replaceAll("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"'<>]+", "[url omitted]")
                .replaceAll(HOST, "[host omitted]")
                .replaceAll(HEADER, "$1=[omitted]")
                .replaceAll(BARE_SCHEME, "$1 [omitted]")
                .replaceAll("(?i)\\b(" + CREDENTIAL_NAMES + ")" + SEPARATOR
                        + "(?:" + QUOTED + "|" + BLOCK + "|[^\\s;,&\"'<>]+)", "$1=[omitted]")
                .replaceAll("(?i)\\b(" + CONTENT_ID_NAMES + ")" + SEPARATOR
                        + "(?:" + QUOTED + "|" + CONTENT_ID_VALUE + ")", "$1=[omitted]")
                .replaceAll(BARE_CONTENT_ID, "[id omitted]");
    }
}

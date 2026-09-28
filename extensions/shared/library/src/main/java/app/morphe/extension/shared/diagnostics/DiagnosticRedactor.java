/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes request addresses, credentials and device identifiers from exported text. */
public final class DiagnosticRedactor {
    /** Facebook's own hosts, its CDN and short-link domains, and Messenger's. */
    private static final String HOST_SUFFIXES =
            "(?:facebook\\.com|facebook\\.net|fbcdn\\.net|fbsbx\\.com|fb\\.com|fb\\.me|fb\\.watch"
                    + "|fb\\.gg|messenger\\.com|meta\\.com|meta\\.ai)";
    /**
     * Credential and device names. c_user, xs and datr are the cookies that make up a Facebook
     * session, and fr and sb go with them; fb_dtsg is its request token; family_device_id,
     * X-FB-Device-ID and advertiser_id identify the phone across Meta's apps. xs, fr, sb and pwd
     * are matched whole below, being too short to look for inside a word.
     */
    private static final String CREDENTIAL_NAMES =
            "[a-z0-9_-]*(?:token|session|sessionid|sid|secret|password|passwd|passphrase|passcode|signature"
                    + "|cookie|auth|credential|api_?key|access_?key|private_?key|device[_-]?id|install[_-]?id"
                    + "|iid|openudid|uid|c_user|datr|fb_dtsg|machine[_-]?id|advertiser[_-]?id"
                    + "|advertising[_-]?id|adid)[a-z0-9_-]*|xs|fr|sb|pwd";
    /** Names whose unquoted value can hold spaces and semicolons, so it runs to the end of its line. */
    private static final String PASSWORD_NAMES = "[a-z0-9_-]*(?:password|passwd|passphrase|passcode)[a-z0-9_-]*|pwd";
    /**
     * A quote written as a JSON unicode escape, backslash u 0022. Built in two parts so no tool
     * that reads this file turns the escape into the quote itself.
     */
    private static final String ESCAPED_QUOTE = "\\\\" + "u0022";
    /**
     * A name's closing quote: plain, escaped once or more when the JSON is itself inside a string,
     * as a unicode escape, as HTML or percent-encoded.
     */
    private static final String QUOTE_MARK = "(?:\\\\*[\"']|" + ESCAPED_QUOTE + "|&quot;|%22)";
    /**
     * What comes between a name and its value: spaces, the name's closing quote, then =, :, =>
     * or ->, written plain or percent-encoded. It never crosses a line break, so a name at the end
     * of a line can't take the stack frame printed under it.
     */
    private static final String SEPARATOR = "[ \\t]*" + QUOTE_MARK + "?[ \\t]*(?:=>|->|[=:]|%3[ad])[ \\t]*";
    /**
     * A quoted value, whole. A double-quoted one ends at a quote escaped exactly as its opening
     * one was, so an escaped quote inside a JSON string, or JSON inside a string, doesn't end it
     * early. Values quoted by unicode escape, HTML or percent-encoding end at the same mark. An
     * unclosed one runs to the end of its line.
     */
    private static final String QUOTED =
            "(?:(?<q>\\\\*)\"(?:[^\\\\\"\\r\\n]|\\\\++(?!\")|(?!\\k<q>\")\\\\+\")*(?:\\k<q>\")?"
                    + "|'(?:[^\\\\'\\r\\n]|\\\\.)*'?"
                    + "|" + ESCAPED_QUOTE + "(?:(?!" + ESCAPED_QUOTE + ")[^\\r\\n])*(?:" + ESCAPED_QUOTE + ")?"
                    + "|&quot;(?:(?!&quot;)[^\\r\\n])*(?:&quot;)?"
                    + "|%22(?:(?!%22)[^\\s&])*(?:%22)?)";
    /** The schemes an Authorization value names before its credential. */
    private static final String SCHEME = "(?:bearer|basic|digest|oauth|negotiate)";
    /** A credential's characters: the token68 of RFC 9110, and percent signs. */
    private static final String TOKEN = "[a-z0-9._~+/=%-]+";
    /** An indented line Java prints for a trace, which a header's continuation must never take. */
    private static final String NOT_TRACE = "(?!at\\s|caused by:|suppressed:|\\.\\.\\.\\s)";
    /**
     * An Authorization or cookie header, whose whole value is private: every cookie in it, and the
     * credential after Bearer, Basic or OAuth, not only the scheme's name. A value may start on
     * the next line when that line starts with a scheme, and a credential may carry on alone on
     * the line after it. An unquoted value without a scheme runs to the end of its line and takes
     * indented continuation lines, but never a Java trace line.
     */
    private static final String HEADER =
            "(?i)\\b((?:proxy-)?authorization|set-cookie|cookie)" + SEPARATOR + "(?:" + QUOTED
                    + "|(?:\\r?\\n[ \\t]*(?=" + SCHEME + "[ \\t]))?"
                    + "(?:" + SCHEME + "(?:[ \\t]+|[ \\t]*\\r?\\n[ \\t]*" + NOT_TRACE + ")" + TOKEN
                    + "(?:\\r?\\n[ \\t]*" + TOKEN + "(?=[ \\t]*(?:\\r?\\n|$)))?[^\\r\\n]*"
                    + "|[^\\r\\n]*(?:\\r?\\n[ \\t]++" + NOT_TRACE + "[^\\r\\n]*)*))";
    /**
     * A name and value pair as HAR files and header dumps print them, {"name": ..., "value": ...},
     * where the name is a header or cookie the rules know. Only the value goes.
     */
    private static final String NAME_VALUE_PAIR =
            "(?i)(\\\\*\"name\\\\*\"[ \\t]*:[ \\t]*\\\\*\"(?:(?:proxy-)?authorization|set-cookie|" + CREDENTIAL_NAMES
                    + ")\\\\*\"[ \\t]*,[ \\t]*\\\\*\"value\\\\*\"[ \\t]*:[ \\t]*)" + QUOTED;
    /**
     * A Bearer, OAuth or Basic credential written with no header name in front of it. A word
     * counts as one only with a digit, +, / or = in it, so "OAuth callback" and "Basic settings"
     * stay as written.
     */
    private static final String BARE_SCHEME =
            "(?i)\\b(bearer|oauth|basic)(?:[ \\t]+|%20)(?=[a-z._~-]*[0-9+/=%])[a-z0-9._~+/=%-]{8,}";
    /** Where a credential's object or list starts: its name, the separator, then { or [. */
    private static final Pattern BLOCK_START =
            Pattern.compile("(?i)\\b(" + CREDENTIAL_NAMES + ")" + SEPARATOR + "(?=[\\[{])");
    /** How far an object or list is followed before it's cut at the end of its first line. */
    private static final int BLOCK_MAX_CHARS = 8_192;
    /**
     * Names carrying the id of one post, story, comment or message. Each of these resolves to
     * something somebody can open, so a shared report would otherwise carry a slice of what was
     * read. The short ones, aid and cid, are matched whole or after an underscore or hyphen, so an
     * ordinary setting such as {@code hide_paid_partnership} keeps its value. The longer ones may
     * follow any prefix, camel case included ({@code topLevelPostId}), and take a hyphen as well as
     * an underscore.
     */
    private static final String CONTENT_ID_NAMES =
            "(?:[a-z0-9]+[_-])*(?:aid|cid)|[a-z0-9_-]*(?:fbid|story[_-]?id|post[_-]?id|feedback[_-]?id"
                    + "|video[_-]?id|item[_-]?id|group[_-]?id|page[_-]?id|profile[_-]?id|actor[_-]?id"
                    + "|thread[_-]?id|comment[_-]?id|msg[_-]?id|message[_-]?id)";
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
        String passed = text
                .replaceAll(ISOLATED_NAME, "[name omitted]")
                .replaceAll(HANDLE, "[handle omitted]")
                .replaceAll("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"'<>]+", "[url omitted]")
                .replaceAll(HOST, "[host omitted]")
                .replaceAll(NAME_VALUE_PAIR, "$1[omitted]")
                .replaceAll(HEADER, "$1=[omitted]")
                .replaceAll(BARE_SCHEME, "$1 [omitted]")
                .replaceAll("(?i)\\b(" + PASSWORD_NAMES + ")" + SEPARATOR + "(?:" + QUOTED + "|[^\\r\\n]*)",
                        "$1=[omitted]");
        return withoutCredentialBlocks(passed)
                .replaceAll("(?i)\\b(" + CREDENTIAL_NAMES + ")" + SEPARATOR
                        + "(?:" + QUOTED + "|[^\\s,&\"'<>]+)", "$1=[omitted]")
                .replaceAll("(?i)\\b(" + CONTENT_ID_NAMES + ")" + SEPARATOR
                        + "(?:" + QUOTED + "|" + CONTENT_ID_VALUE + ")", "$1=[omitted]")
                .replaceAll(BARE_CONTENT_ID, "[id omitted]");
    }

    /**
     * Every object or list a credential's name holds, whole, however deep it nests. A pattern can
     * only follow nesting to a depth written into it, and it can't tell a brace inside a quoted
     * string from one that closes the object, so this counts brackets outside quotes instead.
     */
    private static String withoutCredentialBlocks(String text) {
        Matcher start = BLOCK_START.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && start.find(from)) {
            int end = blockEnd(text, start.end());
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, copied, start.start()).append(start.group(1)).append("=[omitted]");
            copied = end;
            from = end;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * Just past the bracket that closes the one at [open], or the end of its line when none does
     * within {@link #BLOCK_MAX_CHARS}. A quote opens or closes a string when it's escaped the way
     * the object's first quote is, so JSON inside a JSON string is read at its own level.
     */
    private static int blockEnd(String text, int open) {
        int limit = Math.min(text.length(), open + BLOCK_MAX_CHARS);
        int level = -1;
        boolean quoted = false;
        int depth = 0;
        for (int at = open; at < limit; at++) {
            char c = text.charAt(at);
            if (c == '"') {
                int run = 0;
                while (at - run - 1 >= open && text.charAt(at - run - 1) == '\\') run++;
                if (level < 0) level = run;
                if (level == 0 ? run % 2 == 0 : run == level) quoted = !quoted;
            } else if (!quoted) {
                if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && --depth == 0) {
                    return at + 1;
                }
            }
        }
        int line = open;
        while (line < text.length() && text.charAt(line) != '\n' && text.charAt(line) != '\r') line++;
        return line;
    }
}

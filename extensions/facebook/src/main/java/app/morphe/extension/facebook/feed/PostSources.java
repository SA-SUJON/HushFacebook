/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide posts from people, Pages and sites: the person's list, and who wrote a feed post and where
 * its links go, read the way Facebook's own code reads them.
 *
 * <p>Each line of the list is one rule: a profile or Page id (only digits), a site (a domain such
 * as example.com, which takes its subdomains too), or a name, matched whole against the name of a
 * person or Page that wrote the post, capital letters aside. A post goes when one of its authors or
 * one of its links matches, and so does a share of such a post: the post a share wraps is read too.
 *
 * <p>The authors are the story's {@code actors}, a list GraphQLStory reads with a Redex-renamed
 * accessor and a renamed model class, so the patch fills in {@link #actors} for it. The links are
 * the {@code url} of each of the story's {@code attachments}, through {@link #attachments}, and a
 * link Facebook routes through its own redirect (l.facebook.com/l.php?u=) counts as the site it
 * leads to. Each id, name and url is read with the kept {@code getCachedString(int)}, which checks
 * the native tree is still there, so a released model reads as nothing rather than crashing.
 *
 * <p>Nothing here keeps, logs or reports a name, an id or a link. The report counts rules by number.
 */
public final class PostSources {
    /** The most rules the list holds, and the longest one. Longer lines and extra ones are left out. */
    public static final int MAX_RULES = 200;
    public static final int MAX_LENGTH = 80;
    /**
     * The room the stored list takes in UTF-8, so it fits in a settings file beside the word lists
     * whatever it's written in. Rules past it are left out like rules past {@link #MAX_RULES}.
     */
    public static final int MAX_LIST_BYTES = 16 * 1024;

    static final String ACTORS_FIELD = "actors";
    static final String ATTACHMENTS_FIELD = "attachments";
    static final int ID_KEY = "id".hashCode();
    static final int NAME_KEY = "name".hashCode();
    static final int URL_KEY = "url".hashCode();

    static final StoryFlag.Accessor ACTORS = PostSources::actors;
    static final StoryFlag.Accessor ATTACHMENTS = PostSources::attachments;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{3,20}");
    private static final Pattern HOST = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** What one line of the list is. */
    enum Kind { ID, SITE, NAME }

    /** One rule: what it is, the value it matches, and its line number for the report. */
    static final class Rule {
        final Kind kind;
        final String value;
        final int number;

        Rule(Kind kind, String value, int number) {
            this.kind = kind;
            this.value = value;
            this.number = number;
        }
    }

    /** What reading one feed unit found. Only {@link #READ} carries authors and links. */
    enum Outcome {
        READ("sources read"),
        NO_UNIT("no feed unit"),
        NOT_A_STORY("not a story"),
        NO_READER("reader missing"),
        NO_ACCESSOR("accessor not patched"),
        READ_FAILED("read failed");

        /** What the report counts this under: a shape, never content. */
        final String reason;

        Outcome(String reason) {
            this.reason = reason;
        }
    }

    /** The ids, names and link hosts of a post and the post it shares. */
    static final class Found {
        final Outcome outcome;
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> names = new LinkedHashSet<>();
        final Set<String> hosts = new LinkedHashSet<>();

        Found(Outcome outcome) {
            this.outcome = outcome;
        }
    }

    private PostSources() {
    }

    /**
     * Injection point, filled in by the patch: the story's {@code actors}, the people and Pages that
     * wrote it, as a list of tree models, or null. The patch replaces this body with a call to
     * GraphQLStory's accessor, whose name changes every build. Only a GraphQLStory may be passed.
     */
    public static Object actors(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection point, filled in by the patch: the story's {@code attachments} as a list of
     * GraphQLStoryAttachment, or null. Only a GraphQLStory may be passed.
     */
    public static Object attachments(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    // The list.

    /** The rules in a stored list, in order. Blank, too long and repeated lines are left out. */
    static List<Rule> rules(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) return Collections.emptyList();
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : stored.split("\n", -1)) {
            if (rules.size() == MAX_RULES) break;
            Rule rule = rule(line, rules.size() + 1);
            if (rule == null || !seen.add(rule.kind + ":" + rule.value)) continue;
            rules.add(rule);
        }
        return rules;
    }

    /** What one typed line is, or null when it's blank or too long. */
    @Nullable
    static Rule rule(String line, int number) {
        String trimmed = normalize(line);
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) return null;
        if (DIGITS.matcher(trimmed).matches()) return new Rule(Kind.ID, trimmed, number);
        String site = site(trimmed);
        if (site != null) return new Rule(Kind.SITE, site, number);
        return new Rule(Kind.NAME, trimmed.toLowerCase(Locale.ROOT), number);
    }

    /**
     * The list as it's kept: one rule per line, trimmed, in the order typed, with what can't be a
     * rule and what repeats left out. A name keeps its capitals, so the list reads as typed.
     */
    public static String clean(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return "";
        StringBuilder kept = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        int bytes = 0;
        for (String line : typed.split("\n", -1)) {
            if (count == MAX_RULES) break;
            Rule rule = rule(line, count + 1);
            if (rule == null || seen.contains(rule.kind + ":" + rule.value)) continue;
            String text = normalize(line);
            int size = text.getBytes(StandardCharsets.UTF_8).length + (count > 0 ? 1 : 0);
            if (bytes + size > MAX_LIST_BYTES) break;
            seen.add(rule.kind + ":" + rule.value);
            if (count > 0) kept.append('\n');
            kept.append(text);
            bytes += size;
            count++;
        }
        return kept.toString();
    }

    /** Whether [value] is a list as {@link #clean} keeps it, so a settings file can't hold a looser one. */
    public static boolean isClean(@Nullable String value) {
        return value != null && value.equals(clean(value));
    }

    /** How many non-blank lines of [typed] {@link #clean} leaves out. */
    public static int leftOut(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return 0;
        int lines = 0;
        for (String line : typed.split("\n", -1)) {
            if (!normalize(line).isEmpty()) lines++;
        }
        String clean = clean(typed);
        return lines - (clean.isEmpty() ? 0 : clean.split("\n", -1).length);
    }

    /** How many rules a stored list holds. */
    public static int count(@Nullable String stored) {
        return rules(stored).size();
    }

    /** A line trimmed, in one Unicode form, with its runs of spaces made one. */
    private static String normalize(String line) {
        String composed = Normalizer.normalize(line, Normalizer.Form.NFC);
        return SPACES.matcher(composed).replaceAll(" ").trim();
    }

    /**
     * The site a typed line names, or null when it isn't one: a domain with a dot and no spaces,
     * with any scheme, path and leading www. dropped. "https://www.Example.com/news" is example.com.
     */
    @Nullable
    static String site(String typed) {
        if (typed.indexOf(' ') >= 0 || typed.indexOf('.') < 0) return null;
        String value = typed.toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int end = value.length();
        for (char stop : new char[] {'/', '?', '#', ':'}) {
            int at = value.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        value = value.substring(0, end);
        if (value.startsWith("www.")) value = value.substring(4);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return HOST.matcher(value).matches() ? value : null;
    }

    /**
     * The site a link goes to, lowercase and without www., or null when it has no host. A link
     * through Facebook's redirect counts as the one it carries in its u parameter.
     */
    @Nullable
    static String host(@Nullable String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if ((host.equals("l.facebook.com") || host.equals("lm.facebook.com")) && "/l.php".equals(uri.getPath())) {
                String target = queryParameter(uri.getRawQuery(), "u");
                if (target != null) {
                    String inner = host(target);
                    if (inner != null) return inner;
                }
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception malformed) {
            return null;
        }
    }

    @Nullable
    private static String queryParameter(@Nullable String query, String name) throws Exception {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals(name) && equals >= 0) return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
        }
        return null;
    }

    /**
     * The number of the first rule [found] matches, or 0: an id the same as an author's, a site the
     * same as a link's host or a domain it ends in, or a name the same as an author's, capitals and
     * extra spaces aside.
     */
    static int match(List<Rule> rules, Found found) {
        for (Rule rule : rules) {
            switch (rule.kind) {
                case ID:
                    if (found.ids.contains(rule.value)) return rule.number;
                    break;
                case SITE:
                    for (String host : found.hosts) {
                        if (host.equals(rule.value) || host.endsWith("." + rule.value)) return rule.number;
                    }
                    break;
                case NAME:
                    if (found.names.contains(rule.value)) return rule.number;
                    break;
            }
        }
        return 0;
    }

    // The post.

    /**
     * The authors and links of a feed unit and of the post it shares. Never throws. A unit that
     * isn't a story, or a part this can't read, answers why with nothing found, and the rule keeps it.
     */
    static Found read(@Nullable Object feedUnit, StoryFlag.Accessor actors, StoryFlag.Accessor attachments,
            StoryFlag.Accessor attached) {
        if (feedUnit == null) return new Found(Outcome.NO_UNIT);
        PostText.Members members = PostText.members();
        PostText.report();
        if (members.story == null || members.treeModel == null || members.cachedString == null) {
            return new Found(Outcome.NO_READER);
        }
        if (!members.story.isInstance(feedUnit)) return new Found(Outcome.NOT_A_STORY);

        Found found = new Found(Outcome.READ);
        Outcome own = readStory(feedUnit, actors, attachments, members, found);
        if (own != Outcome.READ) return new Found(own);

        Object wrapped;
        try {
            wrapped = attached.model(feedUnit);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "attached story accessor", failure);
            return new Found(Outcome.READ_FAILED);
        }
        if (wrapped == StoryFlag.NOT_PATCHED) return new Found(Outcome.NO_ACCESSOR);
        if (wrapped != null && members.story.isInstance(wrapped)) {
            Outcome shared = readStory(wrapped, actors, attachments, members, found);
            if (shared != Outcome.READ) return new Found(shared);
        }
        return found;
    }

    /** Adds [story]'s author ids and names and its link hosts to [found]. */
    private static Outcome readStory(Object story, StoryFlag.Accessor actors, StoryFlag.Accessor attachments,
            PostText.Members members, Found found) {
        Object authors;
        Object links;
        try {
            authors = actors.model(story);
            links = attachments.model(story);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author or link accessor", failure);
            return Outcome.READ_FAILED;
        }
        if (authors == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.POST_WORDS, "method", StoryFlag.STORY_CLASS,
                    "the " + ACTORS_FIELD + " accessor");
            return Outcome.NO_ACCESSOR;
        }
        if (links == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.POST_WORDS, "method", StoryFlag.STORY_CLASS,
                    "the " + ATTACHMENTS_FIELD + " accessor");
            return Outcome.NO_ACCESSOR;
        }
        try {
            for (Object author : items(authors)) {
                if (!members.treeModel.isInstance(author)) continue;
                String id = string(members, author, ID_KEY);
                if (id != null && !id.isEmpty()) found.ids.add(id);
                String name = string(members, author, NAME_KEY);
                if (name != null) {
                    String normalized = normalize(name).toLowerCase(Locale.ROOT);
                    if (!normalized.isEmpty()) found.names.add(normalized);
                }
            }
            for (Object link : items(links)) {
                if (!members.treeModel.isInstance(link)) continue;
                String host = host(string(members, link, URL_KEY));
                if (host != null) found.hosts.add(host);
            }
            return Outcome.READ;
        } catch (InvocationTargetException failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author or link reader", failure.getCause());
            return Outcome.READ_FAILED;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(FamilyNames.POST_WORDS, "author or link reader", failure);
            return Outcome.READ_FAILED;
        }
    }

    private static Iterable<?> items(@Nullable Object list) {
        return list instanceof Iterable ? (Iterable<?>) list : Collections.emptyList();
    }

    @Nullable
    private static String string(PostText.Members members, Object model, int key) throws ReflectiveOperationException {
        Object value = members.cachedString.invoke(model, key);
        return value instanceof String ? (String) value : null;
    }
}

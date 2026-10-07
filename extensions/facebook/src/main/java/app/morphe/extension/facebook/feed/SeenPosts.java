/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Posts you've already scrolled past stay out of the feed on later loads.
 *
 * <p>Facebook's viewport logger decides a post was seen: when a post leaves the screen after being
 * on it long enough to count as a view, it calls {@code persistSeenState} with the feed unit. That
 * is the signal, so the dwell is Facebook's own, and it fires as the post leaves, which means the
 * post on screen is never judged. The patch hands that unit to {@link #seen}, which remembers the
 * unit's cache id. The feed guard asks {@link #hideReason} for every unit it's about to add, and a
 * remembered one is dropped before Facebook adds it.
 *
 * <p>Only a short hash of each id is kept, in one file in the app's own storage with the time it was
 * seen. It never leaves the phone and isn't part of an exported settings file. At most {@link #CAP}
 * posts are kept, the oldest forgotten first, and a post is forgotten once it's older than the days
 * the setting names. Writes are batched off the main thread.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, nothing is hidden and
 * nothing is remembered.
 */
public final class SeenPosts {
    /** How long a seen post stays hidden. */
    public enum Keep {
        ONE_DAY(1, "1_day"),
        THREE_DAYS(3, "3_days"),
        SEVEN_DAYS(7, "7_days"),
        THIRTY_DAYS(30, "30_days");

        public final int days;

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        Keep(int days, String fileValue) {
            this.days = days;
            this.fileValue = fileValue;
        }

        /** The choice a settings file names, or null when it names none this build knows. */
        @Nullable
        public static Keep fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (Keep keep : values()) {
                if (keep.fileValue.equals(value)) return keep;
            }
            return null;
        }
    }

    /** Most posts remembered. The oldest go first. */
    static final int CAP = 5000;

    /** Counted under the patch's name each time a seen post is kept out of the feed. */
    static final String HIDDEN = "Already seen posts hidden";
    /** Counted when a seen post is new to the store. */
    static final String REMEMBERED = "Seen posts remembered";
    /** Counted when a seen post's unit gave no id to remember it by. */
    static final String NO_ID = "Seen posts with no id";

    /** The reason the feed counts a hidden post under. */
    static final String REASON = "already seen";

    private static final String FAMILY = FamilyNames.SEEN_POSTS;
    private static final String FILE_NAME = "hushfacebook_seen_posts.txt";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long WRITE_DELAY_MS = 5_000;

    /** Id to the time it was seen, oldest first. Guarded by itself. */
    private static final LinkedHashMap<String, Long> STORE = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;
    private static boolean writeScheduled;

    @Nullable static Boolean inBuildForTests;
    @Nullable static File fileForTests;
    @Nullable static Runnable laterForTests;
    static LongSupplier clock = System::currentTimeMillis;

    private static volatile Method cacheIdReader;
    private static volatile Class<?> cacheIdOwner;

    private SeenPosts() {
    }

    private static boolean inBuild() {
        Boolean forTests = inBuildForTests;
        return forTests != null ? forTests : SettingsStatus.seenPosts();
    }

    /**
     * Injection point, at the start of the viewport logger's {@code persistSeenState}: the feed unit
     * Facebook just decided you've seen. Remembers it while the switch is on. Never throws, and
     * changes nothing about what Facebook does.
     */
    public static void seen(Object unit) {
        try {
            HookStatus.invoked(FAMILY);
            if (unit == null || !Utils.settingsReady() || !Settings.HIDE_SEEN_POSTS.get()) return;
            if (!isPost(unit)) return;
            String id = idOf(unit);
            if (id == null) {
                HookStatus.counted(FAMILY, NO_ID);
                return;
            }
            HookStatus.bound(FAMILY, "seen state");
            if (remember(id, clock.getAsLong())) HookStatus.counted(FAMILY, REMEMBERED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "seen state", failure);
        }
    }

    /**
     * The feed guard's question about a unit it's about to add: {@link #REASON} when the post was
     * seen within the days the setting names and the switch is on, otherwise null. Never throws.
     */
    @Nullable
    static String hideReason(@Nullable Object unit) {
        try {
            if (unit == null || !inBuild()) return null;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.HIDE_SEEN_POSTS.get()) return null;
            if (!isPost(unit)) return null;
            String id = idOf(unit);
            if (id == null) return null;
            HookStatus.bound(FAMILY, "feed guard");
            long now = clock.getAsLong();
            if (!isRemembered(id, now, Settings.SEEN_POSTS_KEEP.get().days * DAY_MS)) return null;
            HookStatus.counted(FAMILY, HIDDEN);
            return REASON;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed guard", failure);
            return null;
        }
    }

    /** Whether the unit is a post. A unit that isn't a GraphQLStory has no seen state worth keeping. */
    private static boolean isPost(Object unit) {
        PostText.Members found = PostText.members();
        return found.story == null || found.story.isInstance(unit);
    }

    /** The unit's cache id through its public {@code getCacheId()}, hashed, or null when it has none. */
    @Nullable
    static String idOf(Object unit) {
        try {
            Method reader = cacheIdReader;
            if (reader == null || cacheIdOwner != unit.getClass()) {
                reader = unit.getClass().getMethod("getCacheId");
                if (reader.getReturnType() != String.class) return null;
                cacheIdOwner = unit.getClass();
                cacheIdReader = reader;
            }
            Object id = reader.invoke(unit);
            if (!(id instanceof String) || ((String) id).isEmpty()) return null;
            return hash((String) id);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String hash(String id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Remembers [id] as seen at [now]. True when it's new to the store. */
    static boolean remember(String id, long now) {
        synchronized (STORE) {
            loadLocked();
            Long before = STORE.remove(id);
            STORE.put(id, now);
            trimLocked();
            dirty = true;
            scheduleWriteLocked();
            return before == null;
        }
    }

    /** Whether [id] was seen less than [keepMs] before [now]. An older one is dropped. */
    static boolean isRemembered(String id, long now, long keepMs) {
        synchronized (STORE) {
            loadLocked();
            Long at = STORE.get(id);
            if (at == null) return false;
            if (now - at >= keepMs) {
                STORE.remove(id);
                dirty = true;
                scheduleWriteLocked();
                return false;
            }
            return true;
        }
    }

    /** How many posts are remembered. */
    static int size() {
        synchronized (STORE) {
            loadLocked();
            return STORE.size();
        }
    }

    /** The Forget seen posts row: empties the store and its file. */
    public static void clear() {
        synchronized (STORE) {
            loaded = true;
            STORE.clear();
            dirty = true;
        }
        writeNow();
    }

    private static void trimLocked() {
        while (STORE.size() > CAP) {
            Iterator<String> oldest = STORE.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Writes the store now, on this thread. */
    static void writeNow() {
        List<String> lines = new ArrayList<>();
        synchronized (STORE) {
            writeScheduled = false;
            if (!dirty) return;
            dirty = false;
            for (Map.Entry<String, Long> entry : STORE.entrySet()) lines.add(entry.getValue() + " " + entry.getKey());
        }
        File file = file();
        if (file == null) return;
        File temporary = new File(file.getPath() + ".tmp");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            for (String line : lines) out.write(line + "\n");
        } catch (IOException failure) {
            Logger.printDebug(() -> "Seen posts: could not write the store: " + failure.getClass().getSimpleName());
            return;
        }
        if (!temporary.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            temporary.renameTo(file);
        }
    }

    private static void scheduleWriteLocked() {
        if (writeScheduled) return;
        writeScheduled = true;
        Runnable later = laterForTests;
        if (later != null) {
            later.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> Utils.runOnBackgroundThread(SeenPosts::writeNow), WRITE_DELAY_MS);
    }

    private static void loadLocked() {
        if (loaded) return;
        loaded = true;
        File file = file();
        if (file == null || !file.isFile()) return;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                int space = line.indexOf(' ');
                if (space <= 0) continue;
                try {
                    STORE.put(line.substring(space + 1), Long.parseLong(line.substring(0, space)));
                } catch (NumberFormatException skip) {
                    // A damaged line is dropped; the rest still count.
                }
            }
            trimLocked();
        } catch (IOException failure) {
            Logger.printDebug(() -> "Seen posts: could not read the store: " + failure.getClass().getSimpleName());
        }
    }

    @Nullable
    private static File file() {
        File forTests = fileForTests;
        if (forTests != null) return forTests;
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FILE_NAME);
    }

    /** Forgets what's in memory, so the next use reads the file again. For tests. */
    static void resetForTests() {
        synchronized (STORE) {
            STORE.clear();
            loaded = false;
            dirty = false;
            writeScheduled = false;
        }
        inBuildForTests = null;
        fileForTests = null;
        laterForTests = null;
        clock = System::currentTimeMillis;
    }

    /** The diagnostic report's line about the store: how many posts, never which. */
    public static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() {
            return "SEEN POSTS";
        }

        @Override public List<String> lines() {
            List<String> lines = new ArrayList<>();
            if (!inBuild()) return lines;
            lines.add("Remembered: " + size() + " of " + CAP + ", kept for "
                    + (Utils.settingsReady() ? Settings.SEEN_POSTS_KEEP.get().days : Keep.SEVEN_DAYS.days) + " days");
            return lines;
        }
    };

    /** The toast after Forget seen posts. */
    public static String clearedMessage() {
        return L10n.t("Seen posts forgotten.");
    }
}

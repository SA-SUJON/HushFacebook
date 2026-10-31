/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Loads the next page of Home's Stories tray when the tray filters leave its row short (#317).
 *
 * <p>While the tray has a next page, its row ends in three gray placeholder tiles. Facebook loads
 * that page from the row's scroll listener when a drag settles with the last bucket in view, but it
 * skips the load once the placeholders themselves fill the view past the buckets, and loading from
 * a placeholder coming into view is switched off for some accounts. With Pages', watched or
 * suggested stories filtered out, a page of 17 buckets can come down to one, the placeholders take
 * the rest of the row, and the next page never loads.
 *
 * <p>The patch makes that listener a {@link Pager}: it reads the last tile in view and the kept
 * bucket count the way the listener does, and asks the pager for the next page the way the listener
 * does after a drag. It hands the listener and its RecyclerView to {@link #watch} first thing in
 * onScrolled. Each time the filter leaves buckets out, {@link #droppedBuckets} waits for the row to
 * settle and asks for the next page while the last kept bucket or a placeholder is in view. Each
 * filtered page repeats it, and so does a scroll that stops at the row's end, so the tray keeps
 * paging until the kept stories fill the row or there are no more pages. Ten pages in a row that add
 * no kept story stop it, so a tray of nothing but suggestions isn't paged forever. Nothing changes
 * while the filters keep every bucket.
 */
public final class StoriesTrayPaging {
    /** Counted under its own name each time the tray is asked for its next page. */
    static final String ROUTE = "Stories tray paging";
    static final String NEXT_PAGE = "next page";
    static final String LAST_PAGE = "no next page";
    static final String GAVE_UP = "stopped, no kept stories";

    /** How long a filtered page or a scroll gets to settle before the row's end is checked. */
    static final long SETTLE_MS = 600;

    /** Pages in a row that may add no kept story before paging stops. */
    static final int MAX_EMPTY_PAGES = 10;

    /** The tray's scroll listener, with the methods the patch adds to it. */
    public interface Pager {
        /** Asks the tray's pager for its next page. False when the pager has no next page. */
        boolean hushLoadNextPage();

        /** The row's last tile in view, as Facebook's own check reads it, or -1. */
        int hushLastTile(View recycler);

        /** How many buckets the tray holds after the filters, or -1. */
        int hushBucketCount();
    }

    private static WeakReference<Pager> pager = new WeakReference<>(null);
    private static WeakReference<View> tray = new WeakReference<>(null);

    /** A filtered page is waiting for a tray to page from. */
    private static volatile boolean pending;

    /** The filters have left buckets out this session, so a scroll to the row's end pages too. */
    private static volatile boolean filtered;

    /** The bucket count when the tray was last asked for a page, and the asks since it last grew. */
    private static int askedAt = -1;
    private static int emptyPages;

    @Nullable private static Handler main;
    private static final Runnable CHECK = StoriesTrayPaging::check;

    private StoriesTrayPaging() {
    }

    /**
     * Injection point, first thing in the onScrolled of Home's tray's scroll listener, which
     * RecyclerView also calls after a layout that changes which tiles show. Remembers the latest
     * tray. Never throws.
     *
     * @param listener the scroll listener, made a {@link Pager} by the patch.
     * @param recycler the tray's RecyclerView.
     */
    public static void watch(@Nullable Object listener, @Nullable View recycler) {
        try {
            if (!(listener instanceof Pager) || recycler == null) return;
            if (pager.get() != listener) {
                pager = new WeakReference<>((Pager) listener);
                askedAt = -1;
                emptyPages = 0;
            }
            if (tray.get() != recycler) tray = new WeakReference<>(recycler);
            if (pending || filtered) schedule();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging watch", failure);
        }
    }

    /** The tray filter left buckets out of a page: check the row's end once it settles. Never throws. */
    static void droppedBuckets() {
        try {
            pending = true;
            filtered = true;
            schedule();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging", failure);
        }
    }

    private static void schedule() {
        Handler handler = main;
        if (handler == null) main = handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(CHECK);
        handler.postDelayed(CHECK, SETTLE_MS);
    }

    /** Asks for the next page when the watched tray shows the end of its kept buckets. */
    static void check() {
        try {
            View recycler = tray.get();
            Pager listener = pager.get();
            // No tray on screen yet: the first filtered page comes before Home lays its tray out,
            // and watch() schedules the check again when it does.
            if (recycler == null || listener == null || !recycler.isAttachedToWindow()) return;
            pending = false;
            int buckets = listener.hushBucketCount();
            int last = listener.hushLastTile(recycler);
            if (!showsBucketsEnd(last, buckets)) return;

            if (askedAt >= 0 && buckets <= askedAt) emptyPages++;
            else emptyPages = 0;
            askedAt = buckets;
            if (emptyPages >= MAX_EMPTY_PAGES) {
                if (emptyPages == MAX_EMPTY_PAGES) {
                    FeedFilterCounters.sawKind(ROUTE, GAVE_UP);
                    Logger.printDebug(() -> "Stories tray: " + MAX_EMPTY_PAGES + " pages added no kept stories, so it stopped paging");
                }
                return;
            }
            boolean asked = listener.hushLoadNextPage();
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, asked ? NEXT_PAGE : LAST_PAGE);
            Logger.printDebug(() -> "Stories tray: " + buckets + " kept, tile " + last + " in view, "
                    + (asked ? "asked for its next page" : "no next page"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging", failure);
            Logger.printException(() -> "Stories tray: could not ask for the next page", failure);
        }
    }

    /**
     * Whether the row's last tile in view is the tray's last kept bucket or a tile after it, the
     * placeholders. Facebook's check reads a tile's position as its bucket's index, so this pages
     * one bucket early at most, as Facebook's own check does.
     */
    static boolean showsBucketsEnd(int lastTile, int buckets) {
        return lastTile >= 0 && buckets >= 0 && lastTile >= buckets - 1;
    }

    /** Forgets the tray and any waiting page, for tests. */
    static void reset() {
        pager = new WeakReference<>(null);
        tray = new WeakReference<>(null);
        pending = false;
        filtered = false;
        askedAt = -1;
        emptyPages = 0;
        if (main != null) main.removeCallbacks(CHECK);
    }
}

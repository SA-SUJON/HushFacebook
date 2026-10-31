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
 * <p>Home's tray asks for its next page from its scroll listener, once a drag settles with the end
 * of the row in view. Until that page comes, the row ends in three gray placeholder tiles. With
 * Pages' or watched stories filtered out, a first page of 17 buckets can come down to one, the
 * placeholders sit on screen, and a row that short can't be dragged, so the next page never loads.
 *
 * <p>The patch gives that listener a {@link Pager} method that asks the tray's pager for the next
 * page when it has one, the same call the listener makes after a drag, and hands the listener and
 * its RecyclerView to {@link #watch} first thing in its onScrolled. Each time the filter leaves
 * buckets out, {@link #droppedBuckets} waits for the row to settle and asks for the next page while
 * the end of the row is on screen. Each filtered page repeats it, so the tray keeps paging until
 * the kept stories fill the row or there are no more pages. Nothing changes while the filters keep
 * every bucket.
 */
public final class StoriesTrayPaging {
    /** Counted under its own name each time the tray is asked for its next page. */
    static final String ROUTE = "Stories tray paging";
    static final String NEXT_PAGE = "next page";

    /** How long a filtered page gets to lay out before the row's end is checked. */
    static final long SETTLE_MS = 600;

    /** The tray's scroll listener, with the method the patch adds to it. */
    public interface Pager {
        /** Asks the tray's pager for its next page when it has one. */
        void hushLoadNextPage();
    }

    private static WeakReference<Pager> pager = new WeakReference<>(null);
    private static WeakReference<View> tray = new WeakReference<>(null);

    /** A filtered page is waiting for a tray to page from. */
    private static volatile boolean pending;

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
            if (pager.get() != listener) pager = new WeakReference<>((Pager) listener);
            if (tray.get() != recycler) tray = new WeakReference<>(recycler);
            if (pending) schedule();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging watch", failure);
        }
    }

    /** The tray filter left buckets out of a page: check the row's end once it settles. Never throws. */
    static void droppedBuckets() {
        try {
            pending = true;
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

    /** Asks for the next page when the watched tray shows the end of its row. */
    static void check() {
        try {
            View recycler = tray.get();
            Pager listener = pager.get();
            // No tray on screen yet: the first filtered page comes before Home lays its tray out,
            // and watch() schedules the check again when it does.
            if (recycler == null || listener == null || !recycler.isAttachedToWindow()) return;
            pending = false;
            if (!showsRowEnd(recycler)) return;
            listener.hushLoadNextPage();
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, NEXT_PAGE);
            Logger.printDebug(() -> "Stories tray: the filters left the row short, so it asked for its next page");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SUGGESTED_STORIES, "tray paging", failure);
            Logger.printException(() -> "Stories tray: could not ask for the next page", failure);
        }
    }

    /** Whether the row can't scroll any further toward its end, which is left in a right-to-left layout. */
    static boolean showsRowEnd(View recycler) {
        int forward = recycler.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
        return !recycler.canScrollHorizontally(forward);
    }

    /** Forgets the tray and any waiting page, for tests. */
    static void reset() {
        pager = new WeakReference<>(null);
        tray = new WeakReference<>(null);
        pending = false;
        if (main != null) main.removeCallbacks(CHECK);
    }
}

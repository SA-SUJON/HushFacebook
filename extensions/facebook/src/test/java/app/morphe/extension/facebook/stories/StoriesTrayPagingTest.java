/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Issue #317: a filtered tray whose last kept bucket or placeholders are in view asks for its next
 * page once it settles, even when the placeholders leave the row room to scroll.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoriesTrayPagingTest {
    /** Stands in for the tray's scroll listener once the patch has made it a Pager. */
    static final class Listener implements StoriesTrayPaging.Pager {
        int asked;
        boolean hasNext = true;
        int lastTile;
        int buckets;
        View readFrom;

        @Override public boolean hushLoadNextPage() {
            if (!hasNext) return false;
            asked++;
            return true;
        }

        @Override public int hushLastTile(View recycler) {
            readFrom = recycler;
            return lastTile;
        }

        @Override public int hushBucketCount() {
            return buckets;
        }
    }

    private View row;
    private final Listener listener = new Listener();

    @Before public void setUp() {
        StoriesTrayPaging.reset();
        FeedFilterCounters.clear();
        HookStatus.clear();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        row = new View(activity);
        activity.setContentView(row);
        // The one kept bucket at 1 after Create story, and the three placeholders after it in view.
        listener.buckets = 1;
        listener.lastTile = 4;
    }

    @After public void tearDown() {
        StoriesTrayPaging.reset();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StoriesTrayPaging.SETTLE_MS + 50));
    }

    private static String line() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(StoriesTrayPaging.ROUTE + ":")) return line;
        }
        return "";
    }

    @Test public void aFilteredRowShowingItsPlaceholdersAsksForItsNextPageOnceItSettles() {
        StoriesTrayPaging.watch(listener, row);
        StoriesTrayPaging.droppedBuckets();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StoriesTrayPaging.SETTLE_MS / 2));
        assertEquals("not before the row settles", 0, listener.asked);
        settle();
        assertEquals(1, listener.asked);
        assertSame("the last tile is read from the watched row", row, listener.readFrom);
        assertTrue(line(), line().contains("Kinds: " + StoriesTrayPaging.NEXT_PAGE + " 1"));
        settle();
        assertEquals("once per filtered page", 1, listener.asked);
    }

    @Test public void aFilteredPageBeforeTheTrayLaysOutWaitsForIt() {
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
        StoriesTrayPaging.watch(listener, row);
        settle();
        assertEquals(1, listener.asked);
    }

    @Test public void aRowOfKeptStoriesPastTheScreenWaitsForAScrollToItsEnd() {
        listener.buckets = 12;
        listener.lastTile = 4;
        StoriesTrayPaging.watch(listener, row);
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
        assertEquals("", line());
        listener.lastTile = 11;
        StoriesTrayPaging.watch(listener, row);
        settle();
        assertEquals("a scroll to the last bucket pages once the filters have left one out", 1, listener.asked);
    }

    @Test public void scrollingAnUnfilteredTrayNeverAsks() {
        StoriesTrayPaging.watch(listener, row);
        settle();
        assertEquals(0, listener.asked);
        assertEquals("", line());
    }

    @Test public void aTrayWithNoNextPageIsCountedAndLeftAlone() {
        listener.hasNext = false;
        StoriesTrayPaging.watch(listener, row);
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
        assertTrue(line(), line().contains("Kinds: " + StoriesTrayPaging.LAST_PAGE + " 1"));
    }

    @Test public void pagesThatAddNoKeptStoryStopAfterTheLimit() {
        StoriesTrayPaging.watch(listener, row);
        for (int page = 0; page < StoriesTrayPaging.MAX_EMPTY_PAGES + 3; page++) {
            StoriesTrayPaging.droppedBuckets();
            settle();
        }
        assertEquals(StoriesTrayPaging.MAX_EMPTY_PAGES, listener.asked);
        assertTrue(line(), line().contains(StoriesTrayPaging.GAVE_UP + " 1"));

        listener.buckets = 2;
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals("a page that adds a kept story starts it again", StoriesTrayPaging.MAX_EMPTY_PAGES + 1, listener.asked);
    }

    @Test public void aNewTrayStartsItsOwnCount() {
        StoriesTrayPaging.watch(listener, row);
        for (int page = 0; page < StoriesTrayPaging.MAX_EMPTY_PAGES; page++) {
            StoriesTrayPaging.droppedBuckets();
            settle();
        }
        Listener fresh = new Listener();
        fresh.buckets = 1;
        fresh.lastTile = 4;
        StoriesTrayPaging.watch(fresh, row);
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(1, fresh.asked);
    }

    @Test public void onlyAPatchedListenerIsWatched() {
        StoriesTrayPaging.watch(new Object(), row);
        StoriesTrayPaging.watch(listener, null);
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
    }

    @Test public void aDetachedRowIsLeftAlone() {
        StoriesTrayPaging.watch(listener, new View(row.getContext()));
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
    }

    @Test public void theBucketsEndIsTheLastBucketOrATileAfterIt() {
        assertTrue("a placeholder", StoriesTrayPaging.showsBucketsEnd(4, 1));
        assertTrue("the last bucket", StoriesTrayPaging.showsBucketsEnd(1, 1));
        assertTrue("one before the last, as Facebook's check reads it", StoriesTrayPaging.showsBucketsEnd(10, 11));
        assertFalse(StoriesTrayPaging.showsBucketsEnd(4, 12));
        assertFalse("nothing read", StoriesTrayPaging.showsBucketsEnd(-1, 1));
        assertFalse("nothing read", StoriesTrayPaging.showsBucketsEnd(4, -1));
    }
}

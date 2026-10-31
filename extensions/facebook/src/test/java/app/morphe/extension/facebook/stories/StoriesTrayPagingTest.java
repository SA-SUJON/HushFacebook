/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
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

/** Issue #317: a filtered tray whose row's end is on screen asks for its next page once it settles. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoriesTrayPagingTest {
    /** Stands in for the tray's RecyclerView: scrolls toward its end only while {@link #more} is set. */
    static final class Row extends View {
        boolean more;
        int askedDirection;

        Row(Context context) {
            super(context);
        }

        @Override public boolean canScrollHorizontally(int direction) {
            askedDirection = direction;
            return more;
        }
    }

    /** Stands in for the tray's scroll listener once the patch has made it a Pager. */
    static final class Listener implements StoriesTrayPaging.Pager {
        int asked;

        @Override public void hushLoadNextPage() {
            asked++;
        }
    }

    private Row row;
    private final Listener listener = new Listener();

    @Before public void setUp() {
        StoriesTrayPaging.reset();
        FeedFilterCounters.clear();
        HookStatus.clear();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        row = new Row(activity);
        activity.setContentView(row);
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

    @Test public void aShortFilteredRowAsksForItsNextPageOnceItSettles() {
        StoriesTrayPaging.watch(listener, row);
        StoriesTrayPaging.droppedBuckets();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StoriesTrayPaging.SETTLE_MS / 2));
        assertEquals("not before the row settles", 0, listener.asked);
        settle();
        assertEquals(1, listener.asked);
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

    @Test public void aRowThatStillScrollsPagesTheWayFacebookDoes() {
        row.more = true;
        StoriesTrayPaging.watch(listener, row);
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
        assertEquals("", line());
        row.more = false;
        StoriesTrayPaging.watch(listener, row);
        settle();
        assertEquals("the page was already checked", 0, listener.asked);
    }

    @Test public void scrollingAloneNeverAsks() {
        StoriesTrayPaging.watch(listener, row);
        settle();
        assertEquals(0, listener.asked);
    }

    @Test public void onlyAPatchedListenerIsWatched() {
        StoriesTrayPaging.watch(new Object(), row);
        StoriesTrayPaging.watch(listener, null);
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
    }

    @Test public void aDetachedRowIsLeftAlone() {
        StoriesTrayPaging.watch(listener, new Row(row.getContext()));
        StoriesTrayPaging.droppedBuckets();
        settle();
        assertEquals(0, listener.asked);
    }

    @Test public void theRowsEndIsLeftInARightToLeftLayout() {
        assertTrue(StoriesTrayPaging.showsRowEnd(row));
        assertEquals(1, row.askedDirection);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        assertTrue(StoriesTrayPaging.showsRowEnd(row));
        assertEquals(-1, row.askedDirection);
        row.more = true;
        assertFalse(StoriesTrayPaging.showsRowEnd(row));
    }
}

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** Facebook's own dark mode answer, as the patched controller hands it over on each return. */
public class DarkModeTest {
    private final Runnable listener = DarkMode.changed;

    @After
    public void darkModeAsBefore() {
        DarkMode.answer(true);
        DarkMode.changed = listener;
    }

    @Test
    public void theControllerGetsItsOwnAnswerBackAndTheLatestIsKept() {
        assertFalse("hands back what Facebook answered", DarkMode.answer(false));
        assertFalse(DarkMode.on());
        assertTrue(DarkMode.answer(true));
        assertTrue(DarkMode.on());
    }

    /** Material You writes its route three fields again only when the answer changes, not on every ask. */
    @Test
    public void theListenerRunsOnAChangeOnly() {
        AtomicInteger runs = new AtomicInteger();
        DarkMode.changed = runs::incrementAndGet;
        DarkMode.answer(true);
        assertEquals("the same answer again", 0, runs.get());
        DarkMode.answer(false);
        DarkMode.answer(false);
        assertEquals("light mode", 1, runs.get());
        DarkMode.answer(true);
        assertEquals("dark mode again", 2, runs.get());
    }
}

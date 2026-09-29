/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import androidx.annotation.Nullable;

/**
 * Facebook's own answer to whether its dark mode is on, for the whole app.
 *
 * <p>Both themes change Facebook's dark mode only, and a colour can't always say which mode is on.
 * The Video tab stays dark in light mode: its themed context is built from FDS's dark style, and its
 * bottom bar reads the same colour resource as dark mode's, so the same #252728 reaches the themes
 * in both modes. The patch sends each answer of Facebook's dark mode controller (its Dark mode
 * setting, or the system's night mode when that setting follows the system) through
 * {@link #answer}, which keeps the latest. Facebook asks it as each activity applies its theme, again
 * after the setting or the system's night mode changes, and before it builds the Video tab's themed
 * context, so the kept answer follows the setting. Reading it is one volatile read, which suits the
 * colour hooks that run on every layout pass.
 *
 * <p>Until Facebook first answers, {@link #on} says dark, so the themes act as they did before there
 * was an answer to ask.
 */
public final class DarkMode {

    private DarkMode() {}

    private static volatile boolean on = true;

    /** Run when the answer changes. Material You sets it, to write route three's fields again. */
    @Nullable
    static volatile Runnable changed;

    /**
     * Called with each answer Facebook's dark mode controller gives, right before it returns it.
     *
     * @return {@code dark}, for the controller to return
     */
    public static boolean answer(boolean dark) {
        if (dark != on) {
            on = dark;
            Runnable listener = changed;
            if (listener != null) listener.run();
        }
        return dark;
    }

    /** Whether Facebook's dark mode is on, as it last answered. */
    public static boolean on() {
        return on;
    }
}

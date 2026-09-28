/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/AmoledTheme.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.theme;

import android.graphics.Color;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "[General] AMOLED black theme" patch. It holds the rule for route one and route
 * four, and the one for the status bar. The four routes are described with the patch, in
 * patches/src/main/kotlin/app/morphe/patches/facebook/layout/theme/AmoledThemePatch.kt.
 *
 * <p>The decision needs the token, because a colour alone cannot show the difference between a card
 * and a dark divider. The names of the tokens carry that difference, and R8 cannot rename an enum
 * constant.
 *
 * <p>The decision also needs the colour, because the same tokens serve light mode, where a card is
 * white. Thus the patch needs no test for dark mode. The status bar is the exception, see
 * {@link #statusBar}.
 *
 * <p>{@link #apply} runs for each colour on each layout pass. Thus it makes no object and writes no
 * log. The one thing it adds is a count in Hook status, a hash lookup and an increment once the
 * first call has made the family's entry, which is what shows a report that the theme ran at all.
 */
public final class AmoledTheme {

    private AmoledTheme() {}

    /**
     * The largest value that a channel can have and still count as a background. Measured on a
     * device: a card is {@code #252728}, but a divider is {@code #3A3B3C}.
     */
    private static final int MAX_CHANNEL = 0x2A;

    /**
     * The largest difference between the channels of a background. A grey has almost none. A dark
     * green or dark brown banner has much more, and it keeps its colour.
     */
    private static final int MAX_SPREAD = 8;

    /**
     * The largest value that a channel of a status bar colour can have and still turn black. The
     * bar is chrome, so it goes further than {@link #MAX_CHANNEL}: the Video tab asks for
     * {@code #333334}, and Facebook's lightest dark chrome grey is {@code #3A3B3C}.
     */
    private static final int MAX_BAR_CHANNEL = 0x40;

    /**
     * The tokens that name a background area. The short names come from Mig and the long names
     * from FDS. One set holds both, because only the Mig surface enum declares the short names.
     */
    private static final Set<String> BACKGROUND_TOKENS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // Mig.
                    "WASH",
                    "SURFACE",
                    "CARD",
                    "ELEVATION",
                    "BANNER",
                    "PRIMARY_UI",
                    // FDS: the page.
                    "WEB_WASH",
                    "FBLITE_WASH",
                    "SURFACE_BACKGROUND",
                    "BACKGROUND_SURFACE",
                    "DEVICE_BACKGROUND",
                    "BACKGROUND_DEEMPHASIZED",
                    // FDS: panels on the page.
                    "CARD_BACKGROUND",
                    "CARD_BACKGROUND_FLAT",
                    "CARD_BACKGROUND_LEGACY_WEB",
                    "BACKGROUND_CARD",
                    "BACKGROUND_ELEVATION",
                    "LIST_CELL_BACKGROUND",
                    "ATTACHMENT_FOOTER_BACKGROUND",
                    "ENTITY_HEADER_BACKGROUND",
                    // FDS: comments.
                    "COMMENT_BACKGROUND",
                    "COMMENT_BACKGROUND_DEEMPHASIZED",
                    // FDS: sheets and popovers.
                    "BOTTOM_SHEET_BACKGROUND_DEEMPHASIZED",
                    "BOTTOM_SHEET_INSET_BACKGROUND",
                    "POPOVER_BACKGROUND",
                    "FADED_POPOVER_BACKGROUND",
                    // FDS: chrome.
                    "NAV_BAR_BACKGROUND",
                    "TAB_BAR_BACKGROUND",
                    "BACKGROUND_BANNER",
                    "BACKGROUND_PRIMARY_UI")));

    /**
     * Route one: a colour that a resolver returns.
     *
     * @param token an enum constant. Only its name is used.
     * @return black if this is a background that is already dark, or {@code color} unchanged.
     */
    public static int apply(int color, Object token) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        if (!isDarkNeutral(color, MAX_CHANNEL)) return color;
        if (!(token instanceof Enum)) return color;

        return BACKGROUND_TOKENS.contains(((Enum<?>) token).name()) ? 0xFF000000 : color;
    }

    /**
     * Route four: a colour that the server sends as text.
     *
     * <p>The patch replaces each call to {@link Color#parseColor} in the app with a call to this
     * method. A server-driven screen, such as Settings, gets its colours as strings like
     * {@code "#FF252728"}. No token comes with a string, so the colour alone decides, as in route
     * two and route three.
     *
     * <p>A text that is not a colour throws the same exception as before, so the callers see no
     * change.
     */
    public static int parseColor(String text) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        int color = Color.parseColor(text);
        return isDarkNeutral(color, MAX_CHANNEL) ? 0xFF000000 : color;
    }

    /**
     * The status bar: the colour Facebook is about to paint it, and whether Facebook's theme is dark.
     *
     * <p>On Android 15 and newer Facebook paints the bar itself, through one method that also
     * remembers the last colour per window and skips a colour it already painted. The patch calls
     * this first thing in that method, so the colour painted and the colour remembered are the same.
     * A tab's bar colour often comes from a token resolver that route one doesn't reach. Back from
     * Recent Apps, the Video tab's {@code #333334} (its CARD_BACKGROUND_DARK token) was painted over
     * the black it had at a fresh launch, issue #22.
     *
     * <p>Light mode asks the same token for the same {@code #333334}, so here the colour can't tell
     * the themes apart, and the patch passes Facebook's own answer for the window.
     *
     * @return black for an opaque dark grey in the dark theme, or {@code color} unchanged.
     */
    public static int statusBar(int color, boolean dark) {
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        return dark && isDarkNeutral(color, MAX_BAR_CHANNEL) ? 0xFF000000 : color;
    }

    /** True for an opaque grey with each channel at or below {@code maxChannel}. */
    private static boolean isDarkNeutral(int color, int maxChannel) {
        if ((color >>> 24) != 0xFF) return false;

        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        int high = Math.max(red, Math.max(green, blue));
        int low = Math.min(red, Math.min(green, blue));
        return high <= maxChannel && high - low <= MAX_SPREAD;
    }
}

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.SettingsStatus;

/**
 * The colours React Native screens, such as Marketplace home, set on their views. They come from
 * the screen's JavaScript as ints on view, text and image props, so none of the four colour routes
 * or the FDS night styles sees them. The patch calls these first thing in React's background,
 * border and image tint setters, and on the colour React's text colour span is built with.
 *
 * <p>No token comes with them, so the colour alone decides, as in route four. AMOLED goes first
 * for a background and leaves text and borders alone, and Material You follows with the dark
 * surfaces and Facebook's blues it knows. Either one runs only when its patch is in the build.
 */
public final class ReactColours {
    private ReactColours() {
    }

    /** A view's background colour. */
    public static int background(int color) {
        if (SettingsStatus.amoledTheme()) color = AmoledTheme.react(color);
        return SettingsStatus.materialYouTheme() ? MaterialYouTheme.react(color) : color;
    }

    /** A text span's colour. */
    public static int text(int color) {
        return SettingsStatus.materialYouTheme() ? MaterialYouTheme.react(color) : color;
    }

    /** A border's or an image tint's colour, or null when the screen set none. */
    @Nullable
    public static Integer colour(@Nullable Integer color) {
        if (color == null || !SettingsStatus.materialYouTheme()) return color;
        int recoloured = MaterialYouTheme.react(color);
        return recoloured == color ? color : Integer.valueOf(recoloured);
    }
}

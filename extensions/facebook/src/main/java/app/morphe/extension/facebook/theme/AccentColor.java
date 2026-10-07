/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Accent color" patch: one of Facebook's blues, on the links, buttons, switches and
 * the selected tab it draws in blue, becomes the accent the Accent color row picks. Facebook blue,
 * the row's first choice, is Facebook as it ships, and so is a paused Facebook.
 *
 * <p>It rides route one of the themes ({@link MaterialYouTheme}): the colours Facebook's FDS and Mig
 * resolvers answer. A colour changes only when it is one of Facebook's blues
 * ({@link MaterialYouTheme#isFacebookBlue}) and, for FDS, comes with one of the {@link #TOKENS}, so a
 * chart, a badge or a colour somebody picked for a post keeps its own. It becomes the accent at the
 * lightness it had, in light mode and in dark, and keeps its alpha, so a tint stays a tint.
 *
 * <p>The accent's own tones are a tonal ramp of the preset's hue ({@link Preset}): CIE L* in steps
 * of ten at as much chroma as sRGB shows there. Text on a surface is held to 4.5:1 once Facebook has
 * said which theme is on ({@link #TEXT_TOKENS}): lighter than {@link #DARK_TEXT_LIGHTNESS} in dark
 * mode, darker than {@link #LIGHT_TEXT_LIGHTNESS} in light mode. Fills keep Facebook's own
 * lightness, so the label on a button has the contrast Facebook gave it.
 *
 * <p>While the Material You theme is in the build it decides every colour this class would, so this
 * steps aside.
 */
public final class AccentColor {

    /** The accents on offer, each a hue in LCh and the chroma it's drawn at when sRGB has room for it. */
    public enum Preset {
        FACEBOOK("facebook", 0, 0),
        TEAL("teal", 200, 45),
        GREEN("green", 150, 50),
        PURPLE("purple", 315, 55),
        PINK("pink", 350, 55),
        ORANGE("orange", 55, 60),
        RED("red", 30, 65),
        INDIGO("indigo", 292, 55),
        AMBER("amber", 80, 60);

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        final double hue;
        final double chroma;

        Preset(String fileValue, double hue, double chroma) {
            this.fileValue = fileValue;
            this.hue = hue;
            this.chroma = chroma;
        }

        /** The choice a settings file names, or null when it names none this build knows. */
        @Nullable
        public static Preset fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (Preset preset : values()) {
                if (preset.fileValue.equals(value)) return preset;
            }
            return null;
        }
    }

    /**
     * FDS colour tokens Facebook draws in its blue: its links, buttons, switches, the active input
     * border and the selected tab. Each is a token of MaterialYouTheme's lists of tokens read from
     * Facebook 580 and 577, which AccentColorTest holds this to. The verified badge, the blue badge,
     * story rings and decorative icons are left in Facebook's blue on purpose.
     */
    static final String TOKENS = "ACCENT;ACCENT_DEEMPHASIZED;BLUE_LINK;CURSOR;DOT_BADGE_BLUE;FBLITE_ACCENT_ON_BACKGROUND;"
            + "HOSTED_VIEW_SELECTED_STATE;NEW_NOTIFICATION_BACKGROUND;PRIMARY_BUTTON_BACKGROUND;"
            + "PRIMARY_BUTTON_PRESSED_BACKGROUND;PRIMARY_DEEMPHASIZED_BUTTON_BACKGROUND;PRIMARY_DEEMPHASIZED_BUTTON_ICON;"
            + "PRIMARY_DEEMPHASIZED_BUTTON_TEXT;PROGRESS_RING_BLUE_BACKGROUND;PROGRESS_RING_BLUE_FOREGROUND;"
            + "REACTION_LIKE;STEPPER_ACTIVE;SWITCH_CHECKED_BACKGROUND_COLOR_ANDROID;SWITCH_CHECKED_BACKGROUND_COLOR_IOS;"
            + "SWITCH_CHECKED_HANDLE_FILL_COLOR_ANDROID;TAB_BAR_ACTIVE_ICON;TEXT_HIGHLIGHT;TEXT_INPUT_ACTIVE_INNER_BORDER;"
            + "TEXT_INPUT_ACTIVE_OUTER_BORDER;TEXT_INPUT_ACTIVE_TEXT;TOGGLE_ACTIVE_BACKGROUND";

    /** The tokens that draw text or a small icon over a surface, which {@link #withContrast} holds to 4.5:1. */
    static final String TEXT_TOKENS = "ACCENT;BLUE_LINK;PRIMARY_DEEMPHASIZED_BUTTON_ICON;PRIMARY_DEEMPHASIZED_BUTTON_TEXT;"
            + "REACTION_LIKE;TEXT_INPUT_ACTIVE_TEXT";

    private static final Set<String> TOKEN_SET = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(TOKENS.split(";"))));
    private static final Set<String> TEXT_TOKEN_SET =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(TEXT_TOKENS.split(";"))));

    /** The surfaces text is held against: light mode's page and white card, and dark mode's lightest card. */
    static final int LIGHT_SURFACE = 0xFFF0F2F5;
    static final int DARK_SURFACE = 0xFF333334;

    /**
     * How dark text over a light surface may be at most, and how light text over a dark one may be at
     * least, in CIE L*: the lightness that gives 4.5:1 against {@link #LIGHT_SURFACE} and
     * {@link #DARK_SURFACE}, with a point of L* to spare for the 8-bit rounding of the colour, which every other surface of the theme beats. AccentColorTest holds both.
     */
    static final double LIGHT_TEXT_LIGHTNESS = 45;
    static final double DARK_TEXT_LIGHTNESS = 65;

    /** The tonal ramp of each preset, built when it's first used. */
    private static final Map<Preset, TonePalette> PALETTES = new EnumMap<>(Preset.class);

    private AccentColor() {}

    /**
     * Route one, for FDS: a colour a resolver returns for {@code token}.
     *
     * @return the accent for one of Facebook's blues on a token in {@link #TOKENS}, otherwise {@code color}
     */
    public static int fds(int color, Object token) {
        HookStatus.invoked(FamilyNames.ACCENT_COLOR);
        if (!Utils.settingsReady() || !(token instanceof Enum)) return color;
        Preset preset = chosen();
        if (preset == null) return color;
        return fds(color, ((Enum<?>) token).name(), preset, DarkMode.hasAnswered(), DarkMode.on());
    }

    /**
     * Route one, for Mig: a colour the Mig dark scheme returns. That scheme only answers for a dark
     * surface, so one of Facebook's opaque blues becomes the accent whatever the token.
     */
    public static int mig(int color, Object token) {
        HookStatus.invoked(FamilyNames.ACCENT_COLOR);
        if (!Utils.settingsReady() || (color >>> 24) != 0xFF) return color;
        Preset preset = chosen();
        if (preset == null) return color;
        return mig(color, preset);
    }

    /** The accent in force, or null for Facebook's own blue: unset, paused, or the Material You theme in charge. */
    @Nullable
    private static Preset chosen() {
        Preset preset = Settings.ACCENT_COLOR.get();
        if (preset == Preset.FACEBOOK || SettingsStatus.materialYouTheme()) return null;
        return preset;
    }

    static int fds(int color, String token, Preset preset, boolean answered, boolean dark) {
        if (preset == Preset.FACEBOOK || !TOKEN_SET.contains(token) || !MaterialYouTheme.isFacebookBlue(color)) return color;
        int themed = palette(preset).sameLightness(TonePalette.ACCENT, color);
        if (answered && TEXT_TOKEN_SET.contains(token)) themed = withContrast(preset, themed, dark);
        return themed;
    }

    static int mig(int color, Preset preset) {
        if (preset == Preset.FACEBOOK || !MaterialYouTheme.isFacebookBlue(color)) return color;
        return palette(preset).sameLightness(TonePalette.ACCENT, color);
    }

    /** The accent {@code color}, with its lightness moved only as far as 4.5:1 against the surface of the theme needs. */
    static int withContrast(Preset preset, int color, boolean dark) {
        double lightness = TonePalette.lstar(color);
        double held = dark ? Math.max(lightness, DARK_TEXT_LIGHTNESS) : Math.min(lightness, LIGHT_TEXT_LIGHTNESS);
        if (held == lightness) return color;
        return palette(preset).atLightness(TonePalette.ACCENT, held, color & 0xFF000000);
    }

    /** The preset's tones, L* 0 to 100 in steps of ten, as a palette whose accent family is the ramp. */
    static synchronized TonePalette palette(Preset preset) {
        TonePalette palette = PALETTES.get(preset);
        if (palette == null) {
            int[] ramp = new int[TonePalette.TONES.length];
            for (int i = 0; i < ramp.length; i++) {
                ramp[i] = TonePalette.gamutColour(TonePalette.TONES[i], preset.chroma, preset.hue);
            }
            palette = new TonePalette(new int[][]{ramp, ramp, ramp}, false);
            PALETTES.put(preset, palette);
        }
        return palette;
    }

    /** WCAG's contrast ratio of two opaque colours. */
    static double contrast(int first, int second) {
        double a = TonePalette.luminance(first) + 0.05;
        double b = TonePalette.luminance(second) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }
}

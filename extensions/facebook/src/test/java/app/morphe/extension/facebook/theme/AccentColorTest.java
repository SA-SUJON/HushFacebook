/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The Accent color rules: a chosen accent replaces Facebook's blues and only them, keeps what
 * lightness and alpha Facebook gave, holds text to 4.5:1, and stock is stock (Facebook blue chosen,
 * a paused Facebook, a start before the settings are ready).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentColorTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's blues, from FDS's dark and light styles. */
    private static final int[] BLUES = {0xFF00488C, 0xFF0064D1, 0xFF0866FF, 0xFF1D85FC, 0xFF2D88FF, 0xFF3E93F8,
            0xFF5AA7FF, 0xFF75B6FF, 0xFFADD5FF};

    private static final AccentColor.Preset[] CHOICES = Arrays.stream(AccentColor.Preset.values())
            .filter(preset -> preset != AccentColor.Preset.FACEBOOK).toArray(AccentColor.Preset[]::new);

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.ACCENT_COLOR.resetToDefault();
    }

    @Test
    public void textOverASurfaceHasFourAndAHalfToOneInBothModesForEveryAccent() {
        for (AccentColor.Preset preset : CHOICES) {
            for (int blue : BLUES) {
                for (String token : AccentColor.TEXT_TOKENS.split(";")) {
                    int dark = AccentColor.fds(blue, token, preset, true, true);
                    int light = AccentColor.fds(blue, token, preset, true, false);
                    assertTrue(preset + " " + token + " in dark mode",
                            AccentColor.contrast(dark, AccentColor.DARK_SURFACE) >= 4.5);
                    assertTrue(preset + " " + token + " in light mode",
                            AccentColor.contrast(light, AccentColor.LIGHT_SURFACE) >= 4.5);
                }
            }
        }
    }

    @Test
    public void aFillKeepsFacebooksLightnessAndAnAlphaStaysATint() {
        for (AccentColor.Preset preset : CHOICES) {
            for (int blue : BLUES) {
                int fill = AccentColor.fds(blue, "PRIMARY_BUTTON_BACKGROUND", preset, true, true);
                assertNotEquals(preset + " left Facebook's blue", blue, fill);
                assertEquals(preset + " lightness of " + Integer.toHexString(blue),
                        TonePalette.lstar(blue), TonePalette.lstar(fill), 1.0);
                int tint = AccentColor.fds((0x33 << 24) | (blue & 0xFFFFFF), "ACCENT_DEEMPHASIZED", preset, true, true);
                assertEquals(0x33, tint >>> 24);
            }
        }
    }

    @Test
    public void onlyFacebooksBluesOnTheAccentTokensChange() {
        AccentColor.Preset preset = AccentColor.Preset.TEAL;
        // Not a blue.
        assertEquals(0xFFE41E3F, AccentColor.fds(0xFFE41E3F, "ACCENT", preset, true, true));
        assertEquals(0xFF333334, AccentColor.fds(0xFF333334, "ACCENT", preset, true, true));
        // A blue on a token the accent doesn't own: a badge, a chart.
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "VERIFIED_BADGE", preset, true, true));
        assertEquals(0xFF1D85FC, AccentColor.fds(0xFF1D85FC, "DATAVIZ_BLUE_PRIMARY", preset, true, true));
        // The same blue on one it owns.
        assertNotEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "ACCENT", preset, true, true));
        // Facebook blue chosen is stock.
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "ACCENT", AccentColor.Preset.FACEBOOK, true, true));
        // Mig answers only opaque blues.
        assertEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, AccentColor.Preset.FACEBOOK));
        assertNotEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, preset));
        assertEquals(0xFF333334, AccentColor.mig(0xFF333334, preset));
    }

    @Test
    public void everyTokenIsOneTheMaterialYouListsReadFromFacebook() {
        Set<String> known = new HashSet<>();
        for (String list : new String[]{MaterialYouTheme.FDS_DARK, MaterialYouTheme.FDS_SHARED}) {
            for (String entry : list.split(";")) known.add(entry.substring(0, entry.indexOf('=')));
        }
        for (String token : AccentColor.TOKENS.split(";")) {
            assertTrue(token + " is in neither of Facebook's token lists", known.contains(token));
        }
        Set<String> all = new HashSet<>(Arrays.asList(AccentColor.TOKENS.split(";")));
        for (String token : AccentColor.TEXT_TOKENS.split(";")) {
            assertTrue(token + " holds text but is not an accent token", all.contains(token));
        }
    }

    @Test
    public void everyPresetsRampSpansLightToDarkAndHasAHue() {
        for (AccentColor.Preset preset : CHOICES) {
            TonePalette palette = AccentColor.palette(preset);
            int dark = palette.atLightness(TonePalette.ACCENT, 20, 0xFF000000);
            int mid = palette.atLightness(TonePalette.ACCENT, 50, 0xFF000000);
            int light = palette.atLightness(TonePalette.ACCENT, 90, 0xFF000000);
            assertTrue(preset + " ramp order", TonePalette.lstar(dark) < TonePalette.lstar(mid)
                    && TonePalette.lstar(mid) < TonePalette.lstar(light));
            int r = (mid >> 16) & 0xFF;
            int g = (mid >> 8) & 0xFF;
            int b = mid & 0xFF;
            assertTrue(preset + " is grey", Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 20);
        }
    }

    @Test
    public void theHooksAreStockWhileUnsetPausedOrBeforeTheSettingsAreReady() {
        Object token = Token.ACCENT;
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, token));
        assertEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, token));

        Settings.ACCENT_COLOR.save(AccentColor.Preset.GREEN);
        assertNotEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, token));
        assertNotEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, token));
        // A colour that isn't a Facebook blue is the same with an accent on.
        assertEquals(0xFF333334, AccentColor.fds(0xFF333334, token));
        // A token that is no enum is left alone.
        assertEquals(0xFF0866FF, AccentColor.fds(0xFF0866FF, "ACCENT"));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("a paused Facebook reads the default", 0xFF0866FF, AccentColor.fds(0xFF0866FF, token));
        assertEquals(0xFF0866FF, AccentColor.mig(0xFF0866FF, token));
    }

    /** Stands in for an FDS token: only the constant name matters. */
    enum Token {
        ACCENT
    }
}

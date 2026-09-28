/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The one colour rule the resolver hooks and the parseColor reroute share. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledThemeTest {
    /** Stands in for Facebook's colour token enums: only the constant names matter. */
    enum Token { CARD_BACKGROUND, WASH, DIVIDER, PRIMARY_TEXT }

    private static final int BLACK = 0xFF000000;

    @Test
    public void aDarkGreyBackgroundTurnsBlack() {
        assertEquals(BLACK, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals(BLACK, AmoledTheme.apply(0xFF101011, Token.WASH));
    }

    /** The mutation controls: a divider, a lighter grey, a tinted banner and text keep theirs. */
    @Test
    public void everythingElseKeepsItsColour() {
        assertEquals("a divider token", 0xFF252728, AmoledTheme.apply(0xFF252728, Token.DIVIDER));
        assertEquals("above the dark threshold", 0xFF3A3B3C, AmoledTheme.apply(0xFF3A3B3C, Token.CARD_BACKGROUND));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, AmoledTheme.apply(0xFF1A2A10, Token.CARD_BACKGROUND));
        assertEquals("light mode's white card", 0xFFFFFFFF, AmoledTheme.apply(0xFFFFFFFF, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", 0xFF252728, AmoledTheme.apply(0xFF252728, "CARD_BACKGROUND"));
    }

    @Test
    public void aServerColourStringIsJudgedByItsValue() {
        assertEquals(BLACK, AmoledTheme.parseColor("#FF252728"));
        assertEquals(0xFF3A3B3C, AmoledTheme.parseColor("#3A3B3C"));
        assertEquals("a translucent scrim stays", 0x80252728, AmoledTheme.parseColor("#80252728"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aStringThatIsNoColourThrowsAsBefore() {
        AmoledTheme.parseColor("not a colour");
    }

    /**
     * Issue #22: back from Recent Apps, the Video tab's bar is painted #333334, the colour its
     * CARD_BACKGROUND_DARK token resolves to, and Facebook's home bar #252728.
     */
    @Test
    public void aDarkThemeStatusBarTurnsBlack() {
        assertEquals("the Video tab's bar", BLACK, AmoledTheme.statusBar(0xFF333334, true));
        assertEquals("the home bar", BLACK, AmoledTheme.statusBar(0xFF252728, true));
        assertEquals("the lightest chrome grey", BLACK, AmoledTheme.statusBar(0xFF3A3B3C, true));
    }

    /** Light mode asks the same token for the same #333334, and its bars keep Facebook's colours. */
    @Test
    public void aLightThemeStatusBarKeepsItsColour() {
        assertEquals("the Video tab's bar", 0xFF333334, AmoledTheme.statusBar(0xFF333334, false));
        assertEquals("a white bar", 0xFFFFFFFF, AmoledTheme.statusBar(0xFFFFFFFF, false));
    }

    /** The mutation controls for the dark theme: only an opaque dark grey turns black. */
    @Test
    public void aDarkThemeStatusBarThatIsNoDarkGreyKeepsItsColour() {
        assertEquals("edge to edge", 0x00000000, AmoledTheme.statusBar(0x00000000, true));
        assertEquals("a translucent scrim", 0x80333334, AmoledTheme.statusBar(0x80333334, true));
        assertEquals("above the bar threshold", 0xFF4B4C4F, AmoledTheme.statusBar(0xFF4B4C4F, true));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, AmoledTheme.statusBar(0xFF1A2A10, true));
        assertEquals("a white bar", 0xFFFFFFFF, AmoledTheme.statusBar(0xFFFFFFFF, true));
    }

    /** The bar's higher threshold stays the bar's: a resolver's #333334 card keeps its colour. */
    @Test
    public void theBarThresholdDoesNotReachTheOtherRoutes() {
        assertEquals(0xFF333334, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals(0xFF333334, AmoledTheme.parseColor("#FF333334"));
    }
}

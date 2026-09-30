/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Document
import org.w3c.dom.Element

/*
 * Route two for the FDS styles.
 *
 * A view Facebook inflates from its own compiled layouts, like the Find friends button on an empty
 * feed, takes its colours from theme attributes the FDS dark style sets, and that style points them
 * at colour resources in the default configuration. Facebook keeps its dark palette there, since the
 * Video tab uses the dark style in light mode too, so no colour call passes a hook and none of those
 * colours is a night one route two rewrites.
 *
 * So the resource half writes a night copy of the dark style, and of each style under it that sets a
 * token of its own, with each item for a token and colour FDS_DARK or FDS_SHARED lists pointing at a
 * palette colour instead. Night resources are only read while Facebook's dark mode is on, so light
 * mode, and the Video tab in light mode with it, keeps the default styles as they are. A colour the
 * tables don't list for its token (the logo's blue, a map's), a translucent one, which no system
 * colour resource can carry, and one no palette tone is near all stay as Facebook has them.
 */

/** MaterialYouTheme.FDS_DARK, which the parity test holds to the extension's. */
internal const val FDS_DARK_TOKENS = "ACCENT=1D85FC;ACCENT_DEEMPHASIZED=331D85FC,192D88FF;ACTIVE_DOT=E2E5E9;" +
    "ATTACHMENT_FOOTER_BACKGROUND=F2F4F7;BLUE_LINK=5AA7FF,3E93F8;" +
    "BOTTOM_SHEET_BACKGROUND_DEEMPHASIZED=252728;BOTTOM_SHEET_HANDLE=6F7276;" +
    "BOTTOM_SHEET_INSET_BACKGROUND=333334;CARD_BACKGROUND=333334;CARD_BACKGROUND_FLAT=333334;" +
    "CARD_BACKGROUND_LEGACY_WEB=252728;CARD_BORDER=333334;CLIENT_BOTTOM_SHEET_PRESSED=3B3C3E;" +
    "COMMENT_BACKGROUND=333334;COMMENT_THREADING_LINES=46484B;DISABLED_BUTTON_BACKGROUND=333334;" +
    "DISABLED_ICON=6F7276;DISABLED_TEXT=6F7276,505255;DIVIDER=65686C,505255;DOT_BADGE_BLUE=1D85FC;" +
    "ENTITY_HEADER_BACKGROUND=252728;FBLITE_ACCENT_ON_BACKGROUND=1D85FC;FBLITE_STRONG_SECONDARY=D0D3D7;" +
    "FBLITE_TEXT_INPUT_INACTIVE_INNER_BORDER=6F7276;FBLITE_WASH=080809;FEED_GAP_VERTICAL=101011;" +
    "HOSTED_VIEW_SELECTED_STATE=191D85FC;INACTIVE_DOT=84878B;LIST_CELL_BACKGROUND=252728;" +
    "META_ICON=B0B3B8;META_TEXT=B0B3B8;NAV_BAR_BACKGROUND=252728;NAV_BAR_ICON=E8EAEE;NAV_BAR_TEXT=E8EAEE;" +
    "NEW_NOTIFICATION_BACKGROUND=192D88FF;PLACEHOLDER_ICON=B0B3B8,84878B;PLACEHOLDER_TEXT=B0B3B8,84878B;" +
    "POPOVER_BACKGROUND=3B3C3E,3E4042;PRIMARY_BUTTON_TEXT=252728;" +
    "PRIMARY_DEEMPHASIZED_BUTTON_BACKGROUND=331D85FC,262D88FF;PRIMARY_DEEMPHASIZED_BUTTON_ICON=75B6FF;" +
    "PRIMARY_DEEMPHASIZED_BUTTON_TEXT=75B6FF,0866FF;PRIMARY_ICON=F2F4F7;PRIMARY_TEXT=F2F4F7;" +
    "PROGRESS_RING_DISABLED_FOREGROUND=6F7276;REACTION_LIKE=3E93F8;SECONDARY_BUTTON_BACKGROUND=333334;" +
    "SECONDARY_BUTTON_BACKGROUND_FLOATING=46484B;SECONDARY_BUTTON_BACKGROUND_OPAQUE=46484B;" +
    "SECONDARY_BUTTON_ICON=F2F4F7;SECONDARY_BUTTON_TEXT=F2F4F7;SECONDARY_ICON=B0B3B8,A1A4A9;" +
    "SECONDARY_TEXT=B0B3B8,A1A4A9;SURFACE_BACKGROUND=252728;" +
    "SWITCH_CHECKED_BACKGROUND_COLOR_ANDROID=ADD5FF;SWITCH_CHECKED_HANDLE_FILL_COLOR_ANDROID=0866FF;" +
    "SWITCH_DISABLED_HANDLE_FILL_COLOR=6F7276;SWITCH_UNCHECKED_BACKGROUND_COLOR=6F7276;" +
    "TAB_BAR_ACTIVE_ICON=F2F4F7;TAB_BAR_BACKGROUND=252728;TAB_BAR_INACTIVE_ICON=F2F4F7;" +
    "TEXT_HIGHLIGHT=721D85FC;TEXT_INPUT_ACTIVE_INNER_BORDER=1D85FC;" +
    "TEXT_INPUT_ACTIVE_OUTER_BORDER=331D85FC;TEXT_INPUT_ACTIVE_TEXT=3E93F8;" +
    "TEXT_INPUT_BAR_BACKGROUND=333334;TEXT_INPUT_BAR_BACKGROUND_ON_DEEMPHASIZED=333334;" +
    "TEXT_INPUT_INACTIVE_INNER_BORDER=5C5E62;TOGGLE_ACTIVE_BACKGROUND=1D85FC,0866FF;TOOLTIP_TEXT=080809;" +
    "UFI_TRAY_ICON_BUTTON_BACKGROUND=46484B;VOICE_SWITCHER_BACKGROUND=46484B;WASH=101011,1C1C1D;" +
    "WEB_WASH=1C1C1D"

/** MaterialYouTheme.FDS_SHARED, held to the extension's the same way. */
internal const val FDS_SHARED_TOKENS = "ACCENT=0866FF;BLUE_BADGE=0866FF;CURSOR=0866FF;DECORATIVE_ICON_BLUE=0064D1;" +
    "DISABLED_BUTTON_BACKGROUND_GROWTH=5AA7FF;NOTIFICATION_CIRCLE_BLUE=1D85FC;" +
    "PRIMARY_BUTTON_BACKGROUND=0866FF;PRIMARY_BUTTON_PRESSED_BACKGROUND=3E93F8;" +
    "PROGRESS_RING_BLUE_BACKGROUND=330866FF;PROGRESS_RING_BLUE_FOREGROUND=0866FF;STEPPER_ACTIVE=0866FF;" +
    "STORY_UNSEEN=0866FF;SWITCH_CHECKED_BACKGROUND_COLOR_IOS=0866FF;VERIFIED_BADGE=0866FF"

/**
 * Each token either table lists, with every colour listed for it: six hex digits an opaque colour,
 * eight a colour with its alpha first, as MaterialYouTheme.parseTokens reads them.
 */
internal fun listedTokenColours(): Map<String, Set<Int>> {
    val listed = mutableMapOf<String, MutableSet<Int>>()
    for (table in listOf(FDS_DARK_TOKENS, FDS_SHARED_TOKENS)) {
        for (entry in table.split(";")) {
            val (token, colours) = entry.split("=")
            for (hex in colours.split(",")) {
                val rgb = hex.toLong(16).toInt()
                listed.getOrPut(token) { mutableSetOf() } += if (hex.length == 8) rgb else rgb or -0x1000000
            }
        }
    }
    return listed
}

/** A token set by more of the FDS styles' items than this is one of Facebook's full themes. */
private const val FULL_THEME_ITEMS = 300

/**
 * Each FDS token by the item name the resource decoder gives its theme attribute, such as
 * `attr_0x7f0405bd` for PRIMARY_BUTTON_BACKGROUND on 580. The bytecode half reads them off the token
 * enum for the resource half, since Facebook strips the attributes' names.
 */
internal var tokenAttributeNames: Map<String, String> = emptyMap()

/** Reads [tokenAttributeNames] out of the token enum FDSColors resolves. */
internal val fdsTokenAttributesPatch = bytecodePatch {
    execute {
        val source = classDefBy(FDS_COLORS).methods.singleOrNull { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/Integer;" &&
                method.parameterTypes.size == 3 && method.parameterTypes[0].toString() == "Landroid/content/Context;"
        } ?: error("FDSColors has no single Integer colour source taking a Context, a token and a palette")
        val tokenType = source.parameterTypes[1].toString()
        val initializer = classDefBy(tokenType).methods.single { it.name == "<clinit>" }
        tokenAttributeNames = tokenAttributes(initializer, tokenType).entries
            .associate { (token, attribute) -> "attr_0x%08x".format(attribute) to token }
        check(tokenAttributeNames.size > FULL_THEME_ITEMS) {
            "The FDS token enum has too few constants with a theme attribute, so no style item can be matched"
        }
    }
}

/**
 * Each constant of the token enum, by name, with the theme attribute it passes. Follows constants
 * and moves through registers to each constructor call, which takes the name, the ordinal, the theme
 * attribute, a fallback colour and a colour resource.
 */
internal fun tokenAttributes(initializer: Method, tokenType: String): Map<String, Int> {
    val registers = mutableMapOf<Int, Any?>()
    val tokens = mutableMapOf<String, Int>()
    for (instruction in initializer.implementation!!.instructions) {
        val opcode = instruction.opcode
        when {
            instruction is NarrowLiteralInstruction && instruction is OneRegisterInstruction &&
                opcode in setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16) ->
                registers[instruction.registerA] = instruction.narrowLiteral
            opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO ->
                registers[(instruction as OneRegisterInstruction).registerA] =
                    ((instruction as ReferenceInstruction).reference as StringReference).string
            opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT,
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) ->
                registers[(instruction as TwoRegisterInstruction).registerA] = registers[instruction.registerB]
            (opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                ((instruction as ReferenceInstruction).reference as? MethodReference)
                    ?.let { it.name == "<init>" && it.definingClass == tokenType } == true -> {
                val arguments = when (instruction) {
                    is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
                    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD,
                        instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
                    else -> error("unexpected call form $opcode")
                }
                val name = registers[arguments[1]] as? String ?: continue
                (registers[arguments[3]] as? Int)?.let { tokens[name] = it }
            }
            instruction is OneRegisterInstruction && opcode != Opcode.SPUT_OBJECT -> registers.remove(instruction.registerA)
        }
    }
    return tokens
}

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** A style element's parent by name, or null when it names none. */
private fun Element.parentName(): String? = getAttribute("parent").substringAfter('/', "").ifEmpty { null }

/**
 * The FDS dark style and every style under it, the dark one first, out of one of the decoder's
 * default style files, or nothing when [styles] doesn't hold Facebook's light and dark FDS styles.
 * The light one names no parent, the dark one names the light one, and each sets more than
 * [FULL_THEME_ITEMS] of the tokens [tokens] names, as MaterialYouTokenFixtureTest finds them.
 */
internal fun darkFdsStyles(styles: Document, tokens: Map<String, String>): List<Element> {
    val all = styles.documentElement.childElements().filter { it.tagName.startsWith("style") }
    fun fullTheme(style: Element) = style.childElements().count { it.getAttribute("name") in tokens } > FULL_THEME_ITEMS
    val light = all.singleOrNull { it.parentName() == null && fullTheme(it) } ?: return emptyList()
    val dark = all.singleOrNull { it.parentName() == light.getAttribute("name") && fullTheme(it) } ?: return emptyList()

    val family = mutableListOf(dark)
    val names = mutableSetOf(dark.getAttribute("name"))
    do {
        val next = all.filter { it.getAttribute("name") !in names && it.parentName() in names }
        family += next
        next.forEach { names += it.getAttribute("name") }
    } while (next.isNotEmpty())
    return family
}

/** The palette colour resource a night style item points at for [tone]. */
internal fun paletteColourName(tone: NightTone): String =
    "hushfacebook_you_" + (if (tone.accent) "accent_" else "neutral_") + tone.tone

/**
 * An item's colour as the default configuration has it, following `@color/` references through
 * [colours], or null for anything else: a theme attribute, a colour Facebook gives a night value of
 * its own (route two's, or Facebook's own choice at night), a loop.
 */
private fun defaultColour(value: String, colours: Map<String, String>, nightColours: Set<String>, depth: Int = 0): Int? {
    val text = value.trim()
    if (text.startsWith("#")) return argb(text)
    if (!text.startsWith("@color/") || depth > 4) return null
    val name = text.removePrefix("@color/")
    if (name in nightColours) return null
    return defaultColour(colours[name] ?: return null, colours, nightColours, depth + 1)
}

/**
 * Writes into [night] a copy of each style in [family] with an item to change, its items for a
 * listed token and colour pointing at that colour's palette tone, and adds each tone once to
 * [nightColours] (the fixed palette, for Android 11) and [nightV31Colours] (the wallpaper's).
 * [colours] are the default colours by name and [nightColourNames] the ones with a night value.
 * A style [night] already has is left to Facebook. Answers how many items it pointed elsewhere.
 */
internal fun writeNightStyles(
    family: List<Element>,
    colours: Map<String, String>,
    nightColourNames: Set<String>,
    tokens: Map<String, String>,
    night: Document,
    nightColours: Document,
    nightV31Colours: Document,
): Int {
    val listed = listedTokenColours()
    val present = night.documentElement.childElements().map { it.getAttribute("name") }.toSet()
    val written = (nightColours.documentElement.childElements() + nightV31Colours.documentElement.childElements())
        .map { it.getAttribute("name") }.toMutableSet()

    var changed = 0
    for (style in family) {
        if (style.getAttribute("name") in present) continue
        val tones = style.childElements().mapIndexedNotNull { index, item ->
            val token = tokens[item.getAttribute("name")] ?: return@mapIndexedNotNull null
            val colour = defaultColour(item.textContent, colours, nightColourNames) ?: return@mapIndexedNotNull null
            if (colour !in listed[token].orEmpty()) return@mapIndexedNotNull null
            nightTone("#%08x".format(colour))?.let { index to it }
        }.toMap()
        if (tones.isEmpty()) continue

        val copy = night.importNode(style, true) as Element
        val items = copy.childElements()
        for ((index, tone) in tones) {
            val name = paletteColourName(tone)
            items[index].textContent = "@color/$name"
            if (written.add(name)) {
                nightColours.documentElement.appendChild(nightColours.colour(name, tone.fallback))
                nightV31Colours.documentElement.appendChild(nightV31Colours.colour(name, tone.systemColor))
            }
        }
        night.documentElement.appendChild(copy)
        changed += tones.size
    }
    return changed
}

private fun Document.colour(name: String, value: String): Element =
    createElement("color").also {
        it.setAttribute("name", name)
        it.textContent = value
    }

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Route two for the FDS styles (MaterialYouStyles.kt), on made-up style and colour files shaped like
 * the resource decoder's: which styles it copies for the night, which items it points at the
 * palette, and what it leaves as Facebook has it.
 */
class MaterialYouStylesTest {
    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())

    private fun Document.text(): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it))
    }.toString()

    private fun Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.items(): Map<String, String> {
        val nodes = getElementsByTagName("item")
        return (0 until nodes.length).associate { index ->
            val item = nodes.item(index) as Element
            item.getAttribute("name") to item.textContent
        }
    }

    /** The real tokens these cases use, plus enough made-up ones for a style to count as a full theme. */
    private val tokens: Map<String, String> =
        listOf("PRIMARY_BUTTON_BACKGROUND", "FB_LOGO", "CARD_BACKGROUND", "ACCENT_DEEMPHASIZED", "PRIMARY_TEXT",
            "ACCENT", "SECONDARY_TEXT", "BLUE_LINK")
            .plus((0 until 310).map { "FILLER_$it" })
            .withIndex().associate { (index, token) -> "attr_0x%08x".format(0x7f040000 + index) to token }

    private fun attribute(token: String) = tokens.entries.single { it.value == token }.key

    private fun fillers() = (0 until 310).joinToString("") { "<item name=\"${attribute("FILLER_$it")}\">#ff123456</item>" }

    private val colours = mapOf(
        "blue" to "#ff0866ff",
        "blue_alias" to "@color/blue",
        "card" to "#ff333334",
        "tint" to "#331d85fc",
        "text" to "#fff2f4f7",
        "night_text" to "#ffb0b3b8",
        "accent" to "#ff1d85fc",
        "link" to "#ff3e93f8",
        "loop" to "@color/loop",
    )

    /** night_text has a night value of its own, which route two or Facebook already decides. */
    private val nightColourNames = setOf("night_text")

    private fun defaults() = xml(
        "<resources>" +
            "<style.2 name=\"light\">" + fillers() +
            "<item name=\"${attribute("PRIMARY_BUTTON_BACKGROUND")}\">@color/blue</item></style.2>" +
            "<style.2 name=\"dark\" parent=\"@style.2/light\">" + fillers() +
            "<item name=\"${attribute("PRIMARY_BUTTON_BACKGROUND")}\">@color/blue_alias</item>" +
            "<item name=\"${attribute("FB_LOGO")}\">@color/blue</item>" +
            "<item name=\"${attribute("CARD_BACKGROUND")}\">#ff333334</item>" +
            "<item name=\"${attribute("ACCENT_DEEMPHASIZED")}\">@color/tint</item>" +
            "<item name=\"${attribute("SECONDARY_TEXT")}\">@color/night_text</item>" +
            "<item name=\"${attribute("PRIMARY_TEXT")}\">@color/loop</item>" +
            "<item name=\"${attribute("ACCENT")}\">@color/accent</item>" +
            "<item name=\"android:background\">?attr/${attribute("PRIMARY_BUTTON_BACKGROUND")}</item>" +
            "</style.2>" +
            "<style.2 name=\"darker\" parent=\"@style.2/dark\">" +
            "<item name=\"${attribute("BLUE_LINK")}\">@color/link</item></style.2>" +
            "<style.2 name=\"dark_extra\" parent=\"@style.2/dark\">" +
            "<item name=\"android:textSize\">12sp</item></style.2>" +
            "<style.2 name=\"darker_child\" parent=\"@style.2/darker\">" +
            "<item name=\"${attribute("PRIMARY_TEXT")}\">@color/text</item></style.2>" +
            "<style.2 name=\"unrelated\" parent=\"@style.2/light\">" +
            "<item name=\"${attribute("PRIMARY_TEXT")}\">@color/text</item></style.2>" +
            "</resources>",
    )

    @Test
    fun `the dark style and every style under it are the family, dark first`() {
        val family = darkFdsStyles(defaults(), tokens).map { it.getAttribute("name") }
        assertEquals("dark", family.first())
        assertEquals(setOf("dark", "darker", "dark_extra", "darker_child"), family.toSet())
    }

    @Test
    fun `a file without Facebook's light and dark themes gives no family`() {
        assertEquals(emptyList<Element>(), darkFdsStyles(xml("<resources><style name=\"a\"/></resources>"), tokens))
        val lightOnly = xml("<resources><style.2 name=\"light\">${fillers()}</style.2></resources>")
        assertEquals(emptyList<Element>(), darkFdsStyles(lightOnly, tokens))
    }

    @Test
    fun `a listed token and colour points at the palette tone in the night copy`() {
        val source = defaults()
        val night = xml("<resources/>")
        val nightColours = xml("<resources/>")
        val nightV31 = xml("<resources/>")
        val changed = writeNightStyles(darkFdsStyles(source, tokens), colours, nightColourNames, tokens, night, nightColours, nightV31)

        val copies = night.elements("style.2").associateBy { it.getAttribute("name") }
        assertEquals("only styles with an item to change are copied", setOf("dark", "darker", "darker_child"), copies.keys)
        assertEquals("@style.2/light", copies.getValue("dark").getAttribute("parent"))

        val blue = checkNotNull(nightTone("#ff0866ff"))
        val card = checkNotNull(nightTone("#ff333334"))
        val dark = copies.getValue("dark").items()
        assertEquals("@color/${paletteColourName(blue)}", dark[attribute("PRIMARY_BUTTON_BACKGROUND")])
        assertEquals("@color/${paletteColourName(card)}", dark[attribute("CARD_BACKGROUND")])
        assertEquals("the logo keeps Facebook's blue", "@color/blue", dark[attribute("FB_LOGO")])
        assertEquals("a translucent tint stays", "@color/tint", dark[attribute("ACCENT_DEEMPHASIZED")])
        assertEquals("a colour with a night value stays", "@color/night_text", dark[attribute("SECONDARY_TEXT")])
        assertEquals("a reference loop stays", "@color/loop", dark[attribute("PRIMARY_TEXT")])
        assertEquals("a listed colour too far from every tone stays", "@color/accent", dark[attribute("ACCENT")])
        assertEquals("an unlisted colour stays", "#ff123456", dark[attribute("FILLER_0")])
        assertEquals("a theme attribute stays", "?attr/${attribute("PRIMARY_BUTTON_BACKGROUND")}", dark["android:background"])
        assertEquals("every other item is copied", 310 + 8, dark.size)

        val link = checkNotNull(nightTone("#ff3e93f8"))
        val text = checkNotNull(nightTone("#fff2f4f7"))
        assertEquals("@color/${paletteColourName(link)}", copies.getValue("darker").items()[attribute("BLUE_LINK")])
        assertEquals("@color/${paletteColourName(text)}", copies.getValue("darker_child").items()[attribute("PRIMARY_TEXT")])
        assertEquals(4, changed)

        val fallbacks = nightColours.elements("color").associate { it.getAttribute("name") to it.textContent }
        val system = nightV31.elements("color").associate { it.getAttribute("name") to it.textContent }
        val tones = listOf(blue, card, link, text).distinct()
        assertEquals("each tone is written once", tones.size, nightColours.elements("color").size)
        for (tone in tones) {
            assertEquals(tone.fallback, fallbacks[paletteColourName(tone)])
            assertEquals(tone.systemColor, system[paletteColourName(tone)])
        }
    }

    @Test
    fun `the default styles are never changed`() {
        val source = defaults()
        val before = source.text()
        writeNightStyles(darkFdsStyles(source, tokens), colours, nightColourNames, tokens,
            xml("<resources/>"), xml("<resources/>"), xml("<resources/>"))
        assertEquals(before, source.text())
    }

    @Test
    fun `a style Facebook already gives a night value of its own is left to it`() {
        val night = xml("<resources><style.2 name=\"dark\"><item name=\"x\">#ff000000</item></style.2></resources>")
        val before = night.text()
        val changed = writeNightStyles(darkFdsStyles(defaults(), tokens), colours, nightColourNames, tokens,
            night, xml("<resources/>"), xml("<resources/>"))
        val names = night.elements("style.2").map { it.getAttribute("name") }
        assertEquals(listOf("dark", "darker", "darker_child"), names)
        assertTrue("Facebook's night dark style was changed", night.text().startsWith(before.substringBefore("</resources>")))
        assertEquals(2, changed)
    }

    @Test
    fun `a palette colour already written is not written twice`() {
        val blue = checkNotNull(nightTone("#ff0866ff"))
        val nightColours = xml("<resources><color name=\"${paletteColourName(blue)}\">${blue.fallback}</color></resources>")
        val nightV31 = xml("<resources><color name=\"${paletteColourName(blue)}\">${blue.systemColor}</color></resources>")
        writeNightStyles(darkFdsStyles(defaults(), tokens), colours, nightColourNames, tokens,
            xml("<resources/>"), nightColours, nightV31)
        assertEquals(1, nightColours.elements("color").count { it.getAttribute("name") == paletteColourName(blue) })
        assertEquals(1, nightV31.elements("color").count { it.getAttribute("name") == paletteColourName(blue) })
    }

    @Test
    fun `the tables read opaque and translucent colours`() {
        val listed = listedTokenColours()
        assertEquals(setOf(0xFF0866FF.toInt()), listed["PRIMARY_BUTTON_BACKGROUND"])
        assertEquals(setOf(0x331D85FC, 0x192D88FF), listed["ACCENT_DEEMPHASIZED"])
        assertEquals("ACCENT is in both tables, dark-only and shared", setOf(0xFF1D85FC.toInt(), 0xFF0866FF.toInt()), listed["ACCENT"])
        assertTrue("brand tokens are listed in neither", "FB_LOGO" !in listed && "MAP_HIGHLIGHT_BORDER" !in listed)
    }
}

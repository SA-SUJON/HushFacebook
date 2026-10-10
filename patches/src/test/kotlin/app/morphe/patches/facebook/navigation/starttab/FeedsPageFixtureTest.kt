/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.starttab

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Feeds page Open on a chosen tab starts itself, on every Facebook build the bundle declares:
 * Facebook's link map builds the page's intent from the fragment id the extension asks for, right
 * before it adds the page's launch link, and the screen the extension names is there. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedsPageFixtureTest {
    private val route = "Lapp/morphe/extension/facebook/navigation/StartTabRoute;"

    /** The Feeds page's entry in Facebook's link map, the template its launch link is built from. */
    private val feedsEntry = "fb://recent_feed?source={most_recent_entry_point}"

    private val literals = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

    @Test
    fun `each declared build's link map gives the Feeds page the fragment the start tab asks for`() {
        val fragment = ExtensionDex.intConstant(route, "FEEDS_FRAGMENT")
        val screen = "L" + ExtensionDex.stringConstant(route, "PAGE_SCREEN").replace('.', '/') + ";"
        val launch = ExtensionDex.stringConstant(route, "FEEDS_LAUNCH_LINK")
        assertTrue("the launch link isn't the Feeds page's: $launch", launch.startsWith(feedsEntry.substringBefore('{')))

        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name
                val maps = FixtureDex.methodsWhere(fixture, { dex -> dex.stringSection.any { it == feedsEntry } }) {
                    holdsString(it, feedsEntry)
                }
                assertEquals("$name: link maps holding the Feeds page", 1, maps.size)
                val code = maps.single().implementation!!.instructions.toList()
                val at = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == feedsEntry }
                val built = code.subList(maxOf(0, at - 16), at)
                assertTrue(
                    "$name: the Feeds page's intent isn't built right before its launch link",
                    built.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == "Landroid/content/Intent;" },
                )
                assertTrue(
                    "$name: the Feeds page's fragment isn't $fragment",
                    built.any { it.opcode in literals && (it as NarrowLiteralInstruction).narrowLiteral == fragment },
                )
                assertEquals("$name: the page screen", setOf(screen), FixtureDex.classes(fixture, setOf(screen)).keys)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

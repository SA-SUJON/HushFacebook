/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each declared Facebook build has exactly one factory for each of the browser's link history
 * writers, the method Open links in external browser has answer null. The patch refuses a build where
 * either count is off, so a fixture that moved one fails here first. Each factory also has to answer
 * null on its own somewhere, which is what makes null a value its callers already handle, and needs a
 * local register for the hook to borrow.
 */
class LinkHistoryWritersFixtureTest {
    @Test
    fun `each declared build has one factory per link history writer, and each can answer null`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                for (writer in LINK_HISTORY_WRITERS) {
                    val factories = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.typeSection.any { it == writer } }) {
                        isWriterFactory(it, writer)
                    }
                    assertEquals(
                        "${bundle.name}: factories of $writer " + factories.joinToString { "${it.definingClass}->${it.name}" },
                        1,
                        factories.size,
                    )
                    val factory = factories.single()
                    val code = factory.implementation!!.instructions.toList()
                    val nulls = code.indices.filter { at ->
                        code[at].opcode == Opcode.CONST_4 && (code[at] as NarrowLiteralInstruction).narrowLiteral == 0
                    }.map { (code[it] as OneRegisterInstruction).registerA }.toSet()
                    assertTrue(
                        "${bundle.name}: ${factory.definingClass}->${factory.name} never answers null",
                        code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in nulls },
                    )
                    val locals = factory.implementation!!.registerCount - factory.parameterTypes.size - 1
                    assertTrue("${bundle.name}: ${factory.definingClass}->${factory.name} has no local to borrow", locals >= 1)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

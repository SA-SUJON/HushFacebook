/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comment sheet's rows (issue #37): code that resolves a theme attribute into a TypedValue and
 * reads its `data` gets SURFACE_BACKGROUND's night colour (#252728) with no colour call for Material
 * You to reroute. The patch sends each such read through `MaterialYouTheme.colourData`, which
 * answers for a colour value only. First the rewrite on made-up methods, then on the helpers each
 * declared build has: a static method of a context and an attribute that resolves the attribute and
 * returns `data` (581 has several, the Litho colour lookup with a fallback among them).
 */
class ColourDataReadFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private val data = "Landroid/util/TypedValue;->data:I"
    private val resolve = "Landroid/content/res/Resources\$Theme;->resolveAttribute(ILandroid/util/TypedValue;Z)Z"

    private fun method(smali: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Reader;", "read", emptyList(), "I",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(8, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    @Test
    fun `each data read becomes a call on its TypedValue and a move-result into its register`() {
        val method = method("""
            iget v0, v2, $data
            iget v3, v3, $data
            iget v4, v2, Landroid/util/TypedValue;->type:I
            return v0
        """)
        assertEquals(2, method.readColourData())
        val body = method.body()
        assertEquals(
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IGET, Opcode.RETURN),
            body.map { it.opcode },
        )
        assertEquals(COLOUR_DATA, body[0].reference())
        assertEquals(listOf(2), (body[0] as FiveRegisterInstruction).let { listOf(it.registerC) })
        assertEquals(0, (body[1] as OneRegisterInstruction).registerA)
        assertEquals("the TypedValue is read before its own register is written", listOf(3),
            (body[2] as FiveRegisterInstruction).let { listOf(it.registerC) })
        assertEquals(3, (body[3] as OneRegisterInstruction).registerA)
        assertEquals("the type read stays", "Landroid/util/TypedValue;->type:I", body[4].reference())
    }

    @Test
    fun `a method with no data read isn't touched`() {
        val method = method("""
            iget v0, v2, Landroid/util/TypedValue;->type:I
            return v0
        """)
        assertEquals(0, method.readColourData())
        assertEquals(listOf(Opcode.IGET, Opcode.RETURN), method.body().map { it.opcode })
    }

    @Test
    fun `the colour helpers of each declared build reach the extension's read`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                // A static (context, attribute) -> int that resolves the attribute and returns its data.
                val helpers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.isDataHelper() }) helpers += ImmutableClassDef.of(classDef)
                    }
                }
                assertTrue("$name: no helper reads a theme attribute's data", helpers.isNotEmpty())

                val reads = helpers.sumOf { classDef -> classDef.methods.sumOf { method -> method.body().count { it.reference() == data } } }
                val replaced = with(PatchContexts.of(helpers)) { readColourData() }
                assertEquals("$name: every data read in them goes to the extension", reads, replaced)
                assertTrue("$name: at least one read", replaced > 0)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.isDataHelper(): Boolean {
        if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "I" || implementation == null) return false
        val parameters = parameterTypes.map(CharSequence::toString)
        if (parameters.firstOrNull() != "Landroid/content/Context;" || parameters.drop(1).any { it != "I" }) return false
        val body = body()
        return body.any { it.reference() == resolve } && body.any { it.reference() == data }
    }
}

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.switchKeys
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comment sheet options on every Facebook build the bundle declares. The comment box's button
 * socket is found by the name it gives itself, its name table by the GIF and sticker buttons, and
 * its check, in another class, by the numbers it shares with the table. The reaction picker is the
 * one method that builds the UFI dock and logs its name. Then each hook: the button's name from
 * the table with the check's own number as a range, the extension asked, a yes answering no; and
 * the picker asking first and returning on a yes. A no lands on Facebook's first instruction, and
 * nothing of Facebook's code moves. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class CommentSheetOptionsFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun holders(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

    private fun buttons(bundle: File) {
        val name = bundle.name
        val sockets = holders(bundle, BUTTON_SOCKET).flatMap { methodsHolding(it, BUTTON_SOCKET) }
        assertTrue("$name: nothing names \"$BUTTON_SOCKET\"", sockets.isNotEmpty())
        val found = buttonSocket(holders(bundle, GIF_BUTTON), sockets) { types -> FixtureDex.classes(bundle, types) }
        val table = found.table
        val check = found.check
        val where = "$name: ${check.descriptor()}"
        assertNotEquals("$where: the check was expected in another class than the table",
            table.definingClass, check.definingClass)
        assertTrue("$where isn't static", AccessFlags.STATIC.isSet(check.accessFlags))
        assertEquals("$where: the button's number isn't last", "I", check.parameterTypes.last().toString())
        assertTrue("$where: the table has fewer than the two buttons", switchKeys(table).single().size >= 2)

        val owner = FixtureDex.classes(bundle, setOf(check.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == check.descriptor() }
        val original = method.implementation!!.instructions.toList()
        val number = method.implementation!!.registerCount - 1
        method.holdButtons(table)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains seven instructions", original.size + 7, patched.size)
        assertEquals("$where: the table is read with a range call", Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
        assertEquals("$where: the button's name comes from the table", table.descriptor(), patched[0].reference())
        val range = patched[0] as RegisterRangeInstruction
        assertEquals("$where: with the check's own number, its last register", listOf(number, 1),
            listOf(range.startRegister, range.registerCount))
        assertEquals("$where: the name is kept", Opcode.MOVE_RESULT_OBJECT, patched[1].opcode)
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC, patched[2].opcode)
        assertEquals("$where: the extension is asked", HOLDS_BUTTON, patched[2].reference())
        val ask = patched[2] as FiveRegisterInstruction
        assertEquals("$where: with the name it was handed", listOf(1, register), listOf(ask.registerCount, ask.registerC))
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[3].opcode)
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[4].opcode)
        assertEquals("$where: a yes answers no", listOf(Opcode.CONST_4, Opcode.RETURN), patched.subList(5, 7).map { it.opcode })
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(7).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(5, 7), ControlFlow.of(method).normal[4].toSet())
    }

    private fun picker(bundle: File) {
        val picker = reactionPicker(holders(bundle, DOCK_NAME))
        val where = "${bundle.name}: ${picker.descriptor()}"
        assertTrue("$where isn't the reaction picker", isReactionPicker(picker))

        val owner = FixtureDex.classes(bundle, setOf(picker.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == picker.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.skipPicker()
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains four instructions", original.size + 4, patched.size)
        assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC, patched[0].opcode)
        assertEquals("$where: the extension is asked first", SKIP_PICKER, patched[0].reference())
        assertEquals("$where: with nothing handed over", 0, (patched[0] as FiveRegisterInstruction).registerCount)
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes returns before the picker opens", Opcode.RETURN_VOID, patched[3].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(4).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 4), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build keeps the GIF and sticker buttons out and the reaction picker closed`() = bundles { bundle ->
        buttons(bundle)
        picker(bundle)
    }
}

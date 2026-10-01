/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta AI questions under posts' anchor on every Facebook build the bundle declares: the one
 * method loading DeepDivePillSocket and GenAiDeepDivePillPlugin's class name, with every plugin
 * name in one register and the per-plugin check called twice, each answer in its own move-result.
 * Then the patch on it: after each answer, the extension gets the answer and the plugin's name, and
 * its own answer lands back in the register the branch reads. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HideMetaAiQuestionsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build has the pill socket once, and each check's answer goes through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, META_AI_PILL).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val sockets = owners.flatMap { owner ->
                    owner.methods.filter { method ->
                        method.implementation?.instructions?.any { holds(it, META_AI_PILL) } == true &&
                            method.implementation?.instructions?.any { holds(it, PILL_SOCKET) } == true
                    }
                }
                assertEquals("$name: methods loading $PILL_SOCKET and the Meta AI plugin", 1, sockets.size)
                val socket = pillSocket(sockets.single())
                val original = socket.method.code()

                // Every pill plugin the socket names, Meta AI's among them, goes to the one register.
                val plugins = original.filter { holdsPrefix(it, PILL_PLUGINS) }
                assertTrue("$name: the socket names only ${plugins.size} plugins", plugins.size >= 5)
                assertTrue("$name: Meta AI's plugin isn't one of them", plugins.any { holds(it, META_AI_PILL) })
                assertEquals("$name: two calls of the per-plugin check", 2, socket.answers.size)

                val owner = owners.single { it.type == socket.method.definingClass }
                val context = PatchContexts.of(listOf(owner, ExtensionDex.classDef(SETTINGS_STATUS)))
                hideMetaAiQuestionsPatch.execute(context)

                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == socket.method.name && it.parameterTypes == socket.method.parameterTypes
                }.code()
                assertEquals("$name: two instructions after each check", original.size + 2 * socket.answers.size, patched.size)
                val calls = patched.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == KEEP }
                assertEquals("$name: calls of the extension", socket.answers.size, calls.size)
                for ((at, call) in calls) {
                    val answer = (patched[at - 1] as OneRegisterInstruction).registerA
                    assertTrue("$name: the call at $at doesn't follow a check's move-result",
                        patched[at - 1].opcode == Opcode.MOVE_RESULT && isPluginCheck(patched[at - 2], owner.type))
                    // The patcher's compiler drops an instruction whose registers don't fit, so
                    // the call has to be there with both registers, in order.
                    assertEquals("$name: the call at $at", Opcode.INVOKE_STATIC, call.opcode)
                    val registers = call as FiveRegisterInstruction
                    assertEquals("$name: registers handed over at $at", 2, registers.registerCount)
                    assertEquals("$name: the check's answer at $at", answer, registers.registerC)
                    assertEquals("$name: the plugin's name at $at", socket.nameRegister, registers.registerD)
                    assertEquals("$name: the extension's answer at $at", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                    assertEquals("$name: the extension's answer lands where the branch reads it", answer,
                        (patched[at + 1] as OneRegisterInstruction).registerA)
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "metaAiQuestions" }
                assertEquals("$name: SettingsStatus.metaAiQuestions() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    private fun holds(instruction: Instruction, string: String) =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == string

    private fun holdsPrefix(instruction: Instruction, prefix: String) =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(prefix) == true
}

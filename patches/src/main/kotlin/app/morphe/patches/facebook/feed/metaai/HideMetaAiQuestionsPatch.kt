/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.metaai

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Hide Meta AI questions under posts"

/** The name the pill socket gives itself, in the one method that draws every pill under a post. */
internal const val PILL_SOCKET = "DeepDivePillSocket"

/** The package of the pill plugins. The socket loads each plugin's class name before it asks about it. */
internal const val PILL_PLUGINS = "com.facebook.feed.plugins.attachments.deepdivepill.impl."

/** Meta AI's pill plugin: the row of Meta AI questions under a post. */
internal const val META_AI_PILL = PILL_PLUGINS + "genai.GenAiDeepDivePillPlugin"

internal const val META_AI_QUESTIONS = "Lapp/morphe/extension/facebook/feed/MetaAiQuestions;"
internal const val KEEP = "$META_AI_QUESTIONS->keep(ILjava/lang/Object;)Z"

/**
 * The row of Meta AI questions under some posts goes (issue #48). Facebook calls it a deep dive
 * pill, and one static method draws every kind of pill under a post (580 `LX/3A1;->A06`, 577
 * `LX/39F;->A06`, both naming themselves DeepDivePillSocket). It goes through the pill plugins in
 * a fixed order: for each, it loads the plugin's class name into one register, asks a static
 * (model, index) -> Z check of its own class whether the plugin applies to the post, and draws the
 * first one that does. A second call of the same check feeds a log and jumps back to the same
 * branch. After each of those calls, the answer goes through the extension with the plugin's name,
 * and the extension turns a yes for GenAiDeepDivePillPlugin into a no while the switch is on, so
 * the socket moves on to the next plugin. Every other pill, the affiliate one included, keeps
 * Facebook's answer.
 */
@Suppress("unused")
val hideMetaAiQuestionsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide Meta AI questions under posts",
    description = "Removes the row of Meta AI questions Facebook adds under some posts. The post, its link " +
        "card and its buttons stay.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val socket = findPillSocket()
        val method = mutableClassDefBy(socket.method.definingClass).findMutableMethodOf(socket.method)
        // From the last check back, so the indices of the earlier ones stay where they are.
        socket.answers.sortedDescending().forEach { index ->
            val answer = method.getInstruction<OneRegisterInstruction>(index).registerA
            method.addInstructions(
                index + 1,
                """
                    invoke-static { v$answer, v${socket.nameRegister} }, $KEEP
                    move-result v$answer
                """,
            )
        }
        enableStatus("metaAiQuestions")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The pill socket: its method, the register it loads each plugin's class name into, and the index
 * of the move-result after each call of its per-plugin check.
 */
internal class PillSocket(val method: Method, val nameRegister: Int, val answers: List<Int>)

/** Whether [instruction] calls a static (model, index) -> Z method of [owner]: the socket's per-plugin check. */
internal fun isPluginCheck(instruction: Instruction, owner: String): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC) return false
    val target = (instruction as ReferenceInstruction).reference as MethodReference
    return target.definingClass == owner && target.returnType == "Z" && target.parameterTypes.size == 2 &&
        target.parameterTypes[0].toString().startsWith("L") && target.parameterTypes[1].toString() == "I"
}

/**
 * Reads the socket out of [method], which loads [PILL_SOCKET] and [META_AI_PILL]. Refuses when the
 * plugin names go to more than one register, when no per-plugin check is followed by a move-result,
 * or when a register the hook hands over doesn't fit the call's four bits.
 */
internal fun pillSocket(method: Method): PillSocket {
    val code = method.implementation!!.instructions.toList()
    val nameRegisters = code.filter { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
        .filter { ((it as ReferenceInstruction).reference as StringReference).string.startsWith(PILL_PLUGINS) }
        .map { (it as OneRegisterInstruction).registerA }.toSet()
    val nameRegister = nameRegisters.singleOrNull()
        ?: refuse("expected the plugin names in one register in ${method.definingClass}->${method.name}, found $nameRegisters")
    val answers = code.indices.filter { index ->
        index > 0 && code[index].opcode == Opcode.MOVE_RESULT && isPluginCheck(code[index - 1], method.definingClass)
    }
    if (answers.isEmpty()) refuse("found no (model, index) -> Z check in ${method.definingClass}->${method.name}")
    val registers = answers.map { (code[it] as OneRegisterInstruction).registerA } + nameRegister
    if (registers.any { it > 15 }) refuse("a register the hook hands over is past v15: $registers")
    return PillSocket(method, nameRegister, answers)
}

/** The one method that loads both [PILL_SOCKET] and [META_AI_PILL], read as a socket. Changes nothing. */
internal fun BytecodePatchContext.findPillSocket(): PillSocket {
    val methods = classDefByStrings(META_AI_PILL, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, META_AI_PILL) && holdsString(it, PILL_SOCKET) } }
    val method = methods.singleOrNull()
        ?: refuse("expected one method loading \"$PILL_SOCKET\" and $META_AI_PILL, found ${methods.size}")
    return pillSocket(method)
}

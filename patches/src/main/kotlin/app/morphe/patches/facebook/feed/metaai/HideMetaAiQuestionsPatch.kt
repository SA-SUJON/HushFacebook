/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.metaai

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
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
internal const val DROPS_DEFAULT_PILL = "$META_AI_QUESTIONS->dropsDefaultPill(Ljava/lang/String;)Z"

/** The type the socket's default way of drawing a pill gives Meta AI's icon to. */
internal const val META_AI_TYPE = "meta_ai"

/**
 * The type name the default way compares a pill's type with first once every icon path has
 * joined, so every pill that way draws passes that compare.
 */
internal const val STARS_TYPE = "stars"

/** The getter of Facebook's tree class the default way reads a pill's type with, by the field's key. */
internal const val TYPE_GETTER = "getCachedNullableString"

private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

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
 *
 * A plugin that says yes but brings no renderer, or one whose renderer draws nothing, gets the
 * socket's default way of drawing a pill (580 `LX/3A1;->A06` from @450): it picks an icon, Meta AI's
 * for a pill typed `meta_ai`, and builds the pill. The default click handler's check doesn't look
 * at the type, so a Meta AI row could come back that way once Meta AI's own plugin said no. Once
 * every icon path has joined, the default way reads the type again and compares it with `stars`
 * first. Right after that read, the type goes through the extension, and a pill typed `meta_ai`
 * gets no pill while the switch is on. Other types are drawn as before.
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
        val pill = defaultPill(socket.method)
        if (socket.answers.any { it >= pill.index }) {
            refuse("a check of the plugins in ${socket.method.definingClass} comes after its default way of drawing a pill")
        }
        val method = mutableClassDefBy(socket.method.definingClass).findMutableMethodOf(socket.method)
        // The default way's hook first, then the checks from the last back, so the indices of
        // the earlier ones stay where they are.
        method.hookDefaultPill(pill)
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

/**
 * A read of a pill's type compared with a type name right away: [TYPE_GETTER] on the tree in
 * [tree] with the key in [key] (null when no literal loads it just before), the answer moved into
 * [typeRegister], the name loaded into [nameRegister] at [nameIndex], then String.equals of the two.
 */
internal class TypeCompare(
    val name: String,
    val nameIndex: Int,
    val typeRegister: Int,
    val nameRegister: Int,
    val tree: Int,
    val key: Long?,
)

/** Every [TypeCompare] in [code]. */
internal fun typeCompares(code: List<Instruction>): List<TypeCompare> = (2 until code.size - 1).mapNotNull { index ->
    val load = code[index]
    if (load.opcode != Opcode.CONST_STRING && load.opcode != Opcode.CONST_STRING_JUMBO) return@mapNotNull null
    val name = ((load as ReferenceInstruction).reference as StringReference).string
    val nameRegister = (load as OneRegisterInstruction).registerA
    val moved = code[index - 1]
    if (moved.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
    val typeRegister = (moved as OneRegisterInstruction).registerA
    val read = code[index - 2]
    if (read.opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
    val getter = (read as ReferenceInstruction).reference as MethodReference
    if (getter.name != TYPE_GETTER || getter.parameterTypes.map { it.toString() } != listOf("I") ||
        getter.returnType != "Ljava/lang/String;"
    ) {
        return@mapNotNull null
    }
    val equals = code[index + 1]
    if (equals.opcode != Opcode.INVOKE_VIRTUAL || (equals as ReferenceInstruction).reference.toString() != STRING_EQUALS) {
        return@mapNotNull null
    }
    val compared = equals as FiveRegisterInstruction
    if (compared.registerCount != 2 || compared.registerC != nameRegister || compared.registerD != typeRegister) {
        return@mapNotNull null
    }
    val call = read as FiveRegisterInstruction
    TypeCompare(name, index, typeRegister, nameRegister, call.registerC, keyOf(code, index - 2, call.registerD))
}

/** The literal a const loads into [register] within the few instructions before [index], if one does. */
private fun keyOf(code: List<Instruction>, index: Int, register: Int): Long? {
    for (at in index - 1 downTo maxOf(0, index - 4)) {
        val instruction = code[at]
        if (register !in instruction.namedRegisters()) continue
        val loads = instruction.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
        return if (loads && (instruction as OneRegisterInstruction).registerA == register) {
            (instruction as WideLiteralInstruction).wideLiteral
        } else {
            null
        }
    }
    return null
}

/**
 * Where the socket's default way of drawing a pill gets its hook: the [STARS_TYPE] name's load at
 * [index], right after the pill's type is read into [typeRegister]. The name's own register,
 * [freeRegister], is written by that load, so the hook can use it until then.
 */
internal class DefaultPill(val index: Int, val typeRegister: Int, val freeRegister: Int)

/**
 * Reads the default way's hook point out of [method]. Refuses unless the method compares a pill's
 * type with [META_AI_TYPE] once, reading it with a literal key, and compares the type read with
 * that key from the same tree with [STARS_TYPE] once, and unless that compare can only be reached
 * through the read just before it.
 */
internal fun defaultPill(method: Method): DefaultPill {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val compares = typeCompares(code)
    val metaAi = compares.filter { it.name == META_AI_TYPE }
    val icon = metaAi.singleOrNull()
        ?: refuse("expected one compare of a pill's type with \"$META_AI_TYPE\" in $where, found ${metaAi.size}")
    if (icon.key == null) refuse("the pill type compared with \"$META_AI_TYPE\" in $where isn't read with a literal key")
    val stars = compares.filter { it.name == STARS_TYPE && it.key == icon.key && it.tree == icon.tree }
    val site = stars.singleOrNull()
        ?: refuse("expected one compare of the same pill type with \"$STARS_TYPE\" in $where, found ${stars.size}")
    val flow = ControlFlow.of(method)
    val into = flow.normal.indices.filter { site.nameIndex in flow.normal[it] }
    if (into != listOf(site.nameIndex - 1)) {
        refuse("in $where the \"$STARS_TYPE\" compare can be reached from $into, not only through the read of the type")
    }
    return DefaultPill(site.nameIndex, site.typeRegister, site.nameRegister)
}

/**
 * Hands the pill's type to the extension right after the default way reads it, before the
 * [STARS_TYPE] compare, and returns no pill when the extension says to drop it. The answer goes
 * into the register the compare's name is about to be loaded into, so nothing live is touched.
 * The compare's own plain call names both registers, so both fit the plain forms here.
 */
internal fun MutableMethod.hookDefaultPill(pill: DefaultPill) {
    val free = pill.freeRegister
    addInstructionsWithLabels(
        pill.index,
        """
            invoke-static { v${pill.typeRegister} }, $DROPS_DEFAULT_PILL
            move-result v$free
            if-eqz v$free, :draw
            const/4 v$free, 0x0
            return-object v$free
        """,
        ExternalLabel("draw", getInstruction(pill.index)),
    )
}

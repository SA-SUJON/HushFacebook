/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The selected tab's colour in Facebook's tab bar, for Material You (#65). Read from 577, 580 and
 * 581, 2026-10-07; the obfuscated names in these comments are for reviewers, the code never writes
 * one down.
 *
 * The tab bar takes its colours from one abstract provider (581 LX/3W1, 580 LX/3Zu, 577 LX/3aF)
 * with two subclasses each. TabBarContainerLayout, a class Redex keeps, is handed the provider with
 * the tab id in a setter (581 FGg(FbUserSession, LX/3W1, J), 577 FAb(LX/3aF, J)), and the first
 * colour it asks the provider for goes straight into the Paint of the line it draws over the
 * selected tab. That's the selected tab's colour (581 LX/3W1;->A04, the TAB_BAR_ACTIVE_ICON,
 * PRIMARY_ICON or ACCENT token by Facebook's own switches). Its only other caller is the tab view's
 * tint method, which colours the selected tab's icon with it and the others with the unselected
 * colour (581 LX/2Bf;->A06, 580 LX/27i;->A06, 577 LX/2BK;->A05). 580 and 581 pass the session, 577
 * passes nothing, so the method is found by the Paint it fills, not by its parameters.
 */

internal const val TAB_BAR_CONTAINER = "Lcom/facebook/navigation/tabbar/ui/TabBarContainerLayout;"

private const val PAINT_SET_COLOR = "Landroid/graphics/Paint;->setColor(I)V"

/** How far after the colour's move-result the setter may hand it to the Paint. 581 does it three later. */
private const val PAINT_WINDOW = 4

internal const val TAB_BAR_SELECTED = "$MATERIAL_YOU->tabBarSelected(I)I"

private const val PATCH = "Material You theme"

private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun MethodReference.sameAs(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/**
 * The selected tab's colour method: in each of [layout]'s setters that take the colour provider,
 * an abstract class [isAbstract] answers for, next to last and the tab id (a long) last, the first
 * call to the provider answering an int whose move-result goes into `Paint.setColor` within
 * [PAINT_WINDOW] instructions. Refuses unless exactly one setter has one.
 */
internal fun selectedTabColour(layout: ClassDef, isAbstract: (String) -> Boolean): MethodReference {
    val found = layout.methods.mapNotNull { setter ->
        val parameters = setter.parameterTypes.map { it.toString() }
        if (setter.returnType != "V" || parameters.size < 2 || parameters.last() != "J") return@mapNotNull null
        val provider = parameters[parameters.size - 2]
        if (!provider.startsWith("L") || !isAbstract(provider)) return@mapNotNull null
        val code = setter.implementation?.instructions?.toList() ?: return@mapNotNull null
        code.indices.firstNotNullOfOrNull { at ->
            val call = code[at].called()?.takeIf { it.definingClass == provider && it.returnType == "I" }
                ?: return@firstNotNullOfOrNull null
            val kept = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
                ?: return@firstNotNullOfOrNull null
            val paints = (at + 2..minOf(code.lastIndex, at + 1 + PAINT_WINDOW)).any { next ->
                code[next].called()?.toString() == PAINT_SET_COLOR && kept.registerA in code[next].registers()
            }
            call.takeIf { paints }
        }
    }.distinctBy { it.toString() }
    return found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one colour $TAB_BAR_CONTAINER asks its colour provider for and paints the selected tab's " +
            "line with, found ${found.size}",
    )
}

/** The index of the move-result after each call to [colour] in [method] that keeps the answer. */
internal fun selectedTabColourReads(method: Method, colour: MethodReference): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        code[at].called()?.sameAs(colour) == true && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 1 }
}

/**
 * Every place that asks for the selected tab's colour hands the answer to the extension, which
 * gives back the palette's accent in the dark theme. Refuses unless the setter's own read and at
 * least one other, the tab icon's tint, are found.
 */
internal fun BytecodePatchContext.hookSelectedTabColour() {
    val layout = classDefByOrNull(TAB_BAR_CONTAINER)
        ?: throw PatchException("$PATCH: this Facebook build has no $TAB_BAR_CONTAINER")
    val colour = selectedTabColour(layout) { type ->
        classDefByOrNull(type)?.let { AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
    }
    val readers = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        classDef.methods.filterTo(mutableListOf()) { selectedTabColourReads(it, colour).isNotEmpty() }
            .forEach { readers += classDef.type to it }
    }
    if (readers.none { it.first == TAB_BAR_CONTAINER } || readers.none { it.first != TAB_BAR_CONTAINER }) {
        throw PatchException(
            "$PATCH: expected $colour read by $TAB_BAR_CONTAINER and by the tab icons, found ${readers.map { it.first }}",
        )
    }
    readers.forEach { (type, method) ->
        mutableClassDefBy(type).findMutableMethodOf(method).recolourSelectedTab(colour)
    }
}

/**
 * Puts the extension right after each move-result keeping the selected tab's colour, on the same
 * register, so nothing is borrowed. Last first, so the earlier indices stay right.
 */
internal fun MutableMethod.recolourSelectedTab(colour: MethodReference) {
    selectedTabColourReads(this, colour).asReversed().forEach { moveResult ->
        val register = (implementation!!.instructions[moveResult] as OneRegisterInstruction).registerA
        addInstructions(
            moveResult + 1,
            """
                invoke-static/range { v$register .. v$register }, $TAB_BAR_SELECTED
                move-result v$register
            """,
        )
    }
}

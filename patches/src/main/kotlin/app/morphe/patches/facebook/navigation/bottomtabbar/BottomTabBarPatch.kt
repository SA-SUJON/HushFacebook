/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.util.namedRegisters

internal const val PATCH = "Tab bar at the bottom"

/** The name of Facebook's own preference that overrides where the tab bar goes. */
internal const val OVERRIDE_KEY = "fb4a_bottom_tabs_override_enabled"

internal const val FB_SHARED_PREFERENCES = "Lcom/facebook/prefs/shared/FbSharedPreferences;"
internal const val TRI_STATE = "Lcom/facebook/common/util/TriState;"

internal const val BOTTOM_TAB_BAR = "Lapp/morphe/extension/facebook/navigation/BottomTabBar;"
internal const val OVERRIDE = "$BOTTOM_TAB_BAR->override(I)I"

/**
 * The tab bar goes to the bottom. Facebook gives some accounts the bar at the top and others at
 * the bottom, by a server setting, and keeps a preference of its own, [OVERRIDE_KEY], that
 * overrides that setting either way. The preference's key is a static field its class sets from
 * that name (580 `LX/1rL;->A01`, 577 `LX/1u6;->A01`). Each place that decides where the bar goes
 * (580 `LX/1rJ;->A06` and `A09`, 577 `LX/1qY;->A06` and `A0A`) reads the key from FbSharedPreferences
 * as a TriState and branches on its ordinal. The extension goes right after each ordinal and
 * answers YES's while the switch is on. Facebook's own tab menu writes the key without reading it,
 * and that's left alone.
 */
@Suppress("unused")
val bottomTabBarPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Tab bar at the bottom",
    description = "Moves Facebook's tab bar to the bottom of the screen on accounts that have it at the top. Its " +
        "switch starts off, so turn it on under Appearance and restart Facebook.",
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val key = overrideKeyField(
            classDefByStrings(OVERRIDE_KEY, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val reads = mutableListOf<OverrideRead>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            classDef.methods.forEach { method -> reads += overrideReads(method, key) }
        }
        if (reads.isEmpty()) refuse("nothing reads $key from FbSharedPreferences")
        reads.groupBy { it.method }.forEach { (method, sites) ->
            mutableClassDefBy(method.definingClass).findMutableMethodOf(method).hookOverrideReads(sites)
        }
        enableStatus("bottomTabBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** One read of the override: its method, and the move-result that keeps the ordinal in [register]. */
internal class OverrideRead(val method: Method, val resultIndex: Int, val register: Int)

private fun loadsKeyName(instruction: Instruction) =
    instruction.opcode.name.startsWith("const-string") &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == OVERRIDE_KEY

private fun fieldOf(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference as? FieldReference

/**
 * The static field Facebook keeps the override's key in: the first one a class's `<clinit>`
 * stores into its own class after loading [OVERRIDE_KEY]. Refuses unless [holders] have exactly one.
 */
internal fun overrideKeyField(holders: List<ClassDef>): FieldReference {
    val fields = holders.flatMap { holder ->
        holder.methods.filter { it.name == "<clinit>" }.mapNotNull { clinit ->
            val code = clinit.implementation?.instructions?.toList().orEmpty()
            val name = code.indexOfFirst(::loadsKeyName)
            if (name < 0) return@mapNotNull null
            code.drop(name + 1).firstOrNull { it.opcode == Opcode.SPUT_OBJECT }?.let(::fieldOf)
                ?.takeIf { it.definingClass == holder.type }
        }
    }.distinct()
    return fields.singleOrNull()
        ?: refuse("expected one static field set from \"$OVERRIDE_KEY\", found ${fields.size}")
}

/**
 * The reads of [key] in [method]. A load of the key that goes straight into a call on
 * FbSharedPreferences is a read, and that call has to answer a TriState whose ordinal() is taken
 * right away, the ordinal kept by a move-result. A load that goes anywhere else is taken for a
 * write, the way Facebook's tab menu stores the key, and left alone.
 */
internal fun overrideReads(method: Method, key: FieldReference): List<OverrideRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val where = "${method.definingClass}->${method.name}"
    return code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT && fieldOf(code[it]) == key }.mapNotNull { load ->
        val keyRegister = (code[load] as OneRegisterInstruction).registerA
        val call = code.getOrNull(load + 1)
        val read = ((call as? ReferenceInstruction)?.reference as? MethodReference)
            ?.takeIf { it.definingClass == FB_SHARED_PREFERENCES && keyRegister in call.namedRegisters() }
            ?: return@mapNotNull null
        if (read.returnType != TRI_STATE) refuse("$where reads the override with $read, which doesn't answer a TriState")
        val triState = code.getOrNull(load + 2)
        val ordinal = code.getOrNull(load + 3)
        val result = code.getOrNull(load + 4)
        val ordinalCall = (ordinal as? ReferenceInstruction)?.reference as? MethodReference
        val takesOrdinal = triState?.opcode == Opcode.MOVE_RESULT_OBJECT && ordinal?.opcode == Opcode.INVOKE_VIRTUAL &&
            ordinalCall?.name == "ordinal" && ordinalCall.parameterTypes.isEmpty() && ordinalCall.returnType == "I" &&
            ordinal.namedRegisters() == listOf((triState as OneRegisterInstruction).registerA) &&
            result?.opcode == Opcode.MOVE_RESULT
        if (!takesOrdinal) refuse("$where doesn't take the ordinal of the override's TriState right after reading it")
        OverrideRead(method, load + 4, (result as OneRegisterInstruction).registerA)
    }
}

/**
 * Puts the extension right after each read's ordinal. A range call names the register whatever
 * its number, and the extension's answer lands back where Facebook's branches read it. Nothing
 * may jump to the instruction after the ordinal, since code arriving there would skip the call.
 */
internal fun MutableMethod.hookOverrideReads(reads: List<OverrideRead>) {
    for (read in reads.sortedByDescending { it.resultIndex }) {
        val next = implementation!!.instructions[read.resultIndex + 1] as BuilderInstruction
        if (next.location.labels.isNotEmpty()) {
            refuse("$definingClass->$name has a jump to the instruction after the override's ordinal")
        }
        addInstructions(
            read.resultIndex + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $OVERRIDE
                move-result v${read.register}
            """,
        )
    }
}

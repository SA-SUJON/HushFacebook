/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Facebook's browser's two link history writers, read from 582 on 2026-10-10. The obfuscated names
 * here are for reviewers; the code finds each factory by the kept writer class it creates.
 *
 * Each writer has one factory, an instance method taking the browser's launch state and answering
 * the writer, or null when the launch intent and Facebook's server config leave the feature off
 * (582 LX/IeR;->ATc for the signals writer, LX/IeS;->ATc for the page data writer). Neither factory
 * reads your Enhanced browsing setting. That's read later, where the writers' records are sent
 * (CONTRIBUTING.md has the map). The extension's LinkHistory.hold() goes first in each factory, and a
 * yes answers null, the factory's own off answer, so the writer never exists.
 */

private const val PATCH = "Open links in external browser"

internal val LINK_HISTORY_WRITERS = listOf(
    // Records the links you open with when each page started, became usable and finished loading.
    "Lcom/facebook/browser/lite/extensions/browserhistory/LinkHistorySignalsWriter;",
    // Records each page's address and title, which Facebook saves as your link history.
    "Lcom/facebook/browser/lite/extensions/browserhistory/LinkHistoryUiDataWriter;",
)

internal const val HOLD_LINK_HISTORY = "Lapp/morphe/extension/facebook/misc/LinkHistory;->hold()Z"

/** Whether [method] is a factory of [writer]: a method outside it that creates one and answers an object. */
internal fun isWriterFactory(method: Method, writer: String): Boolean =
    method.definingClass != writer && method.returnType.startsWith("L") &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == writer
        } == true

/**
 * The one factory of each writer in [LINK_HISTORY_WRITERS], found in one walk over the app. None, or
 * two, for either writer stops the patch rather than ship a switch that leaves one writer running.
 */
internal fun BytecodePatchContext.linkHistoryFactories(): List<Method> {
    val found = LINK_HISTORY_WRITERS.associateWith { mutableListOf<Method>() }
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
        for (method in classDef.methods) {
            for (writer in LINK_HISTORY_WRITERS) if (isWriterFactory(method, writer)) found.getValue(writer) += method
        }
    }
    val wrong = found.filterValues { it.size != 1 }
    if (wrong.isNotEmpty()) {
        throw PatchException(
            "$PATCH: expected one factory of each link history writer, found " +
                wrong.entries.joinToString { (writer, factories) -> "${factories.size} of $writer" },
        )
    }
    return found.values.map { it.single() }
}

/** The mutable copy of [factory], to hook. */
internal fun BytecodePatchContext.mutableFactory(factory: Method): MutableMethod =
    mutableClassDefBy(factory.definingClass).findMutableMethodOf(factory)

/** First thing in a writer's factory: ask the extension, and answer null on a yes. Otherwise the factory runs as before. */
internal fun MutableMethod.holdLinkHistory() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_LINK_HISTORY
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", getInstruction(0)),
    )
}

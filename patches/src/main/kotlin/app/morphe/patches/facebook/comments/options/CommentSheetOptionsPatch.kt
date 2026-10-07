/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.PluginSocket
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.isNameTable
import app.morphe.patches.facebook.comments.summaries.switchKeys
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Comment sheet options"

/** The name the comment box's attachment button socket gives itself, in the method that goes through its plugins. */
internal const val BUTTON_SOCKET = "CommentComposerAttachmentButtonSocket"

/** The comment box's GIF button, a kept class name the socket's name table loads. */
internal const val GIF_BUTTON =
    "com.facebook.feedback.comments.plugins.commentcomposer.attachmentbutton.gif.GifAttachmentButtonPlugin"

/** The comment box's sticker button, in the same name table. */
internal const val STICKER_BUTTON =
    "com.facebook.feedback.comments.plugins.commentcomposer.attachmentbutton.sticker.StickerAttachmentButtonPlugin"

/** The name the reaction picker's popup logs itself under, in the method that opens it. */
internal const val DOCK_NAME = "reactions_dock"

/** The reaction picker the Like button's long press opens, a kept class. */
internal const val UFI_DOCK = "Lcom/facebook/feedback/sharedcomponents/reactions/dock/RopeStyleUFIDockView;"

private const val VIEW = "Landroid/view/View;"

internal const val COMMENT_SHEET_OPTIONS = "$EXTENSION_PACKAGE/comments/CommentSheetOptions;"
internal const val HOLDS_BUTTON = "$COMMENT_SHEET_OPTIONS->holdsButton(Ljava/lang/String;)Z"
internal const val SKIP_PICKER = "$COMMENT_SHEET_OPTIONS->skipReactionPicker()Z"

/**
 * Two switches for comments and reactions, both off until turned on.
 *
 * Hide GIF and sticker buttons: the comment box draws its buttons through a plugin socket, the one
 * that names itself [BUTTON_SOCKET] in the method going through its plugins (581
 * `LX/A4X;->A0I`, 580 `LX/AXw;->A02`, 577 `LX/AbP;->A02`). A static (I)String name table turns a
 * plugin's number into its class name (581 `LX/A4X;->A0J`, 580 `LX/AXw;->A03`, 577
 * `LX/AbP;->A03`), and a static check that the socket calls with the plugin's number last decides
 * whether the button shows (581 `LX/2Ap;->A25`, 580 `LX/25t;->A22`, 577 `LX/1xW;->A21`). Unlike the
 * comment summaries' sockets, the check sits in another class, so it's found as the one static
 * boolean the socket calls with an int last and a switch over the table's numbers. The extension
 * goes first in it with the plugin's name, and while the switch is on the GIF and sticker buttons
 * get a no. Photo, mention and every other button stay.
 *
 * Like only: a long press on Like opens the reaction picker through one method that builds
 * [UFI_DOCK] in a popup and logs [DOCK_NAME] (581 `LX/3FO;->A06`, 580 `LX/333;->A06`, 577
 * `LX/34y;->A06`, each an instance (View, View)V method). The extension goes first there, and while
 * the switch is on the method returns before anything opens, so a tap on Like still likes.
 *
 * Off in the default selection.
 */
@Suppress("unused")
val commentSheetOptionsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Comment sheet options",
    description = "Adds two switches under Comments, both off to start. Like only stops a long press on Like from " +
        "opening the reactions, and the other takes the GIF and sticker buttons out of the comment box. A row " +
        "there opens Facebook's own settings, where Reaction preferences can hide reaction counts.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        requireStatusMethod("commentSheetOptions")
        val buttons = buttonSocket(
            holders(GIF_BUTTON),
            holders(BUTTON_SOCKET).flatMap { methodsHolding(it, BUTTON_SOCKET) },
        ) { types -> types.mapNotNull { classDefByOrNull(it) }.associateBy { it.type } }
        val dock = reactionPicker(holders(DOCK_NAME))

        mutableClassDefBy(buttons.check.definingClass).methods.single { it.descriptor() == buttons.check.descriptor() }
            .holdButtons(buttons.table)
        mutableClassDefBy(dock.definingClass).methods.single { it.descriptor() == dock.descriptor() }
            .skipPicker()
        enableStatus("commentSheetOptions")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.holders(string: String) =
    classDefByStrings(string, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

private fun calls(method: Method): List<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (!instruction.opcode.name.startsWith("invoke")) return@mapNotNull null
        (instruction as ReferenceInstruction).reference as? MethodReference
    }

/**
 * The comment box's button socket, from [holders], the classes loading [GIF_BUTTON], and
 * [sockets], the methods loading [BUTTON_SOCKET]. [classes] reads the classes of the types it's
 * given, so the check can be looked at in whichever class holds it.
 *
 * The table is the one name table naming both buttons. The check is the one static boolean method
 * a socket method calls along with the table, with an int last, a switch over the same numbers as
 * the table's and a local register for the hook. Refuses unless there's exactly one of each.
 */
internal fun buttonSocket(
    holders: List<ClassDef>,
    sockets: List<Method>,
    classes: (Set<String>) -> Map<String, ClassDef>,
): PluginSocket {
    val tables = holders.flatMap { methodsHolding(it, GIF_BUTTON) }.filter(::isNameTable).distinctBy { it.descriptor() }
    val table = tables.singleOrNull() ?: refuse("expected one name table naming $GIF_BUTTON, found ${tables.size}")
    if (!holdsString(table, STICKER_BUTTON)) refuse("${table.descriptor()} names the GIF button but not $STICKER_BUTTON")
    val numbers = switchKeys(table).singleOrNull() ?: refuse("${table.descriptor()} has more than one switch")

    val tableCall = table.descriptor()
    val callers = sockets.filter { socket -> calls(socket).any { it.descriptor() == tableCall } }
    if (callers.isEmpty()) refuse("no method naming $BUTTON_SOCKET calls $tableCall")
    val called = callers.flatMap(::calls).filter { call ->
        call.returnType == "Z" && call.parameterTypes.lastOrNull()?.toString() == "I"
    }.associateBy { it.descriptor() }
    val owners = classes(called.values.map { it.definingClass }.toSet())
    val checks = called.keys.mapNotNull { descriptor ->
        owners[called.getValue(descriptor).definingClass]?.methods?.singleOrNull { it.descriptor() == descriptor }
    }.filter { method -> AccessFlags.STATIC.isSet(method.accessFlags) && numbers in switchKeys(method) }
    val check = checks.singleOrNull() ?: refuse(
        "expected one check of $tableCall's buttons, found ${checks.size}: ${checks.joinToString { it.descriptor() }}",
    )
    if (check.localRegisterCount() < 1) refuse("${check.descriptor()} has no local register for the hook")
    return PluginSocket(table, check)
}

/** Whether [method] opens the reaction picker: an instance (View, View)V method building [UFI_DOCK] and logging [DOCK_NAME]. */
internal fun isReactionPicker(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf(VIEW, VIEW)) return false
    if (!holdsString(method, DOCK_NAME)) return false
    return method.implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.NEW_INSTANCE &&
            ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == UFI_DOCK
    } == true
}

/** The one method among [holders], the classes loading [DOCK_NAME], that opens the reaction picker. */
internal fun reactionPicker(holders: List<ClassDef>): Method {
    val pickers = holders.flatMap { methodsHolding(it, DOCK_NAME) }.filter(::isReactionPicker).distinctBy { it.descriptor() }
    val picker = pickers.singleOrNull() ?: refuse(
        "expected one method opening $UFI_DOCK that logs \"$DOCK_NAME\", found ${pickers.size}",
    )
    if (picker.localRegisterCount() < 1) refuse("${picker.descriptor()} has no local register for the hook")
    return picker
}

/**
 * First thing in the button check: get the button's name from [table] with the check's own number,
 * ask the extension, and answer no when it holds that button. Otherwise the check runs from its
 * first instruction. The number is the last register, so the calls take it as a range.
 */
internal fun MutableMethod.holdButtons(table: Method) {
    val number = implementation!!.registerCount - 1
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$number .. v$number }, ${table.descriptor()}
            move-result-object v0
            invoke-static { v0 }, $HOLDS_BUTTON
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}

/** First thing where the reaction picker opens: while the extension says so, return before it does. */
internal fun MutableMethod.skipPicker() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $SKIP_PICKER
            move-result v0
            if-eqz v0, :open
            return-void
        """,
        ExternalLabel("open", getInstruction(0)),
    )
}

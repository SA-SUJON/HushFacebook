/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/*
 * Home's tray pages forward on its own when the filters leave it short (2026-10-30, issue #317). On
 * 585 Home's tray (the feed unit LX/0CYN; renders) hangs a scroll listener on its row (LX/0CYa;, a
 * RecyclerView.OnScrollListener). Its onScrollStateChanged (A07) runs when a drag settles: with the
 * row's last tile near the end of the tray's buckets and the pager (field A01, LX/0DhQ;) saying it
 * has a next page (GnF), it calls the pager's J2o("load_next_page"). Until that page comes the row
 * ends in three gray placeholder tiles. A row the filters cut to one or two buckets can't be
 * dragged, so the placeholders stayed and later pages never loaded.
 *
 * The listener is made a StoriesTrayPaging.Pager, with a method that makes the same pager call, and
 * its onScrolled (A08), which RecyclerView also calls after a layout that changes the tiles in view,
 * hands the extension the listener and the row first thing. The extension asks for the next page
 * after a filtered page settles while the row's end is on screen.
 */

internal const val TRAY_PAGING = "$EXTENSION_PACKAGE/stories/StoriesTrayPaging;"
internal const val TRAY_PAGER = "$EXTENSION_PACKAGE/stories/StoriesTrayPaging\$Pager;"
internal const val WATCH_TRAY = "$TRAY_PAGING->watch(Ljava/lang/Object;Landroid/view/View;)V"
internal const val LOAD_NEXT_PAGE_METHOD = "hushLoadNextPage"

/** The trigger the tray's listener hands its pager to load the next page. */
internal const val LOAD_NEXT_PAGE = "load_next_page"

internal const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"
internal const val LAST_VISIBLE = "findLastVisibleItemPosition"

/**
 * Home's tray's scroll listener: its [stateChanged] check, its [onScrolled], the [pager] field it
 * reads, and the pager's [hasNext] and [loadNext] calls.
 */
internal class TrayPagingAnchors(
    val listener: String,
    val stateChanged: Method,
    val onScrolled: Method,
    val pager: FieldReference,
    val hasNext: MethodReference,
    val loadNext: MethodReference,
)

/** Whether [method] is the listener's settle check: (RecyclerView, int) to void, reading the last tile in view and holding [LOAD_NEXT_PAGE]. */
internal fun isPagingCheck(method: Method): Boolean =
    method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf(RECYCLER_VIEW, "I") &&
        trayBody(method).any { it.stringReference() == LOAD_NEXT_PAGE } &&
        trayBody(method).any { it.methodRef()?.name == LAST_VISIBLE }

/** The onScrolled of [classDef]: its one (RecyclerView, int, int) to void method. */
internal fun scrolledMethod(classDef: ClassDef): Method? =
    classDef.methods.singleOrNull {
        it.returnType == "V" && it.parameterTypes.map { p -> p.toString() } == listOf(RECYCLER_VIEW, "I", "I")
    }

/**
 * Reads Home's tray's scroll listener: the one class outside the extension with a [isPagingCheck]
 * method and an onScrolled, the String call right after [LOAD_NEXT_PAGE] as the pager's load, the
 * field that pager is read from, and the pager's no-argument boolean the check asks first.
 */
internal fun BytecodePatchContext.trayPagingAnchors(): TrayPagingAnchors {
    val listeners = classDefByStrings(LOAD_NEXT_PAGE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { classDef -> classDef.methods.any(::isPagingCheck) && scrolledMethod(classDef) != null }
    val listener = listeners.singleOrPatchException("$PATCH: the tray's scroll listener holding \"$LOAD_NEXT_PAGE\"")
    val check = listener.methods.single(::isPagingCheck)
    val body = trayBody(check)

    val trigger = body.indexOfFirst { it.stringReference() == LOAD_NEXT_PAGE }
    val load = body.drop(trigger + 1).firstOrNull { it.opcode == Opcode.INVOKE_INTERFACE }
        ?: throw PatchException("$PATCH: no pager call after \"$LOAD_NEXT_PAGE\" in ${listener.type}->${check.name}")
    val loadNext = load.methodRef()!!
    if (loadNext.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;") || loadNext.returnType != "V") {
        throw PatchException("$PATCH: ${loadNext.definingClass}->${loadNext.name} doesn't take the trigger alone")
    }
    val pagerRegister = (load as FiveRegisterInstruction).registerC

    val pager = body.take(trigger).mapNotNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.IGET_OBJECT && (it as OneRegisterInstruction).registerA == pagerRegister }
            ?.let { (it as ReferenceInstruction).reference as FieldReference }
    }.filter { it.definingClass == listener.type && it.type == loadNext.definingClass }
        .distinct().singleOrPatchException("$PATCH: the pager field ${listener.type} reads before \"$LOAD_NEXT_PAGE\"")

    val hasNext = body.take(trigger).mapNotNull { it.methodRef() }.firstOrNull {
        it.definingClass == loadNext.definingClass && it.parameterTypes.isEmpty() && it.returnType == "Z"
    } ?: throw PatchException("$PATCH: no has-next-page check on ${loadNext.definingClass} before \"$LOAD_NEXT_PAGE\"")

    return TrayPagingAnchors(listener.type, check, scrolledMethod(listener)!!, pager, hasNext, loadNext)
}

/**
 * Makes the listener a StoriesTrayPaging.Pager whose method asks the pager for the next page when
 * it has one, and hands the extension the listener and its row first thing in onScrolled.
 */
internal fun BytecodePatchContext.pageShortTrays(anchors: TrayPagingAnchors) {
    val listener = mutableClassDefBy(anchors.listener)
    if (TRAY_PAGER !in listener.interfaces) listener.interfaces.add(TRAY_PAGER)
    if (listener.methods.none { it.name == LOAD_NEXT_PAGE_METHOD && it.parameterTypes.isEmpty() }) {
        listener.methods.add(
            ImmutableMethod(
                anchors.listener, LOAD_NEXT_PAGE_METHOD, emptyList(), "V", AccessFlags.PUBLIC.value,
                null, null, MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(0, """
                    iget-object v0, p0, ${anchors.listener}->${anchors.pager.name}:${anchors.pager.type}
                    if-eqz v0, :done
                    invoke-interface { v0 }, ${reference(anchors.hasNext)}
                    move-result v1
                    if-eqz v1, :done
                    const-string v1, "$LOAD_NEXT_PAGE"
                    invoke-interface { v0, v1 }, ${reference(anchors.loadNext)}
                    :done
                    return-void
                """)
            },
        )
    }
    listener.methods.single {
        it.name == anchors.onScrolled.name && it.parameterTypes.map { p -> p.toString() } == listOf(RECYCLER_VIEW, "I", "I")
    }.addInstructions(0, "invoke-static/range { p0 .. p1 }, $WATCH_TRAY")
}

private fun reference(method: MethodReference) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

private fun trayBody(method: Method): List<Instruction> = method.implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.stringReference(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

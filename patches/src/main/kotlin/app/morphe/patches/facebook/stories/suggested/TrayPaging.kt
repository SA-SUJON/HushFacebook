/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
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
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Home's tray pages forward on its own when the filters leave it short (2026-10-30, issue #317). On
 * 585 Home's tray (the feed unit LX/0CYN; renders) hangs a scroll listener on its row (LX/0CYa;, a
 * RecyclerView.OnScrollListener). Its onScrollStateChanged (A07) runs when a drag settles. It reads
 * the last tile in view (the row's mLayout, a LinearLayoutManager, findLastVisibleItemPosition) and
 * the tray's kept buckets (field A03, LX/024Y;, whose A07 is the ImmutableList), skips when that
 * tile is past the bucket count, and otherwise, with the tile among the last buckets and the pager
 * (field A01, LX/0DhQ;) saying it has a next page (GnF), calls the pager's J2o("load_next_page"),
 * then Bdj() when the listener's fetch surface (A02) is set. While the pager has a next page the
 * row ends in three gray placeholder tiles, and a placeholder coming into view loads the page only
 * behind a MobileConfig flag (LX/02fs;->I0l). A row the filters cut to one bucket shows the
 * placeholders past the bucket count, so neither load fired and the placeholders stayed.
 *
 * The listener is made a StoriesTrayPaging.Pager, with methods that read the last tile and the
 * bucket count the same way and make the same pager calls, and its onScrolled (A08), which
 * RecyclerView also calls after a layout that changes the tiles in view, hands the extension the
 * listener and the row first thing. The extension asks for the next page after a filtered page
 * settles while the last kept bucket or a placeholder is in view.
 */

internal const val TRAY_PAGING = "$EXTENSION_PACKAGE/stories/StoriesTrayPaging;"
internal const val TRAY_PAGER = "$EXTENSION_PACKAGE/stories/StoriesTrayPaging\$Pager;"
internal const val WATCH_TRAY = "$TRAY_PAGING->watch(Ljava/lang/Object;Landroid/view/View;)V"
internal const val LOAD_NEXT_PAGE_METHOD = "hushLoadNextPage"
internal const val LAST_TILE_METHOD = "hushLastTile"
internal const val BUCKET_COUNT_METHOD = "hushBucketCount"

/** The trigger the tray's listener hands its pager to load the next page. */
internal const val LOAD_NEXT_PAGE = "load_next_page"

internal const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"
internal const val LAST_VISIBLE = "findLastVisibleItemPosition"

/**
 * Home's tray's scroll listener: its [stateChanged] check, its [onScrolled], the [pager] field it
 * reads, the pager's [hasNext] and [loadNext] calls and the [settled] call after them when the
 * [surface] field is set, the row's [layout] field and the [lastVisible] read on it, and the
 * [holder] field whose [buckets] list the check measures.
 */
internal class TrayPagingAnchors(
    val listener: String,
    val stateChanged: Method,
    val onScrolled: Method,
    val pager: FieldReference,
    val hasNext: MethodReference,
    val loadNext: MethodReference,
    val surface: FieldReference?,
    val settled: MethodReference?,
    val layout: FieldReference,
    val lastVisible: MethodReference,
    val holder: FieldReference,
    val buckets: FieldReference,
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
 * field that pager is read from, the pager's no-argument boolean the check asks first, the String
 * field and no-argument pager call after the load, the row's layout field and its last-tile read,
 * and the last ImmutableList the check reads before the load with the listener field it comes from.
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

    val after = body.drop(trigger + 2)
    val surface = after.firstNotNullOfOrNull { it.fieldRef(Opcode.IGET_OBJECT) }
        ?.takeIf { it.definingClass == listener.type && it.type == "Ljava/lang/String;" }
    val settled = surface?.let {
        after.mapNotNull { it.methodRef() }.firstOrNull {
            it.definingClass == loadNext.definingClass && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
    }

    val layout = body.firstNotNullOfOrNull { it.fieldRef(Opcode.IGET_OBJECT)?.takeIf { field -> field.definingClass == RECYCLER_VIEW } }
        ?: throw PatchException("$PATCH: ${listener.type}->${check.name} doesn't read the row's layout")
    val lastVisible = body.mapNotNull { it.methodRef() }.first { it.name == LAST_VISIBLE }
    if (lastVisible.parameterTypes.isNotEmpty() || lastVisible.returnType != "I") {
        throw PatchException("$PATCH: ${lastVisible.definingClass}->$LAST_VISIBLE isn't ()I")
    }

    val reads = body.take(trigger).mapNotNull { it.fieldRef(Opcode.IGET_OBJECT) }
    val buckets = reads.lastOrNull { it.type == IMMUTABLE_LIST }
        ?: throw PatchException("$PATCH: ${listener.type}->${check.name} reads no bucket list before \"$LOAD_NEXT_PAGE\"")
    val holder = reads.subList(0, reads.lastIndexOf(buckets)).lastOrNull {
        it.definingClass == listener.type && it.type == buckets.definingClass
    } ?: throw PatchException("$PATCH: no ${listener.type} field holds ${buckets.definingClass}")

    return TrayPagingAnchors(
        listener.type, check, scrolledMethod(listener)!!, pager, hasNext, loadNext, surface, settled,
        layout, lastVisible, holder, buckets,
    )
}

/**
 * Makes the listener a StoriesTrayPaging.Pager whose methods ask the pager for the next page when
 * it has one, read the row's last tile in view and count the tray's buckets, and hands the
 * extension the listener and its row first thing in onScrolled.
 */
internal fun BytecodePatchContext.pageShortTrays(anchors: TrayPagingAnchors) {
    val listener = mutableClassDefBy(anchors.listener)
    if (TRAY_PAGER !in listener.interfaces) listener.interfaces.add(TRAY_PAGER)
    val type = anchors.listener
    // Facebook's check follows the load with this call only when the fetch surface is set.
    val settle = if (anchors.surface != null && anchors.settled != null) """
        iget-object v1, p0, $type->${anchors.surface.name}:${anchors.surface.type}
        if-eqz v1, :asked
        invoke-interface { v0 }, ${reference(anchors.settled)}
    """ else ""
    listener.addPagerMethod(LOAD_NEXT_PAGE_METHOD, emptyList(), "Z", 3, """
        iget-object v0, p0, $type->${anchors.pager.name}:${anchors.pager.type}
        if-eqz v0, :none
        invoke-interface { v0 }, ${reference(anchors.hasNext)}
        move-result v1
        if-eqz v1, :none
        const-string v1, "$LOAD_NEXT_PAGE"
        invoke-interface { v0, v1 }, ${reference(anchors.loadNext)}
        $settle
        :asked
        const/4 v0, 0x1
        return v0
        :none
        const/4 v0, 0x0
        return v0
    """)
    val manager = anchors.lastVisible.definingClass
    listener.addPagerMethod(LAST_TILE_METHOD, listOf("Landroid/view/View;"), "I", 4, """
        instance-of v0, p1, $RECYCLER_VIEW
        if-eqz v0, :none
        check-cast p1, $RECYCLER_VIEW
        iget-object v0, p1, $RECYCLER_VIEW->${anchors.layout.name}:${anchors.layout.type}
        instance-of v1, v0, $manager
        if-eqz v1, :none
        check-cast v0, $manager
        invoke-virtual { v0 }, ${reference(anchors.lastVisible)}
        move-result v0
        return v0
        :none
        const/4 v0, -0x1
        return v0
    """)
    listener.addPagerMethod(BUCKET_COUNT_METHOD, emptyList(), "I", 2, """
        iget-object v0, p0, $type->${anchors.holder.name}:${anchors.holder.type}
        if-eqz v0, :none
        iget-object v0, v0, ${anchors.buckets.definingClass}->${anchors.buckets.name}:${anchors.buckets.type}
        if-eqz v0, :none
        invoke-interface { v0 }, Ljava/util/List;->size()I
        move-result v0
        return v0
        :none
        const/4 v0, -0x1
        return v0
    """)
    listener.methods.single {
        it.name == anchors.onScrolled.name && it.parameterTypes.map { p -> p.toString() } == listOf(RECYCLER_VIEW, "I", "I")
    }.addInstructions(0, "invoke-static/range { p0 .. p1 }, $WATCH_TRAY")
}

/** Adds a public method of [name] with [body] to the listener, unless an earlier run of the patch did. */
private fun MutableClass.addPagerMethod(name: String, parameters: List<String>, returns: String, registers: Int, body: String) {
    if (methods.any { it.name == name && it.parameterTypes.map { p -> p.toString() } == parameters }) return
    methods.add(
        ImmutableMethod(
            type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, AccessFlags.PUBLIC.value,
            null, null, MutableMethodImplementation(registers),
        ).toMutable().apply { addInstructions(0, body) },
    )
}

private fun reference(method: MethodReference) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

private fun trayBody(method: Method): List<Instruction> = method.implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldRef(opcode: Opcode): FieldReference? =
    takeIf { it.opcode == opcode }?.let { (it as ReferenceInstruction).reference as? FieldReference }

private fun Instruction.stringReference(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.hold

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val PATCH = "Hold a reel for 2x"

/**
 * A reel you hold plays at double speed until you let go, through the speed-up Facebook's Reels
 * controls already have behind server flags. See ReelHoldAnchors.kt for where the controls decide,
 * and the extension's ReelHold for each answer.
 *
 * The long-press handlers' speed-up flag, the overlay's check of it before it gives a reel its
 * release listener, the release listeners' two flags and the edge check each hand their answer to
 * the extension on its way out, and every touch on a Facebook screen tells the extension when a
 * gesture starts, so the release listener puts the speed back only after a hold.
 *
 * Off in the default selection: while its switch is on, a hold on a reel speeds it up instead of
 * opening Facebook's long-press menu, which is a choice to make. Picked, its switch starts on.
 */
@Suppress("unused")
val holdReelFor2xPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hold a reel for 2x",
    description = "Holding a reel plays it at double speed until you let go. The hold takes the place of " +
        "Facebook's long-press menu, which the reel's more button still opens.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Every anchor is found, and every flag call's answer checked, before anything changes.
        val anchors = findReelHoldAnchors()
        applyReelHoldAnchors(anchors)
        enableStatus("reelHold")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** One method whose flag calls at [calls] hand their answers to [hook]. */
internal class FlagPlan(val method: Method, val hook: String, val calls: List<Int>)

/** What [findReelHoldAnchors] found, for [applyReelHoldAnchors] to change. */
internal class ReelHoldAnchors(
    val config: String,
    val speedUpFlag: String,
    val releaseFlag: String,
    val plans: List<FlagPlan>,
    val edgeCheck: Method,
    val dispatch: Method,
)

/** The long-press handlers, the release listeners, the overlay's check, the edge check and the touch dispatch. */
internal fun BytecodePatchContext.findReelHoldAnchors(): ReelHoldAnchors {
    val handlers = classDefByStrings(SPEED_UP_LOG, StringComparisonType.EQUALS).filter(::isLongPressHandler)
    if (handlers.isEmpty()) refuse("no long-press handler holds \"$SPEED_UP_LOG\" and keeps $DISPATCH_DIRECTLY_FIELD")
    val configs = handlers.mapNotNull(::configType).toSet()
    val config = configs.singleOrNull() ?: refuse("the long-press handlers capture ${configs.size} config types")

    val speedUps = handlers.flatMap { handler -> handler.methods.flatMap { flagCalls(it, config).values } }.toSet()
    val speedUpFlag = speedUps.singleOrNull()
        ?: refuse("expected the long-press handlers to ask $config one flag, found ${speedUps.sorted()}")

    val listeners = mutableListOf<ClassDef>()
    classDefForEach { if (isReleaseListener(it) && configType(it) == config) listeners += it }
    if (listeners.isEmpty()) refuse("no release listener keeps $IN_LONG_PRESS_FIELD and $CONFIG_FIELD")
    val releaseFlags = listeners.flatMap { listener -> listener.methods.flatMap { flagsBefore(it, config, speedUpFlag) } }.toSet()
    val releaseFlag = releaseFlags.singleOrNull()
        ?: refuse("expected the release listeners to ask one flag before $speedUpFlag, found ${releaseFlags.sorted()}")

    val lambdas = (handlers + listeners).map { it.type }.toSet()
    val builders = CONTROL_COMPONENTS.flatMap { classDefByStrings(it, StringComparisonType.EQUALS) }
        .distinctBy { it.type }.flatMap { component -> component.methods.filter { makesOneOf(it, lambdas) } }

    // A call whose answer nothing takes decides nothing (Kotlin leaves one in a long-press handler),
    // so only the calls whose answer the next instruction takes are changed.
    fun plan(method: Method, hook: String, flags: Set<String>): FlagPlan? {
        val calls = flagCalls(method, config).filterValues { it in flags }.keys.filter { answerTakenAt(method, it) != null }
        return if (calls.isEmpty()) null else FlagPlan(method, hook, calls.sorted())
    }
    val handlerPlans = handlers.flatMap { it.methods.mapNotNull { m -> plan(m, LONG_PRESS, setOf(speedUpFlag)) } }
    handlers.firstOrNull { handler -> handlerPlans.none { it.method.definingClass == handler.type } }?.let {
        refuse("the long-press handler ${it.type} takes no answer of $speedUpFlag")
    }
    val listenerPlans = listeners.flatMap { it.methods.mapNotNull { m -> plan(m, RELEASE, setOf(speedUpFlag, releaseFlag)) } }
    listeners.firstOrNull { listener -> listenerPlans.none { it.method.definingClass == listener.type } }?.let {
        refuse("the release listener ${it.type} takes the answer of neither flag")
    }
    val builderPlans = builders.mapNotNull { plan(it, SPEED_UP, setOf(speedUpFlag, releaseFlag)) }

    val edgeChecks = handlers.flatMap { handler -> handler.methods.flatMap { edgeChecksCalled(it, config) } }
        .distinctBy { it.toString() }
    val edgeCall = edgeChecks.singleOrNull()
        ?: refuse("expected the long-press handlers to call one edge check, found ${edgeChecks.size}")
    val edgeCheck = classDefByOrNull(edgeCall.definingClass)?.methods?.singleOrNull { isMethod(it, edgeCall) }
        ?: refuse("the edge check $edgeCall has no body in this build")
    if (edgeCheck.implementation!!.instructions.none { it.opcode == Opcode.RETURN }) refuse("the edge check never returns")

    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuse("this build has no $FRAGMENT_ACTIVITY")
    val dispatch = touchDispatches(activity).singleOrNull()
        ?: refuse("expected $FRAGMENT_ACTIVITY to declare one dispatchTouchEvent($MOTION_EVENT)Z")
    return ReelHoldAnchors(config, speedUpFlag, releaseFlag, handlerPlans + listenerPlans + builderPlans, edgeCheck, dispatch)
}

/**
 * After each flag call's move-result, the extension's answer in its place, in the same register,
 * which the range form names whatever its number; the branch that follows reads it as Facebook's.
 * Before each of the edge check's returns, the same. First in the touch dispatch, the event, which
 * the extension only reads.
 */
internal fun BytecodePatchContext.applyReelHoldAnchors(anchors: ReelHoldAnchors) {
    for (plan in anchors.plans) {
        val mutable = mutableClassDefBy(plan.method.definingClass).findMutableMethodOf(plan.method)
        plan.calls.sortedDescending().forEach { call ->
            val register = mutable.getInstruction<OneRegisterInstruction>(call + 1).registerA
            mutable.addInstructions(
                call + 2,
                """
                    invoke-static/range { v$register .. v$register }, ${plan.hook}
                    move-result v$register
                """,
            )
        }
    }
    val edge = mutableClassDefBy(anchors.edgeCheck.definingClass).findMutableMethodOf(anchors.edgeCheck)
    edge.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
        .asReversed().forEach { index ->
            val register = edge.getInstruction<OneRegisterInstruction>(index).registerA
            edge.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $ANYWHERE
                    move-result v$register
                """,
            )
        }
    val dispatch = mutableClassDefBy(FRAGMENT_ACTIVITY).findMutableMethodOf(anchors.dispatch)
    val event = dispatch.parameterRegister(0)
    dispatch.addInstruction(0, "invoke-static/range { $event .. $event }, $TOUCH")
}

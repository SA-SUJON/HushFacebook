/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors of Open Messenger from the top bar on every declared Facebook build: one tap of the
 * top bar's Messenger icon, with the click listener handing it 0 and the long-click listener 1 in
 * the parameter the hook reads as the long press; one Messenger button handler it calls, which
 * gets that same flag in its own long-press parameter and logs "long_press" only when it's set;
 * and two locals to borrow in each. Nothing else holds both entry points, and no other static
 * method of the handler's shape loads "long_press", which is what the mutation contracts pick the
 * two by. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MessengerIconFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun key(method: MethodReference) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun calls(method: Method, target: Method): Boolean =
        method.implementation?.instructions?.any { it.call?.let(::key) == key(target) } == true

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        return method.implementation!!.registerCount - self - method.parameterTypes.sumOf {
            if (it.toString() == "J" || it.toString() == "D") 2 else 1
        }
    }

    /** The register holding argument [index] of the call [call], all of whose arguments are narrow. */
    private fun argument(call: Instruction, index: Int): Int = when (call) {
        is RegisterRangeInstruction -> call.startRegister + index
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)[index]
        else -> throw AssertionError("not a call: $call")
    }

    /** The last instruction before [at] in [code] that writes [register], or null. */
    private fun lastWrite(code: List<Instruction>, at: Int, register: Int): Instruction? =
        code.subList(0, at).lastOrNull {
            it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
        }

    /** The callers of [target] anywhere in [bundle]. */
    private fun callers(bundle: java.io.File, target: Method): List<Method> =
        FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.methodSection.any { it.definingClass == target.definingClass && it.name == target.name }
        }) { calls(it, target) }

    @Test
    fun `each declared build has one Messenger icon tap and one button handler, both handed the long press`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, REELS_TAB_ENTRY)
                    .flatMap { it.methods }.filter { holdsString(it, REELS_TAB_ENTRY) && holdsString(it, NAVBAR_ENTRY) }
                assertEquals("$name: methods holding both entry points", 1, holders.size)
                val taps = FixtureDex.classesHolding(bundle, REELS_TAB_ENTRY).flatMap { it.methods }.filter(::isIconTap)
                assertEquals("$name: Messenger icon taps", 1, taps.size)
                val tap = taps.single()
                assertTrue("$name: the tap has fewer than two locals", locals(tap) >= 2)

                // The icon's click listener hands the tap 0 in the long-press parameter, and its
                // long-click listener 1.
                val flags = callers(bundle, tap).associate { caller ->
                    val code = caller.implementation!!.instructions.toList()
                    val at = code.indexOfFirst { it.call?.let(::key) == key(tap) }
                    val write = lastWrite(code, at, argument(code[at], TAP_LONG_PRESS))
                    assertEquals("$name: ${caller.definingClass}->${caller.name} hands the tap a long press it didn't set",
                        Opcode.CONST_4, write?.opcode)
                    caller.name + caller.parameterTypes.joinToString("", "(", ")") + caller.returnType to
                        (write as NarrowLiteralInstruction).narrowLiteral
                }
                assertEquals("$name: the tap's callers and the long press each hands it",
                    mapOf("onClick(Landroid/view/View;)V" to 0, "onLongClick(Landroid/view/View;)Z" to 1), flags)

                // The tap calls one button handler, and hands it its own long-press parameter.
                val handlerCalls = buttonHandlerCalls(tap).distinctBy(::key)
                assertEquals("$name: button handlers the tap calls", 1, handlerCalls.size)
                val owner = FixtureDex.classes(bundle, setOf(handlerCalls.single().definingClass)).values.single()
                val handler = resolveStatic(owner, handlerCalls.single())
                    ?: throw AssertionError("$name: ${key(handlerCalls.single())} isn't in its class")
                assertTrue("$name: ${key(handler)} isn't the button handler", isButtonHandler(handler))
                assertTrue("$name: the button handler has fewer than two locals", locals(handler) >= 2)
                val tapCode = tap.implementation!!.instructions.toList()
                val at = tapCode.indexOfFirst { it.call?.let(::key) == key(handler) }
                val handed = lastWrite(tapCode, at, argument(tapCode[at], BUTTON_LONG_PRESS))
                val tapFlag = locals(tap) + TAP_LONG_PRESS
                assertTrue("$name: the tap hands the handler $handed, not its long press v$tapFlag",
                    handed is TwoRegisterInstruction && handed.opcode.name.startsWith("move") && handed.registerB == tapFlag)

                // The handler logs "long_press" only behind a branch on its own long-press parameter.
                val handlerCode = handler.implementation!!.instructions.toList()
                val logged = handlerCode.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == LONG_PRESS
                }
                val handlerFlag = locals(handler) + BUTTON_LONG_PRESS
                assertTrue("$name: the handler logs a long press without asking v$handlerFlag",
                    handlerCode.subList(0, logged).any {
                        it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == handlerFlag
                    })

                // What the contracts pick the handler by: no other static method of its shape
                // loads "long_press".
                val shaped = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.stringSection.any { it == LONG_PRESS } }) {
                    isButtonHandler(it)
                }
                assertEquals("$name: static (Context, FbUserSession, String, Z, Z)V methods loading \"$LONG_PRESS\"",
                    listOf(key(handler)), shaped.map(::key))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Open Messenger from the top bar that need no Facebook build: which methods the
 * anchors take as the Messenger icon's tap and as the Messenger button handler it shares, which
 * they turn down, and the code the hook puts first in both.
 */
class MessengerIconShapesTest {
    private val contextType = "Landroid/content/Context;"
    private val sessionType = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val tapParameters = listOf(
        contextType, sessionType, "Lfixture/Logger;", "Lfixture/Funnel;", "Lfixture/Eligibility;", "Lfixture/Launcher;",
        "Ljava/lang/String;", "Z", "Z", "Z",
    )
    private val handlerCall = ImmutableMethodReference("Lfixture/MessengerButton;", "A00", BUTTON_PARAMETERS, "V")

    private fun string(value: String) = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(value))

    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)

    private fun method(
        name: String,
        parameters: List<String>,
        registers: Int,
        body: List<Instruction>,
        static: Boolean = true,
        returnType: String = "V",
    ): Method = ImmutableMethod(
        "Lfixture/MessengerIcon;",
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
        null,
        null,
        ImmutableMethodImplementation(registers, body, null, null),
    )

    /** The icon's tap: loads [literals], both entry points by default, and hands the tap to [calls]. */
    private fun tap(
        vararg literals: String = arrayOf(NAVBAR_ENTRY, REELS_TAB_ENTRY),
        parameters: List<String> = tapParameters,
        static: Boolean = true,
        returnType: String = "V",
        registers: Int = 18,
        calls: List<Instruction> = emptyList(),
    ) = method("A01", parameters, registers, literals.map(::string) + calls + returnVoid, static, returnType)

    /** The Messenger button handler: loads [literals], "long_press" by default. */
    private fun handler(
        vararg literals: String = arrayOf(LONG_PRESS),
        parameters: List<String> = BUTTON_PARAMETERS,
        static: Boolean = true,
        registers: Int = 25,
    ) = method("A00", parameters, registers, literals.map(::string) + returnVoid, static)

    private fun invoke(opcode: Opcode, reference: MethodReference) =
        ImmutableInstruction35c(opcode, reference.parameterTypes.size, 3, 4, 7, 8, 9, reference)

    @Test
    fun `the tap is the static method over the context and session that loads both entry points`() {
        assertTrue(isIconTap(tap()))
        assertFalse(isIconTap(tap(NAVBAR_ENTRY)))
        assertFalse(isIconTap(tap(REELS_TAB_ENTRY)))
        assertFalse(isIconTap(tap(static = false)))
        assertFalse(isIconTap(tap(returnType = "Z")))
        // Another shape: a method missing the surface, the three flags, or the context in front.
        assertFalse(isIconTap(tap(parameters = tapParameters.dropLast(1))))
        assertFalse(isIconTap(tap(parameters = tapParameters.map { if (it == "Ljava/lang/String;") "Lfixture/Surface;" else it })))
        assertFalse(isIconTap(tap(parameters = listOf(sessionType, contextType) + tapParameters.drop(2))))
        assertFalse(isIconTap(tap(parameters = tapParameters.dropLast(3) + listOf("Z", "I", "Z"))))
    }

    @Test
    fun `the button handler is the static (Context, FbUserSession, String, Z, Z)V that loads long_press`() {
        assertTrue(isButtonHandler(handler()))
        assertFalse(isButtonHandler(handler(literals = arrayOf())))
        assertFalse(isButtonHandler(handler("is_messenger_installed")))
        assertFalse(isButtonHandler(handler(static = false)))
        assertFalse(isButtonHandler(handler(parameters = BUTTON_PARAMETERS.dropLast(1), registers = 24)))
        assertFalse(isButtonHandler(method("A00", BUTTON_PARAMETERS, 25, listOf(string(LONG_PRESS), returnVoid),
            returnType = "Z")))
    }

    @Test
    fun `the tap names the button handler by its static calls of that shape`() {
        val other = ImmutableMethodReference("Lfixture/Strings;", "A0h", listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            "Ljava/lang/String;")
        val calls = listOf(invoke(Opcode.INVOKE_STATIC, other), invoke(Opcode.INVOKE_STATIC, handlerCall))
        assertEquals(listOf(handlerCall.toString()), buttonHandlerCalls(tap(calls = calls)).map { it.toString() })
        // An instance call of the same shape isn't the static handler.
        assertEquals(emptyList<String>(), buttonHandlerCalls(tap(calls = listOf(invoke(Opcode.INVOKE_VIRTUAL, handlerCall))))
            .map { it.toString() })
        assertEquals(emptyList<String>(), buttonHandlerCalls(tap()).map { it.toString() })
    }

    private fun MutableMethod.at(index: Int) = implementation!!.instructions.elementAt(index)

    /**
     * What the hook puts first in [method]: the context and the long-press flag copied down into
     * v0 and v1, the question, and a return while Messenger opened. [context] and [flag] are the
     * registers the two parameters sit in.
     */
    private fun assertHookFirst(method: MutableMethod, own: Int, context: Int, flag: Int) {
        assertEquals(Opcode.MOVE_OBJECT_FROM16, method.at(0).opcode)
        assertEquals(0, (method.at(0) as TwoRegisterInstruction).registerA)
        assertEquals("the hook doesn't hand over the context", context, (method.at(0) as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.MOVE_FROM16, method.at(1).opcode)
        assertEquals(1, (method.at(1) as TwoRegisterInstruction).registerA)
        assertEquals("the hook doesn't hand over the long press", flag, (method.at(1) as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.INVOKE_STATIC, method.at(2).opcode)
        val hook = (method.at(2) as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/facebook/chats/MessengerIcon;", hook.definingClass)
        assertEquals("open", hook.name)
        assertEquals(listOf(contextType, "Z"), hook.parameterTypes.map { it.toString() })
        assertEquals("Z", hook.returnType)
        val call = method.at(2) as FiveRegisterInstruction
        assertEquals(listOf(0, 1), listOf(call.registerC, call.registerD).take(call.registerCount))
        assertEquals(Opcode.MOVE_RESULT, method.at(3).opcode)
        assertEquals(0, (method.at(3) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, method.at(4).opcode)
        assertEquals(Opcode.RETURN_VOID, method.at(5).opcode)
        // The branch lands on the method's own first instruction, so none of it is skipped.
        assertEquals(own + 6, method.implementation!!.instructions.count())
        assertEquals(Opcode.CONST_STRING, method.at(6).opcode)
        assertEquals(6, offsetTarget(method, 4))
    }

    @Test
    fun `the tap asks first with its context and its long-press flag and runs as its own otherwise`() {
        // 18 registers, 10 parameters: p0, the context, is v8 and p8, the long press, is v16.
        val tap = MutableMethod(tap())
        val own = tap.implementation!!.instructions.count()
        tap.openMessengerFirst(TAP_LONG_PRESS)
        assertHookFirst(tap, own, context = 8, flag = 16)
    }

    @Test
    fun `the button handler asks first with its context and its long-press flag`() {
        // 25 registers, 5 parameters: p0 is v20 and p3, the long press, is v23.
        val handler = MutableMethod(handler())
        val own = handler.implementation!!.instructions.count()
        handler.openMessengerFirst(BUTTON_LONG_PRESS)
        assertHookFirst(handler, own, context = 20, flag = 23)
    }

    /** A method with fewer than two locals to borrow is refused by name, before anything goes in. */
    @Test
    fun `a method without two locals to borrow stops the patch`() {
        val tight = MutableMethod(tap(registers = 11))
        val refusal = assertThrows(PatchException::class.java) { tight.openMessengerFirst(TAP_LONG_PRESS) }
        assertTrue(refusal.message, refusal.message!!.contains(ICON_PATCH))
        assertEquals(Opcode.CONST_STRING, tight.at(0).opcode)
        assertEquals(3, tight.implementation!!.instructions.count())
    }

    /** The hook reads a context and a boolean; any other parameter there is a shape it doesn't know. */
    @Test
    fun `a parameter that isn't the long-press flag stops the patch`() {
        val surface = MutableMethod(tap())
        val refusal = assertThrows(PatchException::class.java) { surface.openMessengerFirst(6) }
        assertTrue(refusal.message, refusal.message!!.contains(ICON_PATCH))
        val noContext = MutableMethod(tap(parameters = listOf(sessionType, contextType) + tapParameters.drop(2)))
        assertThrows(PatchException::class.java) { noContext.openMessengerFirst(TAP_LONG_PRESS) }
        assertEquals(3, surface.implementation!!.instructions.count())
        assertEquals(3, noContext.implementation!!.instructions.count())
    }

    /** The instruction index a branch at [index] lands on. */
    private fun offsetTarget(method: MutableMethod, index: Int): Int {
        val instructions = method.implementation!!.instructions.toList()
        val branch = instructions[index] as OffsetInstruction
        var address = 0
        val addresses = instructions.map { val at = address; address += it.codeUnits; at }
        return addresses.indexOf(addresses[index] + branch.codeOffset)
    }
}

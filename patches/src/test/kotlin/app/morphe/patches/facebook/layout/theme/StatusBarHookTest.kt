/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GET_CONTEXT = "Landroid/view/Window;->getContext()Landroid/content/Context;"

private fun Instruction.calls(method: String) = (this as? ReferenceInstruction)?.reference?.toString() == method

private val COPIES = setOf(
    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
)

/**
 * Throws unless [method] starts with the status bar hook and then runs [original], its own body,
 * unchanged: the colour parameter goes through AmoledTheme.statusBar with what [darkCheck] answers
 * for the window's context, and the answer is back in the colour parameter before the method's
 * first instruction reads it. Each value is traced to the instruction that wrote it, so the test
 * holds for any choice of scratch registers.
 */
internal fun assertStatusBarHook(label: String, method: Method, darkCheck: String, original: List<Instruction>) {
    val body = method.implementation!!.instructions.toList()
    val window = method.parameterRegisterNumber(0)
    val colour = method.parameterRegisterNumber(1)

    /** The instruction before [at] that last wrote [register]. The hook runs straight through. */
    fun writer(register: Int, at: Int): Int = (at - 1 downTo 0).firstOrNull {
        body[it].opcode.setsRegister() && (body[it] as? OneRegisterInstruction)?.registerA == register
    } ?: -1

    /** True when [register] holds parameter [parameter] at [at]: it is that register, or a copy of it. */
    fun holds(register: Int, parameter: Int, at: Int): Boolean {
        if (register == parameter && writer(register, at) < 0) return true
        val copy = body.getOrNull(writer(register, at)) ?: return false
        return copy.opcode in COPIES && (copy as TwoRegisterInstruction).registerB == parameter
    }

    val call = body.indexOfFirst { it.calls(STATUS_BAR) }
    assertTrue("$label: nothing calls AmoledTheme.statusBar", call >= 0)
    assertEquals("$label: the hook is all in front of the method's own code", body.size - original.size, call + 2)
    assertEquals("$label: the method's own code changed",
        original.map { it.opcode }, body.drop(call + 2).map { it.opcode })

    val answer = body[call + 1]
    assertEquals("$label: the answer isn't taken", Opcode.MOVE_RESULT, answer.opcode)
    assertEquals("$label: the answer doesn't go back into the colour", colour, (answer as OneRegisterInstruction).registerA)

    val arguments = body[call] as FiveRegisterInstruction
    assertTrue("$label: statusBar doesn't get the colour", holds(arguments.registerC, colour, call))

    val dark = writer(arguments.registerD, call)
    assertTrue("$label: statusBar's second argument isn't the dark check's answer",
        dark > 0 && body[dark].opcode == Opcode.MOVE_RESULT && body[dark - 1].calls(darkCheck))
    val context = writer((body[dark - 1] as FiveRegisterInstruction).registerC, dark - 1)
    assertTrue("$label: the dark check doesn't get the window's context",
        context > 0 && body[context].opcode == Opcode.MOVE_RESULT_OBJECT && body[context - 1].calls(GET_CONTEXT) &&
            holds((body[context - 1] as FiveRegisterInstruction).registerC, window, context - 1))
}

/**
 * The status bar half of the AMOLED theme without a Facebook build: which method of StatusBarUtil
 * takes the hook, what the hook does to the colour it paints, and every shape that stops the patch
 * instead of hooking the wrong method or none.
 */
class StatusBarHookTest {
    private val darkCheck = "Lfixture/Resolver;->dark(Landroid/content/Context;)Z"

    /** Reads the window's colour cache, then paints the colour in parameter 1. */
    private val paints = """
        sget-object v0, $STATUS_BAR_UTIL->colours:Ljava/util/WeakHashMap;
        invoke-virtual/range { p0 .. p1 }, $SET_STATUS_BAR_COLOR
        return-void
    """

    /** Sets the bar's light or dark icons and paints nothing. */
    private val iconsOnly = """
        invoke-virtual/range { p0 .. p0 }, Landroid/view/Window;->getDecorView()Landroid/view/View;
        return-void
    """

    private fun method(
        name: String,
        registers: Int,
        smali: String,
        parameters: List<String> = listOf("Landroid/view/Window;", "I"),
        static: Boolean = true,
    ): Method = MutableMethod(
        ImmutableMethod(
            STATUS_BAR_UTIL, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
            null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }.let(ImmutableMethod::of)

    private fun statusBarUtil(vararg methods: Method) =
        ImmutableClassDef(STATUS_BAR_UTIL, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods.toList())

    /** Runs the hook over [methods] and answers each method's body afterwards, by name. */
    private fun hook(vararg methods: Method): Map<String, Method> {
        val context = PatchContexts.of(listOf(statusBarUtil(*methods)))
        with(context) { hookStatusBarColour(darkCheck) }
        return context.mutableClassDefBy(STATUS_BAR_UTIL).methods.associateBy { it.name }
    }

    private fun Method.body() = implementation!!.instructions.toList()

    @Test
    fun `the painter takes the hook first thing and paints what the extension answers`() {
        val painter = method("paint", 13, paints)
        val icons = method("icons", 5, iconsOnly)
        val hooked = hook(icons, painter)

        assertStatusBarHook("paint", hooked.getValue("paint"), darkCheck, painter.body())
        assertEquals("the method that paints nothing is left alone",
            icons.body().map { it.opcode }, hooked.getValue("icons").body().map { it.opcode })
    }

    /** `invoke-static` names its registers in four bits; parameters past v15 are copied down first. */
    @Test
    fun `a painter whose parameters sit above v15 still takes it`() {
        val painter = method("paint", 20, paints)
        assertStatusBarHook("paint", hook(painter).getValue("paint"), darkCheck, painter.body())
    }

    @Test
    fun `a second painter stops the patch`() {
        val refused = assertThrows(PatchException::class.java) {
            hook(method("paint", 13, paints), method("paintAgain", 13, paints))
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("2 static (Window, int) methods"))
    }

    /** Negative controls: the painter moved to an instance method, or takes a boxed colour. */
    @Test
    fun `a painter of another shape stops the patch`() {
        for (painter in listOf(
            method("paint", 13, paints, static = false),
            method("paint", 13, paints, parameters = listOf("Landroid/view/Window;", "Ljava/lang/Integer;")),
            method("paint", 13, iconsOnly),
        )) {
            val refused = assertThrows(PatchException::class.java) { hook(painter) }
            assertTrue(refused.message, refused.message.orEmpty().contains("0 static (Window, int) methods"))
        }
    }

    @Test
    fun `a painter with one local stops the patch and keeps its body`() {
        val context = PatchContexts.of(listOf(statusBarUtil(method("paint", 3, paints))))
        val refused = assertThrows(PatchException::class.java) { with(context) { hookStatusBarColour(darkCheck) } }
        assertTrue(refused.message, refused.message.orEmpty().contains("has 1 local register(s), needs 2"))
        assertEquals("nothing went in", 3, context.mutableClassDefBy(STATUS_BAR_UTIL).methods.single().body().size)
    }
}

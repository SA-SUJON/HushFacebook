/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.metaai

import app.morphe.ExtensionDex
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Hide Meta AI questions under posts that need no Facebook build: the names the
 * extension checks against the patch's and the calls the patch writes into it, the shape of the
 * pill socket's default way of drawing a pill the patch accepts, the shapes it refuses, and the
 * hook it puts there.
 */
class HideMetaAiQuestionsShapesTest {
    private val key = "0x427f982"
    private val equals = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"
    private val getter = "Lfixture/Tree;->$TYPE_GETTER(I)Ljava/lang/String;"

    private fun method(body: String, returnType: String = "Ljava/lang/Object;") = MutableMethod(
        ImmutableMethod(
            "Lfixture/PillSocket;", "A06", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            returnType, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null, null, ImmutableMethodImplementation(16, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /**
     * The default way as 577 and 580 lay it out: a pill with no icon of its own gets Meta AI's
     * when its type is meta_ai, every icon path joins at `:merge`, and there the type is read
     * again with the same key from the same tree and compared with stars first. The tree is in
     * v11. [metaAiIcon] and [ownIcon] end the two icon paths. [extra] goes after the last of
     * them, where nothing reaches it.
     */
    private fun socket(
        metaAiName: String = META_AI_TYPE,
        metaAiKey: String = "const v1, $key",
        starsKey: String = "const v9, $key",
        starsTree: Int = 11,
        intoStars: String = "",
        metaAiIcon: String = "goto :merge",
        ownIcon: String = "goto :merge",
        extra: String = "",
        returnType: String = "Ljava/lang/Object;",
    ) = method(
        """
            move-object v11, v15
            $intoStars
            if-eqz v11, :no_icon
            :merge
            $starsKey
            invoke-virtual { v$starsTree, v9 }, $getter
            move-result-object v1
            :stars
            const-string v8, "$STARS_TYPE"
            invoke-virtual { v8, v1 }, $equals
            move-result v1
            const-string v0, "the pill"
            return-object v0
            :no_icon
            $metaAiKey
            invoke-virtual { v11, v1 }, $getter
            move-result-object v2
            const-string v1, "$metaAiName"
            invoke-virtual { v1, v2 }, $equals
            move-result v1
            if-eqz v1, :uri
            const-string v13, "Meta AI's icon"
            $metaAiIcon
            :uri
            const-string v13, "the pill's own icon"
            $ownIcon
            $extra
        """,
        returnType,
    )

    private fun compare(name: String, keyRegister: Int, typeRegister: Int, nameRegister: Int) = """
        const v$keyRegister, $key
        invoke-virtual { v11, v$keyRegister }, $getter
        move-result-object v$typeRegister
        const-string v$nameRegister, "$name"
        invoke-virtual { v$nameRegister, v$typeRegister }, $equals
        move-result v$nameRegister
        return-object v$typeRegister
    """

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun indexOfString(code: List<Instruction>, string: String) =
        code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string }

    /** Where the Meta AI icon's path returns in [socket] when it returns instead of joining. */
    private fun metaAiReturn() = socket(metaAiIcon = "return-object v13").body()
        .indexOfFirst { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA == 13 }

    @Test
    fun `the extension names Meta AI's plugin and type as the patch does`() {
        assertEquals("MetaAiQuestions.META_AI_PILL", META_AI_PILL, ExtensionDex.stringConstant(META_AI_QUESTIONS, "META_AI_PILL"))
        assertEquals("MetaAiQuestions.META_AI_TYPE", META_AI_TYPE, ExtensionDex.stringConstant(META_AI_QUESTIONS, "META_AI_TYPE"))
    }

    /** The default way's hook was never reached on a phone, so a renamed method would only show there. */
    @Test
    fun `every call the patch writes is in the extension`() {
        val declared = ExtensionDex.classDef(META_AI_QUESTIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$META_AI_QUESTIONS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (call in listOf(KEEP, DROPS_DEFAULT_PILL)) {
            assertTrue("MetaAiQuestions declares no public static $call: $declared", call in declared)
        }
    }

    @Test
    fun `the default way's hook point is the stars compare of the type the meta_ai compare reads`() {
        val method = socket()
        val pill = defaultPill(method)
        assertEquals(indexOfString(method.body(), STARS_TYPE), pill.index)
        assertEquals("the type's register and the name's", 1 to 8, pill.typeRegister to pill.freeRegister)
    }

    /** Each shape changes one thing from [socket], and has to be refused for that thing. */
    @Test
    fun `a default way the patch can't read for sure is refused`() {
        val shapes = mapOf(
            "no meta_ai compare" to (socket(metaAiName = "meta_ai_v2") to "with \"$META_AI_TYPE\" in Lfixture/PillSocket;->A06, found 0"),
            "two meta_ai compares" to (socket(extra = compare(META_AI_TYPE, 1, 2, 1)) to "with \"$META_AI_TYPE\" in Lfixture/PillSocket;->A06, found 2"),
            "a meta_ai key no literal loads" to (socket(metaAiKey = "move v1, v3") to "isn't read with a literal key"),
            "the stars compare reading another key" to (socket(starsKey = "const v9, 0x1") to "with \"$STARS_TYPE\" in Lfixture/PillSocket;->A06, found 0"),
            "the stars compare reading another tree" to (socket(starsTree = 12) to "with \"$STARS_TYPE\" in Lfixture/PillSocket;->A06, found 0"),
            "two stars compares" to (socket(extra = compare(STARS_TYPE, 9, 1, 8)) to "with \"$STARS_TYPE\" in Lfixture/PillSocket;->A06, found 2"),
            "a jump straight to the stars name" to (socket(intoStars = "if-nez v11, :stars") to "not only through the read of the type"),
            "a meta_ai pill returned before the stars compare" to
                (socket(metaAiIcon = "return-object v13") to "can be returned at [${metaAiReturn()}] before"),
            "no way on from the meta_ai compare to the stars compare" to
                (socket(metaAiIcon = "throw v13", ownIcon = "throw v13") to "nothing after the \"$META_AI_TYPE\" compare reaches"),
            "a socket that returns no object" to (socket(returnType = "Z") to "returns Z"),
        )
        for ((shape, case) in shapes) {
            val (method, reason) = case
            val refusal = assertThrows(shape, PatchException::class.java) { defaultPill(method) }
            val message = refusal.message!!
            assertTrue("$shape: $message", message.startsWith("$PATCH: ") && reason in message)
        }
    }

    /**
     * The hook goes in right after the type's read: the type goes to the extension, its answer
     * into the name's register, a yes returns no pill, and a no goes on to the stars compare.
     */
    @Test
    fun `the hook hands the type over and returns no pill on a yes`() {
        val method = socket()
        val pill = defaultPill(method)
        val before = method.body()
        method.hookDefaultPill(pill)

        val after = method.body()
        assertEquals(before.size + 5, after.size)
        val at = pill.index
        val call = after[at]
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        assertEquals(DROPS_DEFAULT_PILL, (call as ReferenceInstruction).reference.toString())
        assertEquals("the type handed over", 1 to 1, (call as FiveRegisterInstruction).registerCount to call.registerC)
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
            after.subList(at + 1, at + 5).map { it.opcode })
        assertEquals("every register the hook writes", listOf(8, 8, 8, 8),
            after.subList(at + 1, at + 5).map { (it as OneRegisterInstruction).registerA })
        assertEquals("no pill", 0, (after[at + 3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(STARS_TYPE, ((after[at + 5] as ReferenceInstruction).reference as StringReference).string)

        val flow = ControlFlow.of(method)
        assertEquals("the read goes to the hook", listOf(at), flow.normal[at - 1])
        assertEquals("a no goes on to the stars compare", setOf(at + 3, at + 5), flow.normal[at + 2].toSet())
        assertEquals("only the hook's no reaches the stars compare", listOf(at + 2),
            flow.normal.indices.filter { at + 5 in flow.normal[it] })
    }
}

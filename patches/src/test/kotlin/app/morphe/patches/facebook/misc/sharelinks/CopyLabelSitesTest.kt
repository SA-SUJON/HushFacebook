/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where Sanitize sharing links finds a link with Facebook's copy labels, `s` and then `fs`, and the
 * call it puts right after each one. 582 adds them after the tracker has answered, so Copy link on a
 * reel kept them until each labelled link was passed through as well.
 */
class CopyLabelSitesTest {
    private val builder = "Landroid/net/Uri\$Builder;"
    private val sanitize = "Lapp/morphe/extension/facebook/misc/LinkCleaner;->sanitizeShared(Ljava/lang/String;)Ljava/lang/String;"

    private fun smali(body: String) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Links;", "link", listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(5, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private val start = """
        invoke-static {p0}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
        move-result-object v0
        invoke-virtual {v0}, Landroid/net/Uri;->buildUpon()$builder
        move-result-object v0
    """.trimIndent()

    private val sourceLabel = """
        const-string v1, "s"
        invoke-virtual {v0, v1, v2}, $builder->appendQueryParameter(Ljava/lang/String;Ljava/lang/String;)$builder
        move-result-object v0
    """.trimIndent()

    private val formatLabel = """
        const-string v1, "fs"
        const-string v2, "e"
        invoke-static {v0, v1, v2}, Lfixture/Outlined;->append(${builder}Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
        move-result-object v3
        return-object v3
    """.trimIndent()

    @Test
    fun `the link with both labels goes through the extension right after the helper answers`() {
        val method = smali("$start\n$sourceLabel\n$formatLabel")
        val sites = copyLabelSites(method)
        assertEquals(listOf(10), sites)

        method.sanitizeAfter(sites)
        val code = method.implementation!!.instructions.toList()
        assertEquals(14, code.size)
        val call = code[11]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(sanitize, (call as ReferenceInstruction).reference.toString())
        assertEquals(listOf(3, 1), listOf((call as RegisterRangeInstruction).startRegister, call.registerCount))
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, 3), listOf(code[12].opcode, (code[12] as OneRegisterInstruction).registerA))
        assertEquals(listOf(Opcode.RETURN_OBJECT, 3), listOf(code[13].opcode, (code[13] as OneRegisterInstruction).registerA))
    }

    @Test
    fun `a Uri the next call turns into a string counts`() {
        val method = smali("""
            $start
            $sourceLabel
            const-string v1, "fs"
            const-string v2, "e"
            invoke-static {v0, v1, v2}, Lfixture/Outlined;->build(${builder}Ljava/lang/String;Ljava/lang/String;)Landroid/net/Uri;
            move-result-object v0
            invoke-virtual {v0}, Landroid/net/Uri;->toString()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)
        assertEquals(listOf(12), copyLabelSites(method))
    }

    @Test
    fun `fs without the s label before it is left alone`() {
        assertEquals(emptyList<Int>(), copyLabelSites(smali("$start\n$formatLabel")))
    }

    @Test
    fun `a branch between the labels and the string ends the search`() {
        val method = smali("""
            $start
            $sourceLabel
            const-string v1, "fs"
            const-string v2, "e"
            if-eqz v2, :done
            invoke-static {v0, v1, v2}, Lfixture/Outlined;->append(${builder}Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v3
            :done
            return-object v3
        """)
        assertEquals(emptyList<Int>(), copyLabelSites(method))
    }
}

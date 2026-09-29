/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The family-caller hook against each declared Facebook build: the same evaluator UserValuesProvider
 * reaches for its "same key" check, found the way the patch finds it, gets the extension call at its
 * top and keeps Facebook's own body after it.
 *
 * The evaluator's class and name are Redex's and differ between builds, so both the patch and this
 * test reach it through the real-named delegate, not by a fixed name.
 */
class FamilyTrustFixtureTest {
    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()

    @Test
    fun eachDeclaredBuildAsksTheExtensionAtTheTopOfTheCallerCheck() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val delegate = checkNotNull(FixtureDex.classes(bundle, setOf(TRUSTED_CALLER_DELEGATE))[TRUSTED_CALLER_DELEGATE]) {
                    "${bundle.name}: no $TRUSTED_CALLER_DELEGATE"
                }

                // The evaluator the delegate's two boolean checks share, resolved the way the patch does.
                val evaluator = delegate.methods
                    .filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }
                    .mapNotNull { it.trustEvaluatorCall() }
                    .distinct()
                    .also { assertEquals("${bundle.name}: one shared evaluator", 1, it.size) }
                    .single()

                val pool = FixtureDex.classes(bundle, setOf(TRUSTED_CALLER_DELEGATE, evaluator.definingClass))
                val context = PatchContexts.of(pool.values)
                context.trustSameKeyFamilyCallers()

                val patched = context.mutableClassDefBy(evaluator.definingClass).methods.single {
                    it.name == evaluator.name && it.returnType == evaluator.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == evaluator.parameterTypes.map(CharSequence::toString)
                }
                val body = patched.implementation!!.instructions.toList()

                assertEquals("${bundle.name}: the context is copied down first", Opcode.MOVE_OBJECT_FROM16, body[0].opcode)
                assertEquals("${bundle.name}: the extension is asked", Opcode.INVOKE_STATIC, body[1].opcode)
                assertEquals("${bundle.name}: it's the family-caller call", ACCEPT_CALL, body[1].reference())
                assertEquals("${bundle.name}: it branches on the answer", Opcode.IF_EQZ, body[3].opcode)
                // A true answer returns before any of Facebook's own code runs.
                assertTrue("${bundle.name}: a true answer returns early",
                    body.take(6).any { it.opcode == Opcode.RETURN })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

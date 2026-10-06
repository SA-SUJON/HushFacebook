/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The comment pill on each declared build: one static (I)String table names the Reels ad button's
 * plugin, its class has one check of whether a plugin's button shows, and after the hook that check
 * asks the extension first with the table's answer for its own number.
 *
 * Read from 581 (2026-10-06): the table is `LX/A38;->A0G`, the check `LX/A38;->A0H`. None of those
 * names is used here.
 */
class AdPillsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    @Before
    @After
    fun forgetTheLastMatch() = AdPillNamesFingerprint.clearMatch()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    @Test
    fun `the comment pill's check asks about an ad's button first, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetTheLastMatch()
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, REELS_AD_PILL).filter { owner ->
                    owner.methods.any { method ->
                        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/String;" &&
                            method.parameterTypes.map(CharSequence::toString) == listOf("I") && holdsString(method, REELS_AD_PILL)
                    }
                }
                assertEquals("$name: one class holds the pill's name table", 1, owners.size)
                val owner = ImmutableClassDef.of(owners.single())
                val table = owner.methods.single { it.returnType == "Ljava/lang/String;" && holdsString(it, REELS_AD_PILL) }
                val check = owner.methods.filter(::isAdPillCheck).also {
                    assertEquals("$name: one pill check beside the table", 1, it.size)
                }.single()
                val before = check.implementation!!.instructions.toList()

                val context = PatchContexts.of(listOf(owner))
                with(context) { holdAdPills() }

                val after = context.mutableClassDefBy(owner.type).methods
                    .single { MethodUtil.methodSignaturesMatch(it, check) }.implementation!!.instructions.toList()
                val plugin = check.implementation!!.registerCount - 1
                assertEquals("$name: the table is asked first", "${table.definingClass}->${table.name}(I)Ljava/lang/String;", after[0].called())
                assertEquals("$name: for the check's own number", plugin, (after[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: then the extension", HOLDS_AD_PILL, after[2].called())
                assertEquals("$name: which can answer no", Opcode.RETURN, after[6].opcode)
                assertEquals("$name: and the check is otherwise as it was", before.size + 7, after.size)
                assertEquals("$name: starting with its switch", before[0].opcode, after[7].opcode)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

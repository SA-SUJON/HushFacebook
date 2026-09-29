/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredmarketplace

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide sponsored Marketplace listings' answer hooks on every Facebook build the bundle declares: the
 * Tigon callbacks of Facebook's Networking module hand a response's text to JavaScript once in
 * pieces and once whole, each time as the result of a toString() the hook can follow, with the
 * request's state in a register; sendRequest builds that state with the tracking name it read, which
 * the state's constructor keeps in one String field; and the patch, run on the callbacks, reads that
 * field and asks the extension right where the text lands, leaving the rest of each method as it
 * was. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
/** Every class this class's code calls a method of or reads a field of. */
internal fun ClassDef.referencedClasses(): Set<String> = methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is MethodReference -> reference.definingClass
                is FieldReference -> reference.definingClass
                else -> null
            }
        }
    }.toSet()

class MarketplaceResponseFixtureTest {
    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.parameters() = parameterTypes.map { it.toString() }

    @Test
    fun `each declared build hands a response's text to JavaScript the way the patch reads it, and the patch goes in`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val callbacks = FixtureDex.classes(bundle, setOf(CALLBACKS))[CALLBACKS]
                assertNotNull("$name: no $CALLBACKS", callbacks)
                callbacks!!
                val around = FixtureDex.classes(bundle, callbacks.referencedClasses())
                val lookup = { reference: MethodReference ->
                    around[reference.definingClass]?.methods?.firstOrNull { it.name == reference.name && it.parameters() == reference.parameterTypes.map(CharSequence::toString) }
                }

                val handOffs = textHandOffs(callbacks, lookup)
                assertEquals("$name: pieces of text handed on", 1, handOffs.count { !it.whole })
                assertEquals("$name: whole texts handed on", 1, handOffs.count { it.whole })
                assertEquals("$name: the pieces go on as they come in", "onBody", handOffs.single { !it.whole }.method.name)
                assertEquals("$name: the whole text goes on at the end", "onEOM", handOffs.single { it.whole }.method.name)
                val stateType = handOffs.map { it.stateType }.distinct().singleOrNull()
                assertNotNull("$name: the two hand-offs read different request states: $handOffs", stateType)

                val owners = FixtureDex.classesHolding(bundle, NETWORKING_TAG)
                val send = owners.flatMap { owner -> owner.methods.filter(::isSendRequest) }.single()
                val tracking = trackingField(send, stateType!!, lookup)
                assertNotNull("$name: sendRequest builds $stateType with no field keeping the tracking name", tracking)
                tracking!!
                assertEquals("$name: the field's class", stateType, tracking.definingClass)
                assertEquals("$name: the field's type", "Ljava/lang/String;", tracking.type)

                val classes = (listOf(callbacks, ExtensionDex.classDef(SETTINGS_STATUS)) + around.values + owners)
                    .associateBy { it.type }.values
                val context = PatchContexts.of(classes)
                hideSponsoredMarketplaceListingsPatch.execute(context)
                for (handOff in handOffs) {
                    val original = handOff.method
                    val patched = context.mutableClassDefBy(CALLBACKS).methods.single {
                        it.name == original.name && it.parameters() == original.parameters()
                    }.body()
                    val at = handOff.landed + 1
                    val read = patched[at]
                    assertEquals("$name ${original.name}: the tracking name's read", Opcode.IGET_OBJECT, read.opcode)
                    assertEquals("$name ${original.name}: what it reads", tracking.toString(),
                        (read as ReferenceInstruction).reference.toString())
                    assertEquals("$name ${original.name}: from the state", handOff.state, (read as TwoRegisterInstruction).registerB)
                    val borrowed = read.registerA
                    assertTrue("$name ${original.name}: borrowed v$borrowed", borrowed != handOff.text && borrowed != handOff.state)
                    val asks = patched[at + 1]
                    assertEquals("$name ${original.name}: the call", if (handOff.whole) RESPONSE_WHOLE else RESPONSE_PIECE,
                        (asks as ReferenceInstruction).reference.toString())
                    assertEquals("$name ${original.name}: what the call reads",
                        if (handOff.whole) listOf(handOff.text, borrowed) else listOf(handOff.text, borrowed, handOff.state),
                        asks.namedRegisters())
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, patched[at + 2].opcode)
                    assertEquals("$name ${original.name}: where the answer goes", handOff.text,
                        (patched[at + 2] as OneRegisterInstruction).registerA)
                    assertEquals("$name ${original.name}: the rest of it", original.body().map { it.opcode },
                        patched.filterIndexed { index, _ -> index !in at..at + 2 }.map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

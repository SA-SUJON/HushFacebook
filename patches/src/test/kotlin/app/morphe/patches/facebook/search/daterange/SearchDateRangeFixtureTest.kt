/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.daterange

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search date range on every Facebook build the bundle declares, both ABIs of each. One method
 * holds the filter tap mutation, reads a filter's kind through an interface call answering the enum
 * that holds all three kinds, and then reads the bottom sheet flag with a mobile config call tested
 * with if-nez. The filter's interface keeps the getName the extension reads by reflection. Patched,
 * the flag's answer goes through the extension first and the kind goes through it with the filter,
 * and the rest of Facebook's code stays as it was.
 */
class SearchDateRangeFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun tap(bundle: File) {
        val holders = FixtureDex.classesHolding(bundle, TAP_FILTER).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        // The kind enum lives in another dex of the bundle, so every class the tap handlers name is fetched. String and
        // the other framework types they answer stay out of the pool, as they are out of Facebook's, and the patch's
        // own lookup has to pass over them.
        val named = holders.flatMap { methodsHolding(it, TAP_FILTER) }.flatMap { method ->
            method.implementation!!.instructions
                .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType }
        }.filter { it.startsWith("L") }.toSet()
        val found = PatchContexts.of((holders + FixtureDex.classes(bundle, named).values).distinctBy { it.type })
            .kindReads(holders)
        assertEquals("${bundle.name}: one method reads a filter's kind and then the flag, found ${found.map { it.first.descriptor() }}",
            1, found.size)
        val (method, read) = found.single()
        val where = "${bundle.name}: ${method.descriptor()}"
        val original = method.implementation!!.instructions.toList()

        // The kind is what Facebook sends on to its screens: the ordinal of that register is read.
        val ordinal = original.drop(read.flagResult).any { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL && (instruction as ReferenceInstruction).reference.let {
                it is MethodReference && it.name == "ordinal" && it.definingClass == "Ljava/lang/Enum;"
            }
        }
        assertTrue("$where reads the kind's ordinal after the flag", ordinal)

        // The filter's interface keeps getName(), which the extension reads by reflection.
        val filterInterface = ((original[read.call] as ReferenceInstruction).reference as MethodReference).definingClass
        val filter = FixtureDex.classes(bundle, setOf(filterInterface)).values.single()
        assertTrue("$where: the filter interface ${filter.type} keeps a getName()String",
            filter.methods.any { it.name == "getName" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" })

        val owner = FixtureDex.classes(bundle, setOf(method.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val mutable = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == method.descriptor() }
        mutable.hookFlag(read)
        mutable.hookKind(read)
        val patched = mutable.implementation!!.instructions.toList().filter { it.opcode != Opcode.NOP }
        val stock = original.filter { it.opcode != Opcode.NOP }
        assertEquals("$where gains the kind's three and the flag's two instructions", stock.size + 5, patched.size)
        val atKind = read.call + 1
        assertEquals("$where: the kind is handed over", KIND, (patched[atKind + 1] as ReferenceInstruction).reference.toString())
        assertEquals("$where: its answer goes back to the kind's register", read.kind,
            (patched[atKind + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: and is cast to the kind", Opcode.CHECK_CAST, patched[atKind + 3].opcode)
        assertEquals("$where: the flag is handed over", BOTTOM_SHEET,
            (patched[read.flagResult + 4] as ReferenceInstruction).reference.toString())
        assertEquals("$where: the rest of Facebook's code stays",
            stock.map { it.opcode },
            patched.filterIndexed { index, _ -> index !in (atKind + 1..atKind + 3) && index !in (read.flagResult + 4..read.flagResult + 5) }
                .map { it.opcode })
    }

    @Test
    fun `each declared build reads a filter's kind and then the sheet flag in one place and hooks both`() = bundles(::tap)
}

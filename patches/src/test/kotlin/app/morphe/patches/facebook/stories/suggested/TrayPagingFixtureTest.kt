/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #317: on every declared build one scroll listener outside the extension holds
 * "load_next_page" in a (RecyclerView, int) settle check that reads the last tile in view, and has
 * an onScrolled. The patch reads its pager field and the pager's has-next and load calls, makes it
 * a StoriesTrayPaging.Pager whose method makes those calls on that field, and hands the extension
 * the listener and its row first thing in onScrolled.
 */
class TrayPagingFixtureTest {
    /** The listener, its pager field and the pager's has-next and load methods, per build and then by the bundle's ABI. */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to listOf("LX/0CYa;", "A01", "GnF", "J2o"),
            "armeabi-v7a" to listOf("LX/0CYd;", "A01", "GnD", "J2m"),
        ),
    )

    private fun <T> Map<String, Map<String, T>>.pin(version: String, bundle: File): T =
        getValue(version)[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
            ?: throw AssertionError("${bundle.name} has no pin")

    private fun body(method: Method): List<Instruction> = method.implementation!!.instructions.toList()

    private fun call(instruction: Instruction) = (instruction as ReferenceInstruction).reference as MethodReference

    private fun reference(instruction: Instruction): String {
        val call = call(instruction)
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    @Test
    fun `each declared build's tray listener asks its pager for the next page on the extension's word`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expected.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val classes = FixtureDex.classesHolding(bundle, LOAD_NEXT_PAGE)
                val context = PatchContexts.of(classes + ExtensionDex.classDef(TRAY_PAGING))
                val anchors = with(context) { trayPagingAnchors() }
                val (listener, field, hasNext, loadNext) = expected.pin(version, bundle)
                assertEquals("${bundle.name}: the listener", listener, anchors.listener)
                assertEquals("${bundle.name}: the pager field", field, anchors.pager.name)
                assertEquals("${bundle.name}: the pager's has-next", hasNext, anchors.hasNext.name)
                assertEquals("${bundle.name}: the pager's load", loadNext, anchors.loadNext.name)
                assertEquals("${bundle.name}: both calls on the field's type", anchors.pager.type, anchors.loadNext.definingClass)

                val scrolled = body(anchors.onScrolled)
                with(context) { pageShortTrays(anchors) }
                val mutable = with(context) { mutableClassDefBy(anchors.listener) }
                assertTrue("${bundle.name}: the listener is a Pager", TRAY_PAGER in mutable.interfaces)

                val onScrolled = mutable.methods.single { it.name == anchors.onScrolled.name && scrolledMethod(mutable) == it }
                val hooked = body(onScrolled)
                assertEquals("${bundle.name}: one instruction in onScrolled", scrolled.size + 1, hooked.size)
                assertEquals(WATCH_TRAY, reference(hooked[0]))
                val watch = hooked[0] as RegisterRangeInstruction
                val registers = onScrolled.implementation!!.registerCount
                assertEquals("${bundle.name}: the listener and its row", (registers - 4) to 2, watch.startRegister to watch.registerCount)
                assertEquals("${bundle.name}: Facebook's own code follows", scrolled.map { it.opcode }, hooked.drop(1).map { it.opcode })

                val load = mutable.methods.single { it.name == LOAD_NEXT_PAGE_METHOD }
                assertTrue("${bundle.name}: an interface method", load.parameterTypes.isEmpty() && load.returnType == "V")
                val made = body(load)
                assertEquals(Opcode.IGET_OBJECT, made[0].opcode)
                val read = (made[0] as ReferenceInstruction).reference as FieldReference
                assertEquals("${bundle.name}: reads the pager field", listOf(listener, field, anchors.pager.type),
                    listOf(read.definingClass, read.name, read.type))
                val calls = made.filter { it.opcode == Opcode.INVOKE_INTERFACE }
                assertEquals("${bundle.name}: has-next, then load", listOf(hasNext, loadNext), calls.map { call(it).name })
                assertEquals(LOAD_NEXT_PAGE, made.firstNotNullOf { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string })
                val trigger = calls.last() as FiveRegisterInstruction
                assertEquals("${bundle.name}: the pager and its trigger", 2, trigger.registerCount)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

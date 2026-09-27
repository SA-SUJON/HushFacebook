/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap to play's hooks on every Facebook build the bundle declares: FbGrootPlayer's play, its
 * inner pause and its bind, each there once; the older player's play and pause, once each; the
 * trigger enum naming every trigger the extension's rule reads; the one Autoplay settings checker
 * and its one reader of the ON, OFF, WIFI_ONLY and DEFAULT enum; and FbFragmentActivity's touch
 * dispatch. Then the patch itself, run on those classes, with each call first where it belongs and
 * reading the registers the method keeps its arguments in. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TapToPlayFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    @Test
    fun `each declared build has every hook once, and the patch goes in on its own classes`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name

                // One pass over the build for the classes holding the anchors' strings.
                val holders = mutableMapOf<String, MutableList<ClassDef>>()
                val anchors = listOf(GROOT_PLAY, LEGACY_PLAY, AUTOPLAY_SETTINGS_CHECKER)
                FixtureDex.forEach(fixture) { dex ->
                    val strings = anchors.filter { anchor -> dex.stringSection.any { it == anchor } }
                    if (strings.isEmpty()) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.type.startsWith(EXTENSION_CLASSES)) continue
                        for (anchor in strings) {
                            if (classDef.methods.any { holdsString(it, anchor) }) {
                                holders.getOrPut(anchor) { mutableListOf() } += ImmutableClassDef.of(classDef)
                            }
                        }
                    }
                }

                val plays = holders[GROOT_PLAY].orEmpty().flatMap(::grootPlays)
                assertEquals("$name: players' plays holding \"$GROOT_PLAY\"", 1, plays.size)
                val play = plays.single()
                val trigger = play.parameterTypes.single().toString()
                val groot = holders.getValue(GROOT_PLAY).single { it.type == play.definingClass }

                val pauses = grootPauses(groot, trigger)
                assertEquals("$name: the player's pauses", 2, pauses.size)
                val pause = innerPause(pauses)
                assertNotNull("$name: no pause the other hands on to", pause)
                assertEquals("$name: the inner pause's arguments", 2, pause!!.parameterTypes.size)
                val binds = grootBinds(groot)
                assertEquals("$name: the player's binds", 1, binds.size)

                val legacyPlays = holders[LEGACY_PLAY].orEmpty().flatMap { legacyPlays(it, trigger) }
                assertEquals("$name: older player plays", 1, legacyPlays.size)
                val legacyPlay = legacyPlays.single()
                val legacy = holders.getValue(LEGACY_PLAY).single { it.type == legacyPlay.definingClass }
                assertEquals("$name: older player pauses", 1, legacyPauses(legacy, trigger).size)

                val checkers = holders[AUTOPLAY_SETTINGS_CHECKER].orEmpty().filter(::isAutoplaySettingsChecker)
                assertEquals("$name: Autoplay settings checkers", 1, checkers.size)
                val checker = checkers.single()
                val enumCandidates = checker.methods.filter { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
                    .map { it.returnType }.toSet()

                val kept = setOf(trigger, FRAGMENT_ACTIVITY) + enumCandidates
                val classes = FixtureDex.classes(fixture, kept)
                assertEquals("$name: classes missing", emptySet<String>(), setOf(trigger, FRAGMENT_ACTIVITY) - classes.keys)
                assertTrue("$name: the trigger enum doesn't name ${TRIGGER_NAMES.joinToString()}",
                    isEnumNaming(classes.getValue(trigger), TRIGGER_NAMES))
                val readers = settingReaders(checker) { type -> classes[type]?.let { isEnumNaming(it, SETTING_NAMES) } == true }
                assertEquals("$name: readers of the Autoplay setting", 1, readers.size)
                val reader = readers.single()
                val setting = classes.getValue(reader.returnType)
                assertEquals("$name: touch dispatches", 1, touchDispatches(classes.getValue(FRAGMENT_ACTIVITY)).size)

                // The patch, on this build's own classes.
                val context = PatchContexts.of(
                    listOf(groot, legacy, checker, classes.getValue(trigger), setting, classes.getValue(FRAGMENT_ACTIVITY),
                        ExtensionDex.classDef(SETTINGS_STATUS)),
                )
                tapToPlayPatch.execute(context)
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                // Both plays: player and trigger copied into v0 and v1, the answer, and a no that
                // returns before the player's own first instruction.
                listOf(play to ALLOW_START, legacyPlay to ALLOW_LEGACY_START).forEach { (original, allow) ->
                    val gated = patched(original)
                    val code = gated.implementation!!.instructions.toList()
                    val first = original.implementation!!.instructions.first()
                    assertEquals("$name: ${original.name}'s gate",
                        listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                            Opcode.IF_NEZ, Opcode.RETURN_VOID),
                        code.take(6).map { it.opcode })
                    val triggerIndex = original.parameterTypes.indexOfFirst { it.toString() == trigger }
                    assertEquals("$name: the player's copy", listOf(0, gated.localRegisterCount()),
                        (code[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                    assertEquals("$name: the trigger's copy", listOf(1, gated.parameterRegisterNumber(triggerIndex)),
                        (code[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
                    assertEquals("$name: ${original.name}'s call", allow, code[2].call.toString())
                    assertEquals(listOf(0, 1), code[2].registers())
                    assertEquals(0, (code[3] as OneRegisterInstruction).registerA)
                    assertEquals("$name: the player's own first instruction after the gate", first.opcode, code[6].opcode)
                    assertSame("$name: a yes goes on to it", code[6], (code[4] as BuilderOffsetInstruction).target.location.instruction)
                    assertEquals("$name: ${original.name} grew by the gate",
                        original.implementation!!.instructions.count() + 6, code.size)
                }

                // Pauses and the bind tell the extension first, with p0 alone.
                listOf(pause to PAUSED, binds.single() to REBOUND, legacyPauses(legacy, trigger).single() to PAUSED)
                    .forEach { (original, tell) ->
                        val told = patched(original)
                        val first = told.implementation!!.instructions.first()
                        assertEquals("$name: ${original.name}", Opcode.INVOKE_STATIC_RANGE, first.opcode)
                        assertEquals("$name: ${original.name}", tell, first.call.toString())
                        assertEquals("$name: ${original.name} reads p0", listOf(told.localRegisterCount()), first.registers())
                    }
                val outer = pauses.single { it !== pause }
                assertTrue("$name: the outer pause was hooked too",
                    patched(outer).implementation!!.instructions.none { it.call?.toString() == PAUSED })

                // Each answer of the reader goes through the extension and back as the setting's type.
                val answers = patched(reader).implementation!!.instructions.toList()
                val returns = answers.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
                assertEquals("$name: the reader's returns",
                    reader.implementation!!.instructions.count { it.opcode == Opcode.RETURN_OBJECT }, returns.size)
                returns.forEach { (index, answer) ->
                    val register = (answer as OneRegisterInstruction).registerA
                    assertEquals("$name: reader call", AUTOPLAY_SETTING, answers[index - 3].call.toString())
                    assertEquals(listOf(register), answers[index - 3].registers())
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, answers[index - 2].opcode)
                    assertEquals(register, (answers[index - 2] as OneRegisterInstruction).registerA)
                    assertEquals(Opcode.CHECK_CAST, answers[index - 1].opcode)
                    assertEquals(setting.type, (answers[index - 1] as ReferenceInstruction).reference.toString())
                }

                // Every touch goes to the tap clock with the screen and the event, and on unchanged.
                val dispatch = patched(touchDispatches(classes.getValue(FRAGMENT_ACTIVITY)).single())
                val touch = dispatch.implementation!!.instructions.toList()
                assertEquals("$name: the tap clock", TOUCH, touch[0].call.toString())
                assertEquals(listOf(dispatch.localRegisterCount(), dispatch.localRegisterCount() + 1), touch[0].registers())
                assertTrue("$name: dispatchTouchEvent still hands the event on",
                    touch.drop(1).any { it.call?.name == "dispatchTouchEvent" })
                checked += version
            }
        }
        assertEquals("builds checked", versions, checked)
    }
}

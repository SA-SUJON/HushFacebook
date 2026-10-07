/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mark as seen on each declared Facebook build: the sender takes the arguments the hook hands over,
 * the set the hook answers is the one the sender's own empty check and request builder read, and
 * the hook goes first as a range over every argument with its answer in the set's register. The
 * seen helper and its per-card method are found, read the card's id through StoryCard.getId(), and
 * get the card hook as a range over the session, bucket and card. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MarkAsSeenFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun registers(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is FiveRegisterInstruction -> listOf(
            instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
        ).take(instruction.registerCount)
        else -> emptyList()
    }

    @Test
    fun `each declared build's sender and seen helper take the mark as seen hooks`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val mutation = FixtureDex.classesHolding(bundle, SEEN_ROOT_FIELD).filter(::isSeenMutation).single().type
                val sender = FixtureDex.classesHolding(bundle, STORY_IDS).mapNotNull { seenSender(it, mutation) }.single()
                assertTrue("$name: the sender's arguments aren't a callback and $SENDER_SHAPE", hasSendShape(sender))

                // The set the hook answers is the set argument's register; the sender's first read
                // of it copies it to the register its empty check and the request builder take.
                val count = sender.implementation!!.registerCount
                val set = count - 2
                val code = sender.implementation!!.instructions.toList()
                val copy = code.indexOfFirst {
                    (it.opcode == Opcode.MOVE_OBJECT || it.opcode == Opcode.MOVE_OBJECT_FROM16) &&
                        (it as TwoRegisterInstruction).registerB == set
                }
                assertTrue("$name: the sender never copies its set", copy >= 0)
                val copied = (code[copy] as OneRegisterInstruction).registerA
                val empty = code.indexOfFirst { it.call?.let { c -> c.definingClass == "Ljava/util/Set;" && c.name == "isEmpty" } == true }
                assertEquals("$name: the empty check reads another register", listOf(copied), registers(code[empty]))
                val build = code.single { it.call?.name == "getRequest" && it.call?.definingClass == sender.definingClass }
                assertEquals("$name: the request builder gets another set", copied, registers(build)[2])

                val patched = MutableMethod(sender).apply { filterViews() }
                val after = patched.implementation!!.instructions.toList()
                assertEquals("$name: instructions added to the sender", code.size + 4, after.size)
                assertEquals("$name: the send hook's form", Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
                assertEquals("$name: the send hook", TO_SEND, after[0].call!!.descriptor())
                assertEquals("$name: the send hook's arguments", (count - 9 until count).toList(), registers(after[0]))
                assertTrue("$name: the arguments sit past v15, where only a range reaches", count - 1 > 15)
                assertEquals("$name: the answer", Opcode.MOVE_RESULT_OBJECT, after[1].opcode)
                assertEquals("$name: the answer goes in the set's register", set, (after[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the null check", Opcode.IF_NEZ, after[2].opcode)
                assertEquals("$name: the null check's register", set, (after[2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the hold", Opcode.RETURN_VOID, after[3].opcode)
                for (index in code.indices) {
                    assertEquals("$name: sender instruction $index changed", code[index].opcode, after[index + 4].opcode)
                }

                // The seen helper and the card it's about to count, read through StoryCard.getId().
                val helper = seenHelper(FixtureDex.classesHolding(bundle, SEEN_HELPER_LITERAL), sender)
                val card = cardSeen(helper)
                val cardCount = card.implementation!!.registerCount
                val cardCode = card.implementation!!.instructions.toList()
                val ids = cardCode.filter { it.call?.descriptor() == CARD_ID }
                assertTrue("$name: the per-card method never reads the card's id", ids.isNotEmpty())
                assertTrue("$name: the id is read off something other than the card",
                    ids.all { registers(it) == listOf(cardCount - 3) })
                val storyCard = FixtureDex.classes(bundle, setOf(STORY_CARD))[STORY_CARD]
                assertTrue("$name: StoryCard has no getId()", storyCard?.methods?.any { it.name == "getId" && it.parameterTypes.isEmpty() } == true)

                val reported = MutableMethod(card).apply { reportCard() }
                val cardAfter = reported.implementation!!.instructions.toList()
                assertEquals("$name: instructions added to the per-card method", cardCode.size + 1, cardAfter.size)
                assertEquals("$name: the card hook's form", Opcode.INVOKE_STATIC_RANGE, cardAfter[0].opcode)
                assertEquals("$name: the card hook", ON_CARD, cardAfter[0].call!!.descriptor())
                assertEquals("$name: the card hook's arguments", (cardCount - 5 until cardCount - 2).toList(), registers(cardAfter[0]))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hooks and the stubs the patch fills`() {
        val seen = ExtensionDex.classDef(STORY_SEEN).methods
        val button = ExtensionDex.classDef(STORY_SEEN_BUTTON).methods
        fun MethodReference.key() = descriptor()
        assertTrue("StorySeen has no $TO_SEND", seen.any { it.key() == TO_SEND })
        assertTrue("StorySeenButton has no $ON_CARD", button.any { it.key() == ON_CARD })
        assertEquals("StorySeen's send stub takes what the sender takes",
            listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;") + SENDER_SHAPE.drop(1),
            seen.single { it.name == SEND_STUB }.parameterTypes.map { it.toString() })
        assertEquals("StorySeenButton's card id stub", listOf("Ljava/lang/Object;"),
            button.single { it.name == CARD_ID_STUB }.parameterTypes.map { it.toString() })
    }
}

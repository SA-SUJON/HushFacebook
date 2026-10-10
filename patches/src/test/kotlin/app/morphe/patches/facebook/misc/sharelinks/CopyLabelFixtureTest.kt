/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Facebook's copy labels on every build the bundle declares: the places that add `s` and `fs` to a
 * copied link after the tracker has answered, each found where the link is a string. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CopyLabelFixtureTest {
    private fun Method.returnsSite(site: Int): Boolean {
        val code = implementation!!.instructions.toList()
        val next = code.getOrNull(site + 1) ?: return false
        return next.opcode == Opcode.RETURN_OBJECT &&
            (next as OneRegisterInstruction).registerA == (code[site] as OneRegisterInstruction).registerA
    }

    @Test
    fun `each declared build's copy labels are found where the link is a string`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val labelled = FixtureDex.classesHolding(bundle, COPY_LABEL_END_KEY)
                    .flatMap { it.methods }
                    .associateWith { copyLabelSites(it) }
                    .filterValues { it.isNotEmpty() }
                assertEquals("$name: methods adding the copy labels, ${labelled.keys}", 3, labelled.size)
                assertEquals("$name: places adding them", 4, labelled.values.sumOf { it.size })

                // Positive control: one is a story's link builder, which answers the labelled link.
                val builders = labelled.filter { (method, sites) -> sites.any { method.returnsSite(it) } }
                assertEquals("$name: builders answering the labelled link", 1, builders.size)
                assertEquals("$name: the builder's shape", listOf("Ljava/lang/String;", 2),
                    builders.keys.single().let { listOf(it.returnType, it.parameterTypes.size) })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.telemetry

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block ad telemetry's attribution provider on each declared Facebook build. The provider has to
 * keep one query with ContentProvider.query's shape, room for the no-ID row's three registers, and
 * the advertising ID columns it answers with. A miss is only an info line in the patch log, so this
 * is where a build that moved it shows up. The hook then goes in on the real class, which also
 * assembles the no-ID row's smali.
 */
class AttributionProviderFixtureTest {
    @Test
    fun `the attribution provider has one query to answer on every declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val provider = FixtureDex.classes(bundle, setOf(ATTRIBUTION_PROVIDER))[ATTRIBUTION_PROVIDER]
                assertNotNull("${bundle.name}: $ATTRIBUTION_PROVIDER is there", provider)
                val queries = provider!!.methods.filter(::isProviderQuery)
                assertEquals("${bundle.name}: query methods in $ATTRIBUTION_PROVIDER", 1, queries.size)
                val query = queries.single()
                assertTrue("${bundle.name}: ${query.name} has 3 locals for the no-ID row", query.localRegisterCount() >= 3)
                assertTrue("${bundle.name}: ${query.name} answers the advertising ID columns", holdsString(query, "limit_tracking"))

                // The hook goes in on the real class and answers before the query's own first instruction.
                val context = PatchContexts.of(listOf(provider))
                assertNull("${bundle.name}: the hook's reason", context.answerNoAdIdOrReason())
                val patched = context.mutableClassDefBy(ATTRIBUTION_PROVIDER).methods.single(::isProviderQuery)
                    .implementation!!.instructions.toList()
                val original = query.implementation!!.instructions.toList()
                val answer = patched.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
                assertEquals("${bundle.name}: the no-ID row's last line", 11, answer)
                assertEquals("${bundle.name}: the query's own code after it", original.size, patched.size - answer - 1)
                assertEquals("${bundle.name}: the query's own first line", original[0].opcode, patched[answer + 1].opcode)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

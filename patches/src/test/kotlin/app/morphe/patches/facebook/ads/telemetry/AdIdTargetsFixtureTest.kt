/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.telemetry

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block ad telemetry's advertising ID targets on each declared Facebook build. Each job has to keep
 * a void method to stop, and each sender string has to mark exactly one void method, which is the
 * method the patch returns early. A missing one is only an info line in the patch log, so this is
 * where a new build that moved one shows up.
 */
class AdIdTargetsFixtureTest {
    @Test
    fun `each advertising ID job and sender is there to stop on every declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val jobs = FixtureDex.classes(bundle, AD_ID_JOBS.toSet())
                for (type in AD_ID_JOBS) {
                    val stoppable = jobs[type]?.methods?.filter { method ->
                        method.returnType == "V" && method.name != "<init>" && method.name != "<clinit>" &&
                            method.implementation != null
                    }.orEmpty()
                    assertTrue("${bundle.name}: $type has a void method to stop", stoppable.isNotEmpty())
                }
                for (marker in AD_ID_SENDERS) {
                    val senders = FixtureDex.classesHolding(bundle, marker).flatMap { classDef ->
                        classDef.methods.filter { isAdIdSender(it, marker) }
                    }
                    assertEquals(
                        "${bundle.name}: void methods holding \"$marker\" " +
                            senders.joinToString { "${it.definingClass}->${it.name}" },
                        1,
                        senders.size,
                    )
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

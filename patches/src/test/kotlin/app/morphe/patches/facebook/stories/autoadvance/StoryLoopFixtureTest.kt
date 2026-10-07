/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.autoadvance

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loop stories makes Facebook's own restart from the progress callback's class. Each declared
 * build's navigator holds exactly one run of it, and the callback is an instance method, so the
 * guard's p0 is the callback the restart's first call is made on.
 */
class StoryLoopFixtureTest {
    @Test
    fun `each declared build's navigator holds one restart the loop can make`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val owners = FixtureDex.classesHolding(bundle, AUTO_NAVIGATION)
                    .mapNotNull { owner -> autoAdvanceHook(owner)?.let { owner to it } }
                assertEquals("${bundle.name}: completion hook", 1, owners.size)
                val (owner, hook) = owners.single()
                val (callback, index) = hook
                assertFalse("${bundle.name}: the callback is static", AccessFlags.STATIC.isSet(callback.accessFlags))

                val navigator = navigatorOf(owner, callback, index)
                val route = restartRoute(navigator, owner.type)
                assertNotNull("${bundle.name}: Facebook's restart in ${navigator.name}", route)
                route!!
                assertTrue("${bundle.name}: the environment comes from the callback", route.environment.parameterTypes.isEmpty())
                assertEquals("${bundle.name}: the lookup takes the environment", listOf(route.environment.returnType),
                    route.lookup.parameterTypes.map { it.toString() })
                assertEquals("${bundle.name}: the reset is the lookup's", route.lookup.returnType, route.reset.definingClass)
                assertEquals("${bundle.name}: the reset takes a boolean", listOf("Z"), route.reset.parameterTypes.map { it.toString() })
                assertFalse("${bundle.name}: the class already has the loop helper", owner.methods.any { it.name == LOOP_HELPER })
                // The helper builds: the switch, the branch over the restart, and the restart's calls.
                val helper = loopHelper(owner.type, route).implementation!!.instructions.toList()
                assertEquals("${bundle.name}: the helper's body", 10, helper.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

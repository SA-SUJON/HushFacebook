/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.patch.Patch
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Morphe executes the patches it's given sorted by name, each after its dependencies, and runs
 * their finalize blocks in the reverse order. Clone app renames the package in its finalize block,
 * so the clone support only finalizes after it, and sees the renamed manifest, when it executed
 * before "Clone app": when a patch whose name sorts before that one depends on it. Every patch
 * does, and the default selection holds some whose names sort before it.
 */
class ClonedPackageOrderTest {
    /** Every patch this bundle declares at the top level, the way Morphe's loader finds them. */
    private fun bundlePatches(): List<Patch<*>> {
        val root = File(Class.forName("app.morphe.patches.facebook.coexist.ClonedPackageKt")
            .protectionDomain.codeSource.location.toURI())
        return root.walkTopDown().filter { it.name.endsWith("Kt.class") }.flatMap { file ->
            val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            Class.forName(name).methods.filter {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && Patch::class.java.isAssignableFrom(it.returnType)
            }.map { it.invoke(null) as Patch<*> }
        }.toList()
    }

    private fun Patch<*>.reaches(target: Patch<*>): Boolean = this === target || dependencies.any { it.reaches(target) }

    /** Whatever the reader picks, a clone's code reaches its own providers. */
    @Test
    fun everyPatchBringsTheCloneSupport() {
        val named = bundlePatches().filter { it.name != null }.distinct()
        assertTrue("found only ${named.size} named patches", named.size > 30)
        assertEquals("patches without the clone support", emptyList<String>(),
            named.filterNot { it.reaches(clonedPackagePatch) }.map { it.name })
    }

    @Test
    fun theDefaultSelectionHasPatchesMorpheRunsBeforeCloneApp() {
        val early = bundlePatches().filter { it.name != null && it.name!! < "Clone app" }.distinct()
        assertTrue("no default patch sorts before Clone app: ${early.map { it.name }}", early.any { it.default })
    }
}

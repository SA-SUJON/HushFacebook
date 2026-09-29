/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coexistence sign-in fix rides on one method: the reader Facebook gives every trusted-caller
 * check its signers through, which Restore screens on re-signed builds hooks. This proves, on each
 * declared build, that that reader is the single no-argument method reading both of a SigningInfo's
 * signer lists and the old array too (the shape Restore screens' fingerprint pins), that the caller's
 * identity for a guarded component is built from that reader's result, so answering it Meta's
 * certificate makes Facebook judge a same-key family caller as the Meta-signed app, and that the
 * trusted-caller evaluator is left alone: the coexistence patch no longer hooks it and nothing in it
 * calls the extension.
 */
class CoexistIdentityFixtureTest {
    private companion object {
        const val TRUSTED_CALLER_DELEGATE =
            "Lcom/facebook/secure/content/delegate/TrustedCallerContentProviderDelegate;"
        const val SIGNING_INFO = "Landroid/content/pm/SigningInfo;"
        const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
        const val CONTEXT = "Landroid/content/Context;"
        const val ORIGINAL_SIGNERS = "Lapp/morphe/extension/facebook/misc/FacebookSignature;->originalSigners"
    }

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** The `(class, name)` of every method this one calls. */
    private fun Method.calls(): List<Pair<String, String>> = body().mapNotNull { it.call }.map { it.definingClass to it.name }

    /** Whether this method reads both of a SigningInfo's signer lists and the old signatures array. */
    private fun Method.readsAllSignerSources(): Boolean {
        var apk = false
        var history = false
        var old = false
        for (instruction in body()) {
            val reference = (instruction as? ReferenceInstruction)?.reference
            if (reference is MethodReference && reference.definingClass == SIGNING_INFO) {
                if (reference.name == "getApkContentsSigners") apk = true
                if (reference.name == "getSigningCertificateHistory") history = true
            }
            if (reference is FieldReference && reference.definingClass == PACKAGE_INFO && reference.name == "signatures") old = true
        }
        return apk && history && old
    }

    /**
     * Whether this method builds a caller identity from [reader]: it calls the signers reader and, in
     * the same body, constructs an object whose constructor takes the signer lists (three or more List
     * parameters). That's the AppIdentity builder Facebook fills from the reader's result.
     */
    private fun Method.buildsIdentityFrom(reader: Pair<String, String>): Boolean {
        if (reader !in calls()) return false
        // The constructor call is invoke-direct or, with many registers, invoke-direct/range.
        return body().mapNotNull { it.call }.any {
            it.name == "<init>" && it.parameterTypes.count { parameter -> parameter == "Ljava/util/List;" } >= 3
        }
    }

    private fun methodsWhere(bundle: File, wanted: (Method) -> Boolean): List<Method> {
        val found = mutableListOf<Method>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) for (method in classDef.methods) if (wanted(method)) found += ImmutableMethod.of(method)
        }
        return found
    }

    @Test
    fun eachDeclaredBuildBuildsTheCallerIdentityFromTheHookedSignersReader() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val delegate = FixtureDex.classes(bundle, setOf(TRUSTED_CALLER_DELEGATE))[TRUSTED_CALLER_DELEGATE]
                    ?.let(ImmutableClassDef::of)
                checkNotNull(delegate) { "$name: no $TRUSTED_CALLER_DELEGATE" }

                // The signers reader: the one no-argument method reading both SigningInfo lists and the
                // old array. Restore screens hooks exactly this method by that shape.
                val readers = methodsWhere(bundle) { it.parameterTypes.isEmpty() && it.readsAllSignerSources() }
                assertEquals("$name: one signers reader for Restore screens to hook", 1, readers.size)
                val reader = readers.single()
                val readerKey = reader.definingClass to reader.name

                // The caller's identity is built from that reader's result, so spoofing the reader
                // spoofs the identity every caller rule then judges.
                val builders = methodsWhere(bundle) { it.buildsIdentityFrom(readerKey) }
                assertTrue(
                    "$name: the caller identity is built from the hooked signers reader ${reader.definingClass}->${reader.name}",
                    builders.isNotEmpty(),
                )

                // The trusted-caller evaluator is left alone: nothing in it calls the extension.
                val evaluator = evaluatorOf(bundle, delegate)
                assertFalse(
                    "$name: the trusted-caller evaluator must not call the extension",
                    evaluator.calls().any { (owner, methodName) -> "$owner->$methodName".startsWith(ORIGINAL_SIGNERS) },
                )
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The one static `(Context, rule) -> boolean` the delegate's no-argument caller checks share. */
    private fun evaluatorOf(bundle: File, delegate: ClassDef): Method {
        val wanted = delegate.methods
            .filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }
            .mapNotNull { method ->
                method.body().mapNotNull { it.call }.singleOrNull { call ->
                    call.returnType == "Z" && call.parameterTypes.size == 2 && call.parameterTypes[0].toString() == CONTEXT
                }
            }
            .distinctBy { "${it.definingClass}->${it.name}" }
        assertEquals("${bundle.name}: one shared evaluator", 1, wanted.size)
        val evaluator = wanted.single()
        val found = methodsWhere(bundle) {
            it.definingClass == evaluator.definingClass && it.name == evaluator.name &&
                it.returnType == evaluator.returnType &&
                it.parameterTypes.map(CharSequence::toString) == evaluator.parameterTypes.map(CharSequence::toString)
        }
        assertEquals("${bundle.name}: resolved the evaluator ${evaluator.definingClass}->${evaluator.name}", 1, found.size)
        return found.single()
    }
}

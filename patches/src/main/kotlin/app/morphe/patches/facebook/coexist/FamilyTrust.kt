/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Facebook's base for a provider whose cross-process callers are checked by a trusted-caller rule. */
internal const val TRUSTED_CALLER_DELEGATE =
    "Lcom/facebook/secure/content/delegate/TrustedCallerContentProviderDelegate;"

private const val CONTEXT = "Landroid/content/Context;"

/** The extension answers whether the current caller is a family app carrying this build's own key. */
internal const val ACCEPT_CALL =
    "$EXTENSION_PACKAGE/coexist/FamilySignatureTrust;->accept($CONTEXT)Z"

/**
 * Lets a Messenger re-signed with this build's key sign in through a patched Facebook.
 *
 * Facebook guards its sign-in store, UserValuesProvider, with a "same key" check: a cross-process
 * caller passes only when it carries Facebook's own signing certificate. Restore screens on re-signed
 * builds answers Facebook its original Meta certificate for its own package, so that check trusts
 * Meta's certificate rather than this build's, and a Messenger carrying the user's Manager key is
 * refused with "Component access not allowed".
 *
 * The check runs through one static evaluator that takes the provider's context and the caller policy
 * and answers a boolean; UserValuesProvider reaches it through this delegate's two checks. The hook
 * asks the extension first: when the caller is a family app carrying this build's own key it answers
 * true and the evaluator returns, so the caller passes the way Meta's signed Messenger does. Otherwise
 * Facebook's own check runs unchanged, so every other caller keeps its answer and a Meta-signed caller
 * keeps passing.
 */
internal fun BytecodePatchContext.trustSameKeyFamilyCallers() {
    val delegate = mutableClassDefBy(TRUSTED_CALLER_DELEGATE)

    // The delegate's caller checks take nothing and answer a boolean, each through the one static
    // evaluator (context, policy) -> boolean. Both checks call the same evaluator.
    val evaluators = delegate.methods
        .filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }
        .mapNotNull(Method::trustEvaluatorCall)
        .distinct()
    if (evaluators.size != 1) {
        throw PatchException(
            "$PATCH: expected one caller-trust evaluator behind $TRUSTED_CALLER_DELEGATE, found ${evaluators.size}",
        )
    }
    val evaluator = evaluators.single()

    val method = mutableClassDefBy(evaluator.definingClass).methods.singleOrNull {
        it.name == evaluator.name &&
            it.returnType == evaluator.returnType &&
            it.parameterTypes.map(CharSequence::toString) == evaluator.parameterTypes.map(CharSequence::toString)
    } ?: throw PatchException("$PATCH: could not resolve the caller-trust evaluator $evaluator")

    method.acceptSameKeyFamilyCaller()
}

/**
 * The one static `(Context, X) -> boolean` this caller-check method calls, or null. That's the shared
 * evaluator every trusted-caller policy runs through.
 */
internal fun Method.trustEvaluatorCall(): MethodReference? {
    val instructions = implementation?.instructions ?: return null
    return instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .singleOrNull { call ->
            call.returnType == "Z" &&
                call.parameterTypes.size == 2 &&
                call.parameterTypes[0].toString() == CONTEXT
        }
}

/**
 * Puts the family-caller check at the top of the evaluator, on the context it's handed. A true answer
 * returns straight away; false falls through to Facebook's own check.
 *
 * The context is copied down with `move-object/from16` so the read reaches a parameter above v15, the
 * way Restore screens does, and the injected code borrows one local, free before the method's first
 * instruction runs. The extension never throws, so the call needs no handler.
 */
internal fun MutableMethod.acceptSameKeyFamilyCaller() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${parameterRegister(0)}
            invoke-static { v0 }, $ACCEPT_CALL
            move-result v0
            if-eqz v0, :refused
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("refused", getInstruction(0)),
    )
}

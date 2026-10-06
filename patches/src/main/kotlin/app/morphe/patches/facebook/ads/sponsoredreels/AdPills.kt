/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** The plugin that draws the floating button on a reel ad's comments, by its class name. */
internal const val REELS_AD_PILL =
    "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"

/** Gets a pill plugin's class name. Gives true when that plugin's button stays off. */
internal const val HOLDS_AD_PILL = "$REELS_AD_FILTER->holdsAdPill(Ljava/lang/String;)Z"

/**
 * The comment sheet's indicator pill numbers its plugins, and one static method of the pill's
 * component turns a number into the plugin's class name (581 `LX/A38;->A0G`). Its table holds the
 * Reels ad button's plugin among the others.
 */
internal object AdPillNamesFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("I"),
    strings = listOf(REELS_AD_PILL),
)

/**
 * The pill component's check of whether a plugin's button shows (581 `LX/A38;->A0H`): static, a
 * boolean answer, the plugin's number last, and a switch on that number first. The component's
 * render asks the name table and then this for the same number.
 */
internal fun isAdPillCheck(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z") return false
    if (method.parameterTypes.lastOrNull()?.toString() != "I") return false
    val implementation = method.implementation ?: return false
    val first = implementation.instructions.firstOrNull() ?: return false
    return (first.opcode == Opcode.PACKED_SWITCH || first.opcode == Opcode.SPARSE_SWITCH) &&
        (first as OneRegisterInstruction).registerA == implementation.registerCount - 1
}

/**
 * Has the pill's check ask the extension first, with the plugin's class name from the component's
 * own table, and answer no when it says so. Throws when the table or the check has moved, which the
 * patch logs and goes on.
 */
internal fun BytecodePatchContext.holdAdPills() {
    val names = AdPillNamesFingerprint.methodOrNull
        ?: throw PatchException("$SPONSORED_REELS_PATCH: no comment pill table names $REELS_AD_PILL")
    val checks = mutableClassDefBy(names.definingClass).methods.filter(::isAdPillCheck)
    val check = checks.singleOrNull() ?: throw PatchException(
        "$SPONSORED_REELS_PATCH: ${names.definingClass} has ${checks.size} pill checks, expected one",
    )
    check.requireLocals(SPONSORED_REELS_PATCH, 1)
    val plugin = check.implementation!!.registerCount - 1
    check.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$plugin .. v$plugin }, ${names.definingClass}->${names.name}(I)Ljava/lang/String;
            move-result-object v0
            invoke-static { v0 }, $HOLDS_AD_PILL
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", check.getInstruction(0)),
    )
}

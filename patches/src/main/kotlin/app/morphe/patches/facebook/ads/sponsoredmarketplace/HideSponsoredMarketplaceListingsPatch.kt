/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredmarketplace

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Keeps the ads out of Marketplace's feed and its search results: the feed's query asks Facebook's
 * servers to skip them, the queries that fetch only ads aren't sent, and the ads that come back
 * among search results are taken out of the answer before JavaScript sees it. See
 * MarketplaceRequestAnchors.kt and MarketplaceResponseAnchors.kt for where the requests and their
 * answers pass through Java, and the extension's MarketplaceAdFilter for what it changes.
 */
@Suppress("unused")
val hideSponsoredMarketplaceListingsPatch = bytecodePatch(
    name = "Hide sponsored Marketplace listings",
    description = "Removes the ads and boosted listings from Marketplace's feed and search results. The feed's request " +
        "asks Facebook to leave them out and the requests that fetch only ads don't go out. Ads that come back among " +
        "search results are taken out before Marketplace shows them. The listings people post stay.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val sends = classDefByStrings(NETWORKING_TAG, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter(::isSendRequest) }
        val send = sends.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one React Native $SEND_REQUEST holding \"$NETWORKING_TAG\" and \"$TRACKING_NAME\", " +
                "found ${sends.size}",
        )
        val read = bodyRead(send) ?: throw PatchException(
            "$PATCH: ${send.definingClass}->$SEND_REQUEST no longer reads its POST body from the request data's " +
                "\"$BODY_KEY\" right before the one StringEntity it builds, from the data it reads \"$TRACKING_NAME\" from",
        )
        mutableClassDefBy(send.definingClass).findMutableMethodOf(send).askAboutTheBody(read)

        val callbacks = classDefByOrNull(CALLBACKS) ?: throw PatchException("$PATCH: this build has no $CALLBACKS")
        val inBuild = { reference: MethodReference -> method(classDefByOrNull(reference.definingClass)?.methods, reference) }
        val handOffs = textHandOffs(callbacks, inBuild)
        val pieces = handOffs.count { !it.whole }
        val wholes = handOffs.count { it.whole }
        if (pieces != 1 || wholes != 1) {
            throw PatchException(
                "$PATCH: expected $CALLBACKS to hand an answer's text to JavaScript once in pieces and once whole, " +
                    "each from a toString() with the request's state at hand, found $pieces and $wholes",
            )
        }
        val stateType = handOffs.map { it.stateType }.distinct().singleOrNull()
            ?: throw PatchException("$PATCH: $CALLBACKS reads two request states: ${handOffs.map { it.stateType }}")
        val tracking = trackingField(send, stateType, inBuild) ?: throw PatchException(
            "$PATCH: ${send.definingClass}->$SEND_REQUEST no longer builds $stateType with the \"$TRACKING_NAME\" it " +
                "reads, kept in one String field",
        )
        val mutableCallbacks = mutableClassDefBy(CALLBACKS)
        handOffs.forEach { mutableCallbacks.findMutableMethodOf(it.method).handTheTextOver(it, tracking) }
        enableStatus("sponsoredMarketplace")
    }
}

/**
 * Right after sendRequest reads the POST body, the body and the request data go to the extension,
 * and its answer takes the body's register. Nothing else in the method changes. Both registers
 * were checked to fit an invoke's four bits.
 */
internal fun MutableMethod.askAboutTheBody(read: BodyRead) {
    if (read.body > 15 || read.data > 15) {
        throw PatchException("$PATCH: $definingClass->$name keeps the body or the data past v15: ${read.body}, ${read.data}")
    }
    addInstructions(
        read.index + 1,
        """
            invoke-static { v${read.body}, v${read.data} }, $REQUEST_BODY
            move-result-object v${read.body}
        """,
    )
}

/** The method of [methods] that [reference] names, or null. */
private fun method(methods: Iterable<Method>?, reference: MethodReference): Method? = methods?.firstOrNull {
    it.name == reference.name && it.returnType == reference.returnType &&
        it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
}

/**
 * Right after [handOff]'s text lands, the tracking name goes into a local nothing reads afterwards,
 * the text, the name and for a piece the request's state go to the extension, and its answer takes
 * the text's register. Nothing else in the method changes.
 */
internal fun MutableMethod.handTheTextOver(handOff: TextHandOff, tracking: FieldReference) {
    val at = handOff.landed + 1
    val name = freeLocalsAt(PATCH, at, 1).single()
    val call = if (handOff.whole) {
        "invoke-static { v${handOff.text}, v$name }, $RESPONSE_WHOLE"
    } else {
        "invoke-static { v${handOff.text}, v$name, v${handOff.state} }, $RESPONSE_PIECE"
    }
    addInstructions(
        at,
        """
            iget-object v$name, v${handOff.state}, $tracking
            $call
            move-result-object v${handOff.text}
        """,
    )
}

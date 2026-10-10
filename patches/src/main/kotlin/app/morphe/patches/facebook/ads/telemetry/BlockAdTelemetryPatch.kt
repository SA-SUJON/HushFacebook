/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/blockadtelemetry/BlockAdTelemetryPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.telemetry

import app.morphe.patches.facebook.shared.neuterOrReason
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.handleTargets
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "Block ad telemetry"

/** Ad measurement that runs whether or not an ad is shown. All keep their real names. */
internal val AD_TELEMETRY = listOf(
    // Watches for you taking a screenshot of an ad. The controller's void methods add and remove
    // the detector's listener, so neutering them means it is never registered.
    "Lcom/facebook/ads/screenshot/AdsScreenshotController;",
    "Lcom/facebook/ads/AdsScreenshotDetector;",
    // Ad attribution for apps you install: the tracker's scheduler and the service that does the
    // reporting (doHandleIntent keeps its name).
    "Lcom/facebook/feed/platformads/AppInstallTrackerScheduler;",
    "Lcom/facebook/feed/platformads/AppInstallService;",
)

/**
 * Jobs that report the advertising ID or check Android's ad measurement, mapped on 582 (2026-10-10).
 * Each keeps its name and has only void methods, so neutering stops the job outright:
 * LatStatusJob posts the ID with its limit-tracking flag to /attributions, PrivacySandboxCapabilities-
 * Checker logs Android's measurement status, and TestPACustomAudienceAppJob joins a test custom
 * audience on Android's ad services.
 */
internal val AD_ID_JOBS = listOf(
    "Lcom/facebook/attribution/LatStatusJob;",
    "Lcom/facebook/privacysandbox/PrivacySandboxCapabilitiesChecker;",
    "Lcom/facebook/privacysandbox/protectedaudience/TestPACustomAudienceAppJob;",
)

/**
 * Strings that each mark the one void method sending an identifier, which returns at once: the
 * advertising ID to Facebook as a GraphQL mutation, the advertising ID, App Manager id and device
 * id in a launch event, and an ad view or click registered with Android's attribution. The method
 * names are Redex's, so the strings find them.
 */
internal val AD_ID_SENDERS = listOf(
    "ReportAdvertiserIDMutation",
    "fb4a_launch_custom_data",
    "https://www.facebook.com/privacy_sandbox/mobile/register/source?tracking_data=",
)

@Suppress("unused")
val blockAdTelemetryPatch = bytecodePatch(
    name = "Block ad telemetry",
    description = "Stops Facebook watching for screenshots of ads, reporting which apps you install after " +
        "seeing ads, and sending your phone's advertising ID, so less of what you do feeds its ad tracking. Works " +
        "as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Deliberately not covered:
    //
    // - AdVisualQualityEngine / OcrPreprocessor (on-device OCR of ad creative). Their only entry
    //   points are suspend functions returning Object, so an early return hands null to a
    //   continuation that does not expect one. They only run on a rendered ad anyway.
    // - PigeonFeedUnitSponsoredImpressionLogger. Its one clean entry point marks an impression as
    //   *already logged*; neutering it invites repeat logging rather than none.
    //
    // Each class stands alone, so a build that renamed some still gets the others stopped, and the
    // patch log names each one left running. None found stops the patch.
    execute {
        handleTargets(PATCH, "ad telemetry classes", AD_TELEMETRY) { neuterOrReason(it) }

        // The advertising ID reporting is extra: a build without one of these still gets the
        // classes above, so a missing one is an info line rather than a warning.
        AD_ID_JOBS.forEach { type -> neuterOrReason(type)?.let { patchLog.info("$PATCH: $it.") } }
        AD_ID_SENDERS.forEach { marker -> stopSenderOrReason(marker)?.let { patchLog.info("$PATCH: $it.") } }

        enableStatus("adTelemetry")
    }
}

/** Whether [method] is a sender [marker] picks: a void method loading exactly that string. */
internal fun isAdIdSender(method: Method, marker: String) = method.returnType == "V" && holdsString(method, marker)

/** Null once the one void method holding exactly [marker] returns at once, or why nothing changed. */
internal fun BytecodePatchContext.stopSenderOrReason(marker: String): String? {
    val senders = classDefByStrings(marker, StringComparisonType.EQUALS).flatMap { classDef ->
        classDef.methods.filter { isAdIdSender(it, marker) }
    }
    val sender = senders.singleOrNull()
        ?: return "${senders.size} void methods hold \"$marker\", expected 1, so that send keeps running"
    mutableClassDefBy(sender.definingClass).methods.single {
        it.name == sender.name && it.parameterTypes.map(Any::toString) == sender.parameterTypes.map(Any::toString)
    }.returnEarly()
    return null
}

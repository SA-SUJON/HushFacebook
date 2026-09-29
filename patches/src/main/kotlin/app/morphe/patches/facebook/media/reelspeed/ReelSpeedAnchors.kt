/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.reelspeed

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * Where Keep the reel speed hooks, found by kept names only (read from 577 and 580, 2026-09-29).
 * The obfuscated names in these comments are for reviewers; the code never writes one down.
 *
 * - The Reels menu's speed pickers: FbShortsInlinePlaybackSpeedUtil builds a dropdown and an
 *   attribute selector, and a pick in either sets the speed on the reel's FbGrootPlayer (found by
 *   the reel's PlayerOrigin and video id), then posts, 150 ms later, the speed toast: the one
 *   static (Context, float) method holding "InlinePlaybackSpeedAttributeSelector" (580
 *   LX/Txd;->A02, 577 LX/Het;->A02). Only the two pickers' runnables call it.
 * - FbGrootPlayer (580 LX/5BR;, 577 LX/4qS;), the class of the play holding "FbGrootPlayer.play":
 *   its speed setter, the one instance (F)V method that reads HeroPlayerSetting's
 *   enableLastPlaybackSpeedCacheUpdate, which it checks before remembering the speed for the video
 *   (580 A1V, 577 A1U; the pickers, the gear menu's speed sheet and Facebook's own hold-for-2x all
 *   set a speed through it); its PlayerOrigin getter, the one no-argument method answering a
 *   PlayerOrigin (580 Bs0, 577 BtY); its bind, the one method holding "FbGrootPlayer.bindVideoSources"
 *   (580 A1l, 577 A1j); maybeTrackVideoStart, a kept name, which the play's start path runs
 *   right after the Hero player starts, before it looks up the speed it remembers for the video;
 *   and its VideoPlayerParams getter, the one no-argument method answering them (580 CM9, 577 CMy),
 *   null before the first bind.
 * - VideoPlayerParams' debug dump (580 EYb, 577 EVr) reports each field under its name: isFbShorts
 *   (580 A1e, 577 A1d), isSponsored (580 A1v, 577 A1u) and isLiveNow (580 A1m, 577 A1l) are public
 *   booleans. The Reels viewer
 *   plays ads and live videos between reels, and Facebook's own speed-up skips live videos too.
 * - A new reel's player starts at normal speed: the play only restores a speed Facebook remembered
 *   for that same video. PlayerOrigin.toString() writes the origin, then "::" and where in the
 *   viewer the video started when that's known. The Reels viewer's origin is "fb_shorts_viewer";
 *   reels in the feed play under others (fb_shorts_native_in_feed_unit and more), all named in
 *   PlayerOrigin's static initializer.
 */

internal const val REEL_SPEED = "$EXTENSION_PACKAGE/media/ReelSpeed;"
internal const val SPEED_SET = "$REEL_SPEED->speedSet(Ljava/lang/Object;F)V"
internal const val PICKED = "$REEL_SPEED->picked(F)V"
internal const val BOUND = "$REEL_SPEED->bound(Ljava/lang/Object;)V"
internal const val STARTED = "$REEL_SPEED->started(Ljava/lang/Object;)V"
internal const val SET_SPEED_STUB = "setPlayerSpeed"
internal const val ORIGIN_STUB = "playerOrigin"
internal const val REEL_PARAMS_STUB = "playerParams"

/** The VideoPlayerParams booleans the extension reads, by the name the params' debug dump reports each under, to the stub reading it. */
internal val REEL_PARAM_STUBS = linkedMapOf("isFbShorts" to "fbShorts", "isSponsored" to "sponsored", "isLiveNow" to "liveNow")

/** The Reels viewer's PlayerOrigin, the only viewer whose reels get the kept speed (the extension's ReelSpeed.VIEWER). */
internal const val VIEWER_ORIGIN = "fb_shorts_viewer"

internal const val SPEED_TOAST = "InlinePlaybackSpeedAttributeSelector"
internal const val PLAYER_ORIGIN = "Lcom/facebook/video/common/playerorigin/PlayerOrigin;"
internal const val SPEED_CACHE_SWITCH =
    "Lcom/facebook/video/heroplayer/setting/HeroPlayerSetting;->enableLastPlaybackSpeedCacheUpdate:Z"
private const val CONTEXT = "Landroid/content/Context;"

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

/** Whether [method] is the Reels menu's speed toast: static, (Context, float), holding its selector's name. */
internal fun isSpeedToast(method: Method): Boolean =
    method.isStatic() && method.returnType == "V" && method.parameters() == listOf(CONTEXT, "F") &&
        holdsString(method, SPEED_TOAST)

/** [owner]'s speed setters: instance (F)V methods reading HeroPlayerSetting's speed cache switch. */
internal fun speedSetters(owner: ClassDef): List<Method> = owner.methods.filter { method ->
    !method.isStatic() && method.returnType == "V" && method.parameters() == listOf("F") &&
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == SPEED_CACHE_SWITCH
        } == true
}

/** [owner]'s PlayerOrigin getters: instance methods with a body, taking nothing and answering one. */
internal fun originGetters(owner: ClassDef): List<Method> = owner.methods.filter {
    !it.isStatic() && it.parameterTypes.isEmpty() && it.returnType == PLAYER_ORIGIN && it.implementation != null
}

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
 *   (580 A1l, 577 A1j); and maybeTrackVideoStart, a kept name, which the play's start path runs
 *   right after the Hero player starts, before it looks up the speed it remembers for the video.
 * - A new reel's player starts at normal speed: the play only restores a speed Facebook remembered
 *   for that same video. PlayerOrigin.toString() writes the origin, then "::" and where in the
 *   viewer the video started when that's known.
 */

internal const val REEL_SPEED = "$EXTENSION_PACKAGE/media/ReelSpeed;"
internal const val SPEED_SET = "$REEL_SPEED->speedSet(Ljava/lang/Object;F)V"
internal const val PICKED = "$REEL_SPEED->picked(F)V"
internal const val BOUND = "$REEL_SPEED->bound(Ljava/lang/Object;)V"
internal const val STARTED = "$REEL_SPEED->started(Ljava/lang/Object;)V"
internal const val SET_SPEED_STUB = "setPlayerSpeed"
internal const val ORIGIN_STUB = "playerOrigin"

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

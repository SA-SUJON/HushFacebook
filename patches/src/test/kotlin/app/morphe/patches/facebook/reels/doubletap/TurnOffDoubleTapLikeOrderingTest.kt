/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.doubletap

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.media.taptoplay.FB_USER_SESSION
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small hand-built build with every anchor Turn off double tap to like needs: a reel like helper,
 * a GestureReactionComponent whose view has a heart and a handler read by three owners (the heart
 * itself, a listener the view makes and an event subscriber the component makes), and a feed
 * attachment. One version is entirely sound and the patch applies. A second is sound everywhere
 * except the event subscriber, the last reader the hook looks at, whose read isn't followed by a
 * null check. On that build the whole hook must refuse before changing anything: not the reel like
 * helper hookReelLikes used to finish first, and not the heart or the listener hookGestureView's own
 * loop used to hook before reaching the bad reader.
 */
class TurnOffDoubleTapLikeOrderingTest {
    private val helper = "Lfixture/Helper;"
    private val attachmentClass = "Lfixture/Attachment;"
    private val component = "Lfixture/Component;"
    private val view = "Lfixture/GestureView;"
    private val listener = "Lfixture/Listener;"
    private val eventSubscriber = "Lfixture/EventSubscriber;"
    private val handlerField = "$view->handler:Ljava/lang/Object;"

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        body: String,
        registers: Int = 8,
        static: Boolean = false,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun classDef(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.map(ImmutableMethod::of))

    private fun helperClass() = classDef(
        helper,
        method(
            helper, "like", listOf(FB_USER_SESSION, "Ljava/lang/Object;", "Ljava/lang/String;"), "V",
            """
                const-string v0, "$MUTATE_LIKE"
                return-void
            """,
        ),
        method(
            helper, "doubleTapLike", listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Z"), "V",
            """
                invoke-static {p1, p3}, $helper->keyFor(Ljava/lang/Object;Z)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :noKey
                const-string v1, "like_double_tap"
                return-void
                :noKey
                return-void
            """,
        ),
    )

    private fun attachment() = classDef(
        attachmentClass,
        method(
            attachmentClass, "onDoubleTap", listOf(MOTION_EVENT), "Z",
            """
                const-string v0, "$HEART_RISE"
                const/4 v1, 0x0
                invoke-virtual {v1, v1, v1, v1}, $helper->doubleTapLike(Ljava/lang/Object;Ljava/lang/Object;Z)V
                const/4 v0, 0x0
                return v0
            """,
        ),
    )

    private fun componentClass() = classDef(
        component,
        method(component, "<init>", emptyList(), "V", """
            const-string v0, "$GESTURE_REACTION"
            return-void
        """),
        method(component, "onCreateMountContent", listOf("Landroid/content/Context;"), "Ljava/lang/Object;", """
            new-instance v0, $view
            return-object v0
        """),
        // What the component makes on mount: the event subscriber, the third reader.
        method(component, "onMount", listOf("Ljava/lang/Object;"), "V", """
            new-instance v0, $eventSubscriber
            return-void
        """),
    )

    private fun gestureView() = classDef(
        view,
        method(view, "heart", listOf(MOTION_EVENT, view), "V", """
            const-string v0, "$SHORT_FORM_VIDEO_UNIT"
            iget-object v0, p1, $handlerField
            if-eqz v0, :none
            const-string v1, "played"
            :none
            return-void
        """, static = true),
        // What the view makes: its gesture listener, the second reader.
        method(view, "makeListener", emptyList(), listener, """
            new-instance v0, $listener
            return-object v0
        """),
    )

    private fun listenerClass() = classDef(
        listener,
        method(listener, "onDoubleTap", listOf(MOTION_EVENT), "Z", """
            iget-object v0, p0, $handlerField
            if-eqz v0, :none
            const/4 v0, 0x1
            return v0
            :none
            const/4 v0, 0x0
            return v0
        """),
    )

    /** The event subscriber. [checked] false drops the null check its read must have. */
    private fun eventSubscriberClass(checked: Boolean) = classDef(
        eventSubscriber,
        method(
            eventSubscriber, "onEvent", listOf("Ljava/lang/Object;"), "V",
            if (checked) {
                """
                    iget-object v0, p0, $handlerField
                    if-eqz v0, :none
                    :none
                    return-void
                """
            } else {
                """
                    iget-object v0, p0, $handlerField
                    const-string v1, "no null check follows this read"
                    return-void
                """
            },
        ),
    )

    private fun build(eventSubscriberChecked: Boolean = true): BytecodePatchContext = PatchContexts.of(
        listOf(
            helperClass(), attachment(), componentClass(), gestureView(), listenerClass(),
            eventSubscriberClass(eventSubscriberChecked), ExtensionDex.classDef(SETTINGS_STATUS),
        ),
    )

    private fun instructionCount(context: BytecodePatchContext, owner: String, name: String) =
        context.mutableClassDefBy(owner).methods.single { it.name == name }.implementation!!.instructions.count()

    @Test
    fun `a sound build applies and hooks every reader`() {
        val context = build()
        val before = listOf(helper to "like", helper to "doubleTapLike", attachmentClass to "onDoubleTap",
            view to "heart", listener to "onDoubleTap", eventSubscriber to "onEvent")
            .associateWith { (owner, name) -> instructionCount(context, owner, name) }

        turnOffDoubleTapLikePatch.execute(context)

        before.forEach { (ownerAndName, originalCount) ->
            val (owner, name) = ownerAndName
            val grown = instructionCount(context, owner, name) - originalCount
            assertTrue("$owner->$name should have grown by a hook, was $originalCount now ${instructionCount(context, owner, name)}", grown > 0)
        }
    }

    /**
     * The event subscriber, the last reader hookGestureView looks at, has no null check after its
     * read. The whole hook must refuse before touching anything: the reel like helper's like and
     * double-tap like, the attachment, the heart and the listener all stay exactly as built.
     */
    @Test
    fun `a bad last reader refuses the patch before anything else changes`() {
        val context = build(eventSubscriberChecked = false)
        val before = listOf(helper to "like", helper to "doubleTapLike", attachmentClass to "onDoubleTap",
            view to "heart", listener to "onDoubleTap")
            .associateWith { (owner, name) -> instructionCount(context, owner, name) }

        val message = assertThrows(PatchException::class.java) { turnOffDoubleTapLikePatch.execute(context) }.message!!
        assertTrue(message, message.contains("without checking it for null straight away"))

        before.forEach { (ownerAndName, originalCount) ->
            val (owner, name) = ownerAndName
            assertEquals("$owner->$name changed even though a later anchor refused", originalCount, instructionCount(context, owner, name))
        }
    }
}

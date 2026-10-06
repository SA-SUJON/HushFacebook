/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI character side of Hide AI-detected posts, found in the Facebook builds the bundle
 * declares, the way the patch finds it: GraphQLStory's one accessor of its attachments,
 * GraphQLStoryAttachment's one accessor of its style_infos, and the one static finder the AI
 * character style's literal is handed to, public in a public class and walking style_infos by
 * getTypeName(). The compiled extension's holders of the literal are searched beside Facebook's,
 * as the patcher sees them. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class AiCharacterFixtureTest {
    @Test
    fun `every declared build has the attachments, their style list and one public style finder`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val extensionHolders = ExtensionDex.classes().flatMap { methodsHolding(it, AI_CHARACTER_STYLE) }
        assertTrue("the extension no longer holds \"$AI_CHARACTER_STYLE\", so the merged search below is no " +
            "harder than Facebook's alone: drop this check", extensionHolders.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val models = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY, GRAPHQL_STORY_ATTACHMENT))
                val story = models[GRAPHQL_STORY] ?: throw AssertionError("${bundle.name} has no $GRAPHQL_STORY")
                val attachment = models[GRAPHQL_STORY_ATTACHMENT]
                    ?: throw AssertionError("${bundle.name} has no $GRAPHQL_STORY_ATTACHMENT")
                val attachmentLists = attachmentsAccessors(story)
                assertEquals("${bundle.name}: GraphQLStory's attachments accessors ${attachmentLists.map { it.name }}",
                    1, attachmentLists.size)
                val styleLists = styleInfosAccessors(attachment)
                assertEquals("${bundle.name}: GraphQLStoryAttachment's style_infos accessors ${styleLists.map { it.name }}",
                    1, styleLists.size)
                val styleInfos = styleLists.single()

                // The control: the attachment's own list of attachments is read the same way under
                // another key, and isn't taken for the story's.
                assertTrue("${bundle.name}: GraphQLStoryAttachment declares no other list read as StoryAttachment",
                    attachment.methods.any {
                        isModelListAccessor(it, GRAPHQL_STORY_ATTACHMENT, "subattachments", ATTACHMENT_TYPE, GRAPHQL_STORY_ATTACHMENT)
                    })
                assertTrue("${bundle.name}: an attachment's own list passes for the story's attachments",
                    attachment.methods.none { isModelListAccessor(it, GRAPHQL_STORY, ATTACHMENTS_FIELD, ATTACHMENT_TYPE) })

                val holders = FixtureDex.classesHolding(bundle, AI_CHARACTER_STYLE)
                    .flatMap { methodsHolding(it, AI_CHARACTER_STYLE) }
                assertTrue("${bundle.name}: only ${holders.size} methods hold \"$AI_CHARACTER_STYLE\"", holders.size >= 2)
                val found = attributionFinder(holders + extensionHolders, AI_CHARACTER_STYLE)
                assertNull("${bundle.name}: ${found.problem}", found.problem)
                val finder = found.call!!
                val finderClass = FixtureDex.classes(bundle, setOf(finder.definingClass))[finder.definingClass]
                    ?: throw AssertionError("${bundle.name} has no ${finder.definingClass}")
                val method = resolveStatic(finderClass, finder)
                    ?: throw AssertionError("${bundle.name}: ${finder.definingClass} declares no static ${finder.name}")
                assertTrue("${bundle.name}: ${finder.definingClass}->${finder.name} isn't a public style finder over " +
                    "${styleInfos.name}()", isStyleFinder(method, finderClass, styleInfos))

                // The control: the finder's siblings taking the same parameters don't pass for it.
                val siblings = finderClass.methods.filter {
                    it.name != finder.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
                        it.parameterTypes.map { p -> p.toString() } == finder.parameterTypes.map { p -> p.toString() }
                }
                for (sibling in siblings) {
                    assertFalse("${bundle.name}: ${sibling.name} passes for the style finder too",
                        isStyleFinder(sibling, finderClass, styleInfos))
                }
                checked += version
            }
        }
        assertEquals("a declared build went unchecked", versions.toSet(), checked)
    }
}

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Route one of the AMOLED and Material You themes as the patch runs it, on each declared build's
 * own colour classes: four resolvers and six returns, each hook reading the colour token from a
 * register that still holds it there. A build whose resolver reused the token's register before a
 * return would stop here, naming it. The status bar hook of the AMOLED theme is checked on the same
 * builds, with the path that paints a tab's bar again after Recent Apps.
 */
class ColourResolverFixtureTest {
    @Before
    @After
    fun forgetMatches() {
        DarkSchemeResolveFingerprint.clearMatch()
        FdsSchemeResolveFingerprint.clearMatch()
    }

    /** The class the FdsColorScheme wrapper hands the context and token to: the view resolver's. */
    private fun viewResolverClass(scheme: ClassDef): String = scheme.methods.mapNotNull { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val readsContext = instructions.any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { field ->
                field.definingClass == FDS_COLOR_SCHEME && field.type == "Landroid/content/Context;"
            } == true
        }
        if (method.returnType != "I" || !readsContext) return@mapNotNull null
        instructions.firstNotNullOfOrNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                ?.takeIf { instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "I" }
                ?.definingClass
        }
    }.distinct().single()

    @Test
    fun `route one hooks four resolvers and six returns, each token intact, on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val named = FixtureDex.classes(bundle, setOf(DARK_COLOR_SCHEME, FDS_COLORS, FDS_COLOR_SCHEME))
                val viewResolver = viewResolverClass(named.getValue(FDS_COLOR_SCHEME))
                val classes = named + FixtureDex.classes(bundle, setOf(viewResolver))
                val context = PatchContexts.of(classes.values)

                with(context) {
                    DarkSchemeResolveFingerprint.method.hookColorReturns(tokenParameterIndex = 0, target = APPLY)
                    hookFdsColorsResolvers(target = APPLY)
                    fdsViewResolver().hookColorReturns(tokenParameterIndex = 1, target = APPLY)
                }

                val hooked = classes.keys.flatMap { type -> context.mutableClassDefBy(type).methods }
                    .associate { method ->
                        "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})" to
                            (method.implementation?.instructions?.count {
                            (it as? ReferenceInstruction)?.reference?.toString() == APPLY
                        } ?: 0)
                    }
                    .filterValues { it > 0 }
                assertEquals("${bundle.name}: resolvers hooked, $hooked", 4, hooked.size)
                assertEquals("${bundle.name}: returns hooked, $hooked", 6, hooked.values.sum())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private val systemBarsController = "Lcom/facebook/navigation/statusbar/controller/SystemBarsController;"

    private fun Method.calls(descriptor: String) = implementation?.instructions?.any {
        (it as? ReferenceInstruction)?.reference?.toString() == descriptor
    } == true

    private fun Method.descriptor() =
        "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /**
     * Issue #22. Back from Recent Apps, SystemBarsController applies the tab's bar config again. A
     * config holding only a token gets its colour from an FDS resolver none of route one's hooks
     * reach (the Video tab's CARD_BACKGROUND_DARK is #333334), and the controller hands it to
     * StatusBarUtil's painter, which remembers it per window. The hook sits first in that painter,
     * so the colour painted and the colour remembered are both the extension's answer.
     */
    @Test
    fun `the status bar painter takes the hook on the path that reapplies a tab's bars, on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val name = bundle.name
                val named = FixtureDex.classes(bundle, setOf(FDS_COLOR_SCHEME, STATUS_BAR_UTIL, systemBarsController))
                val viewResolver = viewResolverClass(named.getValue(FDS_COLOR_SCHEME))
                val classes = named + FixtureDex.classes(bundle, setOf(viewResolver))
                val context = PatchContexts.of(classes.values)

                val (darkCheck, resolver) = with(context) {
                    val check = fdsDarkCheck()
                    hookStatusBarColour(check)
                    check to fdsViewResolver().descriptor()
                }

                val hooked = context.mutableClassDefBy(STATUS_BAR_UTIL).methods.filter { it.calls(STATUS_BAR) }
                assertEquals("$name: StatusBarUtil methods hooked", 1, hooked.size)
                val painter = hooked.single()
                val original = classes.getValue(STATUS_BAR_UTIL).methods.single { it.descriptor() == painter.descriptor() }
                assertStatusBarHook(name, painter, darkCheck, original.implementation!!.instructions.toList())

                // The dark check is FDS's own: the question its colour picker asks before it takes a
                // colour's darkThemeColor over its lightThemeColor.
                val owner = classes.getValue(viewResolver)
                val question = owner.methods.single { it.descriptor() == darkCheck }.implementation!!.instructions
                    .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    .single { it.returnType == "Z" }.toString()
                val picker = owner.methods.single { method ->
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "darkThemeColor"
                    } == true
                }
                assertTrue("$name: the dark check asks $question, the darkThemeColor picker doesn't", picker.calls(question))

                // The path back from Recent Apps: the controller's apply step, which skips a config
                // "same as current for window", paints through the hooked painter and asks the same
                // dark check for the bar's icons, but no route-one resolver for the colour.
                val apply = classes.getValue(systemBarsController).methods.single {
                    holdsString(it, "applyConfig: skipped (same as current for window)")
                }
                assertTrue("$name: the controller doesn't paint through the hooked painter", apply.calls(painter.descriptor()))
                assertTrue("$name: the controller doesn't ask $darkCheck", apply.calls(darkCheck))
                assertFalse("$name: the controller now asks the hooked view resolver", apply.calls(resolver))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

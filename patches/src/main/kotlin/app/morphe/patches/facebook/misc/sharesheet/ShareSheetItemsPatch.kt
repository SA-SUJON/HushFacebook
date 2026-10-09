/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharesheet

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Lets people pick which items Facebook's share sheet shows. The hook is shareSheetHookPatch's;
 * this turns on the extension's rule and the list under Links in Hushfacebook settings, where the
 * item types Facebook has offered on the phone are listed beside the common ones.
 *
 * The idea is icysymmetra/tiktok-patches-for-morphe's Share sheet modification, which does the same
 * for TikTok's share sheet.
 *
 * In the default selection, but it changes nothing until it's turned on in Hushfacebook settings:
 * nobody has seen it on a signed-in account yet.
 */
@Suppress("unused")
val shareSheetItemsPatch = bytecodePatch(
    name = "Share sheet items",
    description = "Lets you hide items you never use from Facebook's share sheet, such as WhatsApp, Meta AI or " +
        "Copy link, so the ones you want are easier to reach. Nothing is hidden until you pick. Choose them in " +
        "Hushfacebook settings > Links.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch, shareSheetHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        enableStatus("shareSheetItems")
    }
}

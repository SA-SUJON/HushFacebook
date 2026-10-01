/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.hold

import app.morphe.patches.facebook.media.reelspeed.keepReelSpeedPatch
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both reel speed patches bring the release guard, so a tap can't undo a picked speed whichever is
 * selected (issue #25). A dependency declared later in the same file than its patch would read as
 * null here, which Morphe would only trip over while patching.
 */
class ReelLiftGuardPatchTest {
    @Test
    fun `both reel speed patches bring the release guard`() {
        assertTrue("Hold a reel for 2x doesn't bring the release guard", reelLiftGuardPatch in holdReelFor2xPatch.dependencies)
        assertTrue("Keep the reel speed doesn't bring the release guard", reelLiftGuardPatch in keepReelSpeedPatch.dependencies)
    }
}

/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.KeyguardManager;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

/** Lock Facebook as another package's test sees it. */
public final class AppLockForTests {
    private AppLockForTests() {
    }

    /**
     * A cold start on a phone with a screen lock, with Android's prompt stood in for: true when it
     * covered the screen or asked for the screen lock. Back to a fresh process after.
     */
    public static boolean aStartLocks() {
        AppLock.forgetForTests();
        int[] asked = {0};
        AppLock.prompter = (activity, answer) -> asked[0]++;
        KeyguardManager keyguard = RuntimeEnvironment.getApplication().getSystemService(KeyguardManager.class);
        shadowOf(keyguard).setIsDeviceSecure(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AppLock.started(activity);
            AppLock.resumed(activity);
            ShadowLooper.idleMainLooper();
            return AppLock.covered(activity) || asked[0] > 0;
        } finally {
            AppLock.forgetForTests();
        }
    }
}

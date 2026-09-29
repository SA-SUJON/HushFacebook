/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import java.util.ArrayList;
import java.util.List;

/** What tests outside this package need of Keep the reel speed: one pick and the next reel. */
public final class ReelSpeedForTests {
    private ReelSpeedForTests() { }

    /**
     * Picks 1.5x on a reel in the Reels viewer, then starts the next reel there. True when the next
     * reel got the picked speed. Leaves nothing kept behind.
     */
    public static boolean keepsAPickedSpeed() {
        ReelSpeed.forget();
        List<Float> set = new ArrayList<>();
        ReelSpeed.access = new ReelSpeed.Player() {
            @Override
            public void setSpeed(Object player, float speed) {
                set.add(speed);
            }

            @Override
            public Object origin(Object player) {
                return "fb_shorts_viewer";
            }
        };
        try {
            ReelSpeed.speedSet(new Object(), 1.5f);
            ReelSpeed.picked(1.5f);
            Object next = new Object();
            ReelSpeed.bound(next);
            ReelSpeed.started(next);
            return !set.isEmpty();
        } finally {
            ReelSpeed.forget();
        }
    }
}

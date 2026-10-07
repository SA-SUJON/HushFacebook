/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsEntry;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Lock Facebook: with its switch on, a cold start and a return after the chosen time cover every
 * Facebook screen and ask for the phone's own screen lock, its fingerprint, face, PIN, pattern or
 * password, through Android's BiometricPrompt. Only a check that passes takes the cover away. One
 * that fails or is cancelled leaves Facebook covered, with an Unlock button to ask again, and Back
 * sends Facebook to the background.
 *
 * <p>Facebook carries an app lock of its own, {@code AuthAppLockState}, but in 577, 580 and 581
 * nothing outside its own class reads the "lock due" flag its start sets, and its other readers
 * only keep the screen out of the recent apps view, log, or filter a promotion. There's no lock
 * screen behind it to turn on, so this is the extension's own.
 *
 * <p>Its own activity callbacks drive it ({@link #watch}), registered in every one of Facebook's
 * processes: Instant Games, the Audience Network ads and Facebook's crash screen run in processes
 * of their own, and each process locks on its own cold start and its own time away.
 *
 * <p>Facebook is in front while one of its screens is started and isn't a picture-in-picture
 * window. "Away" starts when the last of those stops, or shrinks into a picture-in-picture window,
 * which Android reports as a pause with the screen already in that mode. The floating window keeps
 * playing and is never covered, but the first full-screen Facebook screen after the chosen time
 * asks, the video brought back to full screen included. A reply typed into a notification runs
 * without any screen, so it starts nothing. A rotation, or a screen Facebook rebuilds for a new
 * configuration, isn't a leave. While the switch is on, Android 13 and later keep Facebook's
 * screens out of the recent apps view, as Facebook's own lock does.
 *
 * <p>Off, settings that aren't ready yet, or a phone without a screen lock, and nothing is covered
 * or asked. Turning the switch on doesn't lock the screen in front. A pause doesn't turn it off,
 * whatever paused Hushfacebook: the Pause switch, the marker file, or safe mode after crashed
 * starts. Each of those is in reach of someone holding the phone, and the switch, which sits
 * behind the lock, is the way off ({@link Settings#APP_LOCK} keeps its value while paused).
 */
public final class AppLock {
    /** How long Facebook may be away before a return asks again. */
    public enum After {
        IMMEDIATELY(0L, "immediately"),
        ONE_MINUTE(60_000L, "1_minute"),
        FIVE_MINUTES(5 * 60_000L, "5_minutes"),
        FIFTEEN_MINUTES(15 * 60_000L, "15_minutes"),
        ONE_HOUR(60 * 60_000L, "1_hour");

        public final long millis;

        /** What a settings file holds for this choice. It never changes once written. */
        public final String fileValue;

        After(long millis, String fileValue) {
            this.millis = millis;
            this.fileValue = fileValue;
        }

        /** The choice a settings file names, or null when it names none this build knows. */
        @Nullable
        public static After fromFile(@Nullable Object value) {
            if (!(value instanceof String)) return null;
            for (After after : values()) {
                if (after.fileValue.equals(value)) return after;
            }
            return null;
        }
    }

    /** Asks for the phone's screen lock over [activity]: Android's prompt, or a test's stand-in. */
    interface Prompter {
        void ask(Activity activity, Answer answer);
    }

    /** What a check came to. Called on the main thread. */
    interface Answer {
        void unlocked();

        /** The check ended without passing, with Android's reason, which it words in the phone's language. */
        void refused(int code, @Nullable CharSequence message);
    }

    /** BiometricPrompt's code for a check Android itself called off, as when the screen went away. */
    static final int CALLED_OFF = BiometricPrompt.BIOMETRIC_ERROR_CANCELED;

    private static final long NEVER = -1;

    /** How long after a check ends a cover that hasn't got the focus back goes on top again. */
    static final long SETTLE_MS = 500;

    static Prompter prompter = AppLock::askAndroid;

    /** Facebook screens between their start and their stop. */
    private static int started;
    /** The started screens that are picture-in-picture windows, as their last pause found them. */
    private static final Set<Activity> floating = Collections.newSetFromMap(new WeakHashMap<>());
    /**
     * When the last full-screen Facebook screen left, or {@link #NEVER} while one is in front or the
     * lock is off.
     */
    private static long leftAt = NEVER;
    /** A check passed in this process, or it ran with the lock off, so a start alone doesn't lock. */
    private static boolean everUnlocked;
    /** The screens are covered until a check passes. */
    private static boolean locked;
    /** The Facebook screen in front, between its resume and its pause. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** A check is on screen. */
    private static boolean asking;
    /** The screen the check was asked over, so its going away ends the wait for an answer. */
    @Nullable
    private static WeakReference<Activity> askedOn;
    /** The person called the last check off, so the next one waits for Unlock or a return. */
    private static boolean declined;
    /** Android's reason the last check ended, shown on the cover until the next one. */
    @Nullable
    private static CharSequence refusal;
    private static final Map<Activity, Cover> covers = new WeakHashMap<>();
    private static final Set<Activity> keptFromRecents = Collections.newSetFromMap(new WeakHashMap<>());

    /** The application this process's callbacks went on, so a second start of the hook adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    private AppLock() {
    }

    /**
     * From the settings entry's application hook, in every one of Facebook's processes. Its
     * callbacks go on after the entry's, so on a resume the cover is the newest window, over
     * anything the entry's callbacks opened. Facebook's application only wraps the callbacks it's
     * handed, in any process, so registering them early in a side process is safe.
     */
    public static void watch(Context context) {
        if (!(context instanceof Application)) return;
        Application application = (Application) context;
        if (watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new Watcher());
        watching = new WeakReference<>(application);
    }

    /** The lock's own activity callbacks. */
    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
            started(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            resumed(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            paused(activity);
        }

        @Override
        public void onActivityStopped(Activity activity) {
            stopped(activity);
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            destroyed(activity);
        }
    }

    /** Whether the lock is on: its switch, which reads the same paused or not, once the settings are ready. */
    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.APP_LOCK.get();
    }

    /** Whether a Facebook screen is in front: started, and not a picture-in-picture window. */
    private static boolean inFront() {
        return started > floating.size();
    }

    /** Whether [activity] is a picture-in-picture window. */
    private static boolean inPictureInPicture(Activity activity) {
        return activity.isInPictureInPictureMode();
    }

    /**
     * From the lock's callbacks, as a Facebook screen starts. The first start with no
     * Facebook screen in front is a return, and it locks when the lock is due. A picture-in-picture
     * window that starts again, as it does when the phone is unlocked, isn't one: it floats, so the
     * time away keeps running until a full-screen Facebook screen comes back.
     */
    public static void started(Activity activity) {
        boolean returning = !inFront();
        started++;
        try {
            if (inPictureInPicture(activity)) {
                floating.add(activity);
                return;
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not judge a floating start", failure);
        }
        if (returning) returned(activity);
    }

    /**
     * A Facebook screen came to the front with none there before it: a start, or a
     * picture-in-picture window brought back to full screen. Locks when the lock is due.
     */
    private static void returned(Activity activity) {
        try {
            if (!Utils.settingsReady()) return;
            if (!Settings.APP_LOCK.get() || !canLock(activity)) {
                // Nothing to ask with, or nothing asked for: turning the lock on later won't lock this
                // screen. A lock already up goes too, since with the phone's screen lock gone no check
                // could ever pass.
                if (locked) release(null);
                everUnlocked = true;
                leftAt = NEVER;
                return;
            }
            if (!locked && due(SystemClock.elapsedRealtime())) {
                locked = true;
                Logger.printInfo(() -> "App lock: locked on " + (everUnlocked ? "a return" : "a cold start"));
            }
            declined = false;
            refusal = null;
            leftAt = NEVER;
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not judge a start", failure);
        }
    }

    /** Whether a return now asks: always on a cold start, otherwise after the chosen time away. */
    static boolean due(long now) {
        if (!everUnlocked) return true;
        return leftAt != NEVER && now - leftAt >= Settings.APP_LOCK_AFTER.get().millis;
    }

    /**
     * From the lock's callbacks, as a Facebook screen leaves the front. A screen that
     * pauses as a picture-in-picture window no longer counts as Facebook in front, and when it was
     * the last one that did, the time away starts.
     */
    public static void paused(Activity activity) {
        try {
            if (front.get() == activity) front = new WeakReference<>(null);
            if (!inPictureInPicture(activity) || floating.contains(activity) || !inFront()) return;
            floating.add(activity);
            if (!inFront()) leftAt = SystemClock.elapsedRealtime();
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not judge a pause", failure);
        }
    }

    /**
     * From the lock's callbacks, as a Facebook screen stops. The last full-screen one to
     * stop starts the time away.
     */
    public static void stopped(Activity activity) {
        boolean wasInFront = inFront();
        if (started > 0) started--;
        floating.remove(activity);
        if (wasInFront && !inFront() && !activity.isChangingConfigurations()) {
            leftAt = SystemClock.elapsedRealtime();
        }
    }

    /**
     * From the lock's callbacks, as a Facebook screen comes to the front: covered while
     * locked, and the check asked for unless the person just called one off. A picture-in-picture
     * window is left as it is, and one brought back to full screen is a return.
     */
    public static void resumed(Activity activity) {
        try {
            front = new WeakReference<>(activity);
            if (floating.contains(activity) && !inPictureInPicture(activity)) {
                boolean returning = !inFront();
                floating.remove(activity);
                if (returning) returned(activity);
            }
            if (!switchedOn()) {
                showInRecents(activity);
                if (locked) release(null);
                return;
            }
            keepFromRecents(activity);
            if (locked && !canLock(activity)) {
                // The phone's screen lock was taken away while Facebook was locked: nothing could pass.
                Logger.printInfo(() -> "App lock: the phone has no screen lock now, so Facebook opens");
                release(null);
            }
            if (!locked || inPictureInPicture(activity)) return;
            cover(activity);
            if (!asking && !declined) ask(activity);
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not lock a screen", failure);
        }
    }

    /** From the lock's callbacks, as a Facebook screen goes away for good. */
    public static void destroyed(Activity activity) {
        Cover cover = covers.remove(activity);
        if (cover != null) cover.close();
        keptFromRecents.remove(activity);
        floating.remove(activity);
        if (front.get() == activity) front = new WeakReference<>(null);
        // Android ends a check whose screen goes, and an answer that never comes mustn't stop the next one.
        WeakReference<Activity> asked = askedOn;
        if (asked != null && asked.get() == activity) {
            asking = false;
            askedOn = null;
        }
    }

    /** Whether Facebook is covered, so nothing of Hushfacebook's opens over the cover. */
    public static boolean covering() {
        return locked;
    }

    /** Whether [activity] carries a cover right now. */
    static boolean covered(Activity activity) {
        Cover cover = covers.get(activity);
        return cover != null && cover.isShowing();
    }

    /**
     * Whether the phone has a screen lock this could ask for. Without one Facebook is never covered,
     * and a cover already up goes, since no check could pass.
     */
    public static boolean canLock(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    private static void keepFromRecents(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && keptFromRecents.add(activity)) activity.setRecentsScreenshotEnabled(false);
    }

    private static void showInRecents(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && keptFromRecents.remove(activity)) activity.setRecentsScreenshotEnabled(true);
    }

    private static void cover(Activity activity) {
        Cover cover = covers.get(activity);
        if (cover != null && cover.isShowing()) {
            // Already up: back on top, over anything Facebook opened on the screen while it was away.
            raise(activity);
            return;
        }
        if (cover == null) {
            try {
                cover = new Cover(activity);
                covers.put(activity, cover);
            } catch (RuntimeException failure) {
                // A screen that can't be covered isn't left showing: Facebook goes to the background.
                Logger.printException(() -> "App lock: could not cover " + activity.getClass().getSimpleName(), failure);
                activity.moveTaskToBack(true);
                return;
            }
        }
        cover.showReason();
        if (!cover.isShowing()) cover.show();
    }

    /**
     * Puts a fresh cover on [activity] and then takes the old one away, so the cover is the newest
     * of the screen's windows again and nothing shows between the two. A dialog or sheet Facebook
     * opened over the old cover ends up under it.
     */
    private static void raise(Activity activity) {
        Cover old = covers.get(activity);
        if (old == null || activity.isFinishing() || activity.isDestroyed()) return;
        // Its loss of focus to the fresh one is no reason to move again.
        old.retired = true;
        Cover fresh;
        try {
            fresh = new Cover(activity);
            fresh.show();
        } catch (RuntimeException failure) {
            old.retired = false;
            Logger.printException(() -> "App lock: could not put the cover back on top", failure);
            return;
        }
        covers.put(activity, fresh);
        old.close();
    }

    /**
     * After [cover] lost the focus, or a check ended without it getting the focus back. When
     * Facebook is still locked, [activity] is still in front and no check is on screen, the window
     * holding the focus is one Facebook opened on the screen over the cover, so a fresh cover goes
     * on top of it. Android's own windows, the notification shade among them, sit above every app
     * window anyway, and a cover put back under one of them changes nothing.
     */
    private static void keepOnTop(Activity activity, Cover cover) {
        if (!locked || asking || cover.retired || cover.focused || covers.get(activity) != cover) return;
        if (front.get() != activity) return;
        Logger.printInfo(() -> "App lock: another window took the focus from the cover, so the cover went back on top");
        raise(activity);
    }

    /**
     * Asks for the screen lock once [activity] has finished coming to the front, which Android
     * wants of an app that asks.
     */
    private static void ask(Activity activity) {
        asking = true;
        askedOn = new WeakReference<>(activity);
        Utils.runOnMainThread(() -> {
            if (!locked || activity.isFinishing() || activity.isDestroyed()) {
                asking = false;
                return;
            }
            try {
                prompter.ask(activity, new Answer() {
                    @Override
                    public void unlocked() {
                        asking = false;
                        Logger.printInfo(() -> "App lock: unlocked");
                        release(activity);
                    }

                    @Override
                    public void refused(int code, @Nullable CharSequence message) {
                        asking = false;
                        // Android calling a check off, as when the screen goes, isn't the person saying no.
                        declined = code != CALLED_OFF;
                        refusal = message;
                        Logger.printInfo(() -> "App lock: the check ended with code " + code + ", Facebook stays covered");
                        for (Cover cover : covers.values()) cover.showReason();
                        // A dialog Facebook opened while the check was up sits over the cover, which
                        // then never gets the focus back to notice. Once the check has gone, it does.
                        Activity shown = front.get();
                        if (shown == null) return;
                        Utils.runOnMainThreadDelayed(() -> {
                            Cover cover = covers.get(shown);
                            if (cover != null) keepOnTop(shown, cover);
                        }, SETTLE_MS);
                    }
                });
            } catch (Throwable failure) {
                asking = false;
                declined = true;
                Logger.printException(() -> "App lock: could not ask for the screen lock", failure);
            }
        });
    }

    /** Takes every cover away, after a check passed or with the lock off. */
    private static void release(@Nullable Activity unlockedOn) {
        locked = false;
        everUnlocked = true;
        leftAt = NEVER;
        declined = false;
        refusal = null;
        for (Cover cover : new ArrayList<>(covers.values())) cover.close();
        covers.clear();
        if (unlockedOn != null && !unlockedOn.isFinishing()) SettingsEntry.openIfRequested(unlockedOn);
    }

    /** Back to a fresh process, covers closed. */
    static void forgetForTests() {
        for (Cover cover : new ArrayList<>(covers.values())) cover.close();
        covers.clear();
        keptFromRecents.clear();
        floating.clear();
        front = new WeakReference<>(null);
        started = 0;
        leftAt = NEVER;
        everUnlocked = false;
        locked = false;
        asking = false;
        askedOn = null;
        declined = false;
        refusal = null;
        prompter = AppLock::askAndroid;
    }

    /** Android's own prompt: a biometric the phone counts as at least weak, or its PIN, pattern or password. */
    private static void askAndroid(Activity activity, Answer answer) {
        BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                .setTitle(L10n.t("Unlock Facebook"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build();
        prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        answer.unlocked();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        answer.refused(code, message);
                    }
                });
    }

    /**
     * An opaque window over a Facebook screen, added after the windows it already has, so a sheet
     * or dialog Facebook left open stays under it too. One Facebook opens later lands above it and
     * takes the focus, and the cover answers by going back on top ({@link #keepOnTop}). It takes
     * every touch, and Back sends Facebook to the background rather than closing it. It comes and
     * goes without an animation, so a fresh one put on top shows nothing of the screen between.
     */
    private static final class Cover extends Dialog {
        private final Activity activity;
        private final TextView reason;
        /** A fresh cover replaced it, so its own loss of focus means nothing. */
        boolean retired;
        /** Whether its window has the focus, as Android last said. */
        boolean focused;

        Cover(Activity activity) {
            super(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
            this.activity = activity;
            setCancelable(false);
            setCanceledOnTouchOutside(false);
            float density = activity.getResources().getDisplayMetrics().density;
            int gap = Math.round(24 * density);

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            column.setPadding(gap, gap, gap, gap);
            column.setBackgroundColor(Color.BLACK);

            TextView title = new TextView(activity);
            title.setText(L10n.t("Facebook is locked"));
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            title.setGravity(Gravity.CENTER);
            column.addView(title);

            reason = new TextView(activity);
            reason.setTextColor(0xFFB0B3B8);
            reason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            reason.setGravity(Gravity.CENTER);
            reason.setPadding(0, gap / 2, 0, gap);
            column.addView(reason);

            Button unlock = new Button(activity);
            unlock.setText(L10n.t("Unlock"));
            // Asks again even while a check may still be on its way: one that never answered mustn't trap Facebook.
            unlock.setOnClickListener(view -> {
                if (!locked) return;
                if (!canLock(activity)) {
                    // No screen lock to ask for any more: asking would fail forever.
                    release(activity);
                    return;
                }
                declined = false;
                refusal = null;
                showReason();
                ask(activity);
            });
            column.addView(unlock);

            setContentView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setWindowAnimations(0);
            }
            setOnKeyListener((dialog, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) activity.moveTaskToBack(true);
                return true;
            });
            showReason();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            focused = hasFocus;
            // Judged once the change has settled, when the screen's own pause has been heard.
            if (!hasFocus && !retired) Utils.runOnMainThread(() -> keepOnTop(activity, this));
        }

        /** Android's reason the last check ended, or what unlocks Facebook. */
        void showReason() {
            CharSequence why = refusal;
            reason.setText(why != null && why.length() > 0 ? why
                    : L10n.t("Use your fingerprint, face or screen lock to open it."));
        }

        void close() {
            try {
                dismiss();
            } catch (RuntimeException gone) {
                // Its screen's window is already gone, and the cover with it.
            }
        }
    }
}

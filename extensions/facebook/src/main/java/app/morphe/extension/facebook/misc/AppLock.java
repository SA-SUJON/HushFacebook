/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
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
 * <p>The settings entry's activity callbacks drive it. "Away" starts when the last Facebook screen
 * stops, so a video in picture-in-picture, which keeps its screen started, is never away, and a
 * screen in picture-in-picture is never covered. A reply typed into a notification runs without
 * any screen, so it starts nothing either. A rotation, or a screen Facebook rebuilds for a new
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

    static Prompter prompter = AppLock::askAndroid;

    /** Facebook screens between their start and their stop. */
    private static int started;
    /** When the last of them stopped, or {@link #NEVER} while one is started or the lock is off. */
    private static long leftAt = NEVER;
    /** A check passed in this process, or it ran with the lock off, so a start alone doesn't lock. */
    private static boolean everUnlocked;
    /** The screens are covered until a check passes. */
    private static boolean locked;
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

    private AppLock() {
    }

    /** Whether the lock is on: its switch, which reads the same paused or not, once the settings are ready. */
    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.APP_LOCK.get();
    }

    /**
     * From the settings entry's callbacks, as a Facebook screen starts. The first start after every
     * screen stopped is a return, and it locks when the lock is due.
     */
    public static void started(Activity activity) {
        boolean returning = started == 0;
        started++;
        if (!returning) return;
        try {
            if (!Utils.settingsReady()) return;
            if (!Settings.APP_LOCK.get() || !canLock(activity)) {
                // Nothing to ask with, or nothing asked for: turning the lock on later won't lock this screen.
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

    /** From the settings entry's callbacks, as a Facebook screen stops. The last one to stop starts the time away. */
    public static void stopped(Activity activity) {
        if (started > 0) started--;
        if (started == 0 && !activity.isChangingConfigurations()) leftAt = SystemClock.elapsedRealtime();
    }

    /**
     * From the settings entry's callbacks, as a Facebook screen comes to the front: covered while
     * locked, and the check asked for unless the person just called one off. A screen in
     * picture-in-picture is left as it is.
     */
    public static void resumed(Activity activity) {
        try {
            if (!switchedOn()) {
                showInRecents(activity);
                if (locked) release(null);
                return;
            }
            keepFromRecents(activity);
            if (!locked || activity.isInPictureInPictureMode()) return;
            cover(activity);
            if (!asking && !declined) ask(activity);
        } catch (Throwable failure) {
            Logger.printException(() -> "App lock: could not lock a screen", failure);
        }
    }

    /** From the settings entry's callbacks, as a Facebook screen goes away for good. */
    public static void destroyed(Activity activity) {
        Cover cover = covers.remove(activity);
        if (cover != null) cover.close();
        keptFromRecents.remove(activity);
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

    /** Whether the phone has a screen lock this could ask for. */
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
     * or dialog Facebook left open stays under it too. It takes every touch, and Back sends
     * Facebook to the background rather than closing it.
     */
    private static final class Cover extends Dialog {
        private final TextView reason;

        Cover(Activity activity) {
            super(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
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
                declined = false;
                refusal = null;
                showReason();
                if (locked) ask(activity);
            });
            column.addView(unlock);

            setContentView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            setOnKeyListener((dialog, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) activity.moveTaskToBack(true);
                return true;
            });
            showReason();
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

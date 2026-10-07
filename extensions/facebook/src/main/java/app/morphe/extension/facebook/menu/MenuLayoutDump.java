/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.facebook.navigation.FacebookTabs;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * With Debug logging on, the Menu Facebook builds is written to the log a moment later, cut down
 * to the parts naming Muse, so one report names the component behind the Muse card, which no
 * Menu hook sees (see the roadmap's Hide the Muse card in Menu). Litho's own
 * LithoViewTestHelper.viewToString, which Facebook keeps, describes each Litho tree on the main
 * screen, and Android's own text search finds the views whose text or description names Muse.
 * Off, it does nothing at all.
 *
 * <p>At most {@link #DUMPS_MAX} times a run, {@link #GAP_MS} apart, since Facebook can build the
 * Menu before it's on screen, and never again once one found the card.
 */
public final class MenuLayoutDump {
    /** What the Muse card shows: its title's name and its button. */
    static final String[] WANTED = {"Muse", "Get app"};

    /** How long after Menu builds the layout is read, so Litho has drawn it. */
    static final long DELAY_MS = 2_000;

    static final int DUMPS_MAX = 3;
    static final long GAP_MS = 15_000;

    /** Lines kept from one Litho tree, and Litho trees read from one screen. */
    static final int LINES_MAX = 80;
    static final int TREES_MAX = 200;
    static final int LINE_CHARS_MAX = 240;

    private static final String MOUNTING_VIEW = "com.facebook.litho.BaseMountingView";
    private static final String HELPER = "com.facebook.litho.LithoViewTestHelper";

    private static volatile WeakReference<Activity> mainScreen = new WeakReference<>(null);
    private static int dumps;
    private static long lastDump = -GAP_MS;
    private static boolean found;

    private MenuLayoutDump() {
    }

    /** From each Facebook screen's onCreate: keeps the main screen, the one Menu is drawn in. */
    public static void screenCreated(@Nullable Activity activity) {
        if (activity != null && FacebookTabs.MAIN_TAB_ACTIVITY.equals(activity.getClass().getName())) {
            mainScreen = new WeakReference<>(activity);
        }
    }

    /** As Menu builds. With Debug logging on, reads the main screen's layout a moment later. Never throws. */
    static synchronized void menuBuilt() {
        try {
            if (!Utils.settingsReady() || !BaseSettings.DEBUG.get() || found || dumps >= DUMPS_MAX) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastDump < GAP_MS) return;
            dumps++;
            lastDump = now;
            Utils.runOnMainThreadDelayed(MenuLayoutDump::dumpMainScreen, DELAY_MS);
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Menu layout: couldn't plan a read: " + failure);
        }
    }

    private static void dumpMainScreen() {
        Activity activity = mainScreen.get();
        Window window = activity == null ? null : activity.getWindow();
        if (window == null) {
            Logger.printDebug(() -> "Menu layout: no main screen to read");
            return;
        }
        if (dump(window.getDecorView())) {
            synchronized (MenuLayoutDump.class) {
                found = true;
            }
        }
    }

    /** Writes what under [root] names Muse. True when anything did. */
    static boolean dump(View root) {
        int matched = 0;
        List<View> trees = new ArrayList<>();
        try {
            Class<?> mounting = Class.forName(MOUNTING_VIEW);
            Method describe = Class.forName(HELPER).getMethod("viewToString", mounting);
            collect(root, mounting, trees);
            for (int i = 0; i < trees.size(); i++) {
                Object text = describe.invoke(null, trees.get(i));
                List<String> lines = around(text == null ? "" : text.toString());
                if (lines.isEmpty()) continue;
                matched++;
                String where = "Menu layout, Litho tree " + (i + 1) + " of " + trees.size() + ":\n";
                String body = String.join("\n", lines);
                Logger.printDebug(() -> where + body);
            }
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Menu layout: Litho's trees couldn't be read: " + failure);
        }

        ArrayList<View> named = new ArrayList<>();
        root.findViewsWithText(named, WANTED[0], View.FIND_VIEWS_WITH_TEXT | View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View view : named) {
            String path = chain(view);
            Logger.printDebug(() -> "Menu layout, a view naming Muse: " + path);
        }
        if (matched == 0 && named.isEmpty()) {
            int read = trees.size();
            Logger.printDebug(() -> "Menu layout: nothing names Muse in " + read + " Litho trees");
        }
        return matched > 0 || !named.isEmpty();
    }

    private static void collect(View view, Class<?> mounting, List<View> out) {
        if (out.size() >= TREES_MAX) return;
        if (mounting.isInstance(view)) out.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), mounting, out);
    }

    /**
     * The lines of a Litho tree's description, which indents each component under its parent,
     * that name any of {@link #WANTED}, each after the lines of its parents, at most
     * {@link #LINES_MAX} and each cut to {@link #LINE_CHARS_MAX} characters.
     */
    static List<String> around(String description) {
        String[] lines = description.split("\n");
        // In the description's order, whichever match brought a parent in.
        Set<Integer> kept = new TreeSet<>();
        for (int i = 0; i < lines.length && kept.size() < LINES_MAX; i++) {
            if (!names(lines[i])) continue;
            List<Integer> parents = new ArrayList<>();
            int indent = indent(lines[i]);
            for (int j = i - 1; j >= 0 && indent > 0; j--) {
                int above = indent(lines[j]);
                if (above < indent) {
                    parents.add(0, j);
                    indent = above;
                }
            }
            kept.addAll(parents);
            kept.add(i);
        }
        List<String> out = new ArrayList<>();
        for (int index : kept) {
            if (out.size() >= LINES_MAX) break;
            String line = lines[index];
            out.add(line.length() > LINE_CHARS_MAX ? line.substring(0, LINE_CHARS_MAX) : line);
        }
        return out;
    }

    private static boolean names(String line) {
        for (String wanted : WANTED) {
            if (line.contains(wanted)) return true;
        }
        return false;
    }

    private static int indent(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
        return spaces;
    }

    /** [view]'s class and id, then each parent's, up to the screen. */
    static String chain(View view) {
        StringBuilder path = new StringBuilder();
        Object at = view;
        for (int depth = 0; at instanceof View && depth < 20; depth++) {
            View step = (View) at;
            if (path.length() > 0) path.append(" < ");
            path.append(step.getClass().getName());
            if (step.getId() != View.NO_ID) path.append('#').append(Integer.toHexString(step.getId()));
            at = step.getParent();
        }
        return path.toString();
    }

    /** Forgets this run's reads, as a new Facebook process would. For tests. */
    static synchronized void resetForTests() {
        dumps = 0;
        lastDump = -GAP_MS;
        found = false;
        mainScreen = new WeakReference<>(null);
    }
}

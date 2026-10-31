/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import java.lang.reflect.Method;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Search date range (#279): the Posted date filter opens Facebook's own from and to month picker
 * in place of the list of years.
 *
 * <p>Facebook's filter screen is built from the kind each filter declares, and it already has a
 * date range picker for that kind: two month wheels that send the range to the search as a start
 * and end month. The Posted date filter ({@value #FILTER}) declares a list of choices instead. The
 * patch hands this class the filter and its kind where a tap on a filter reads the kind. With the
 * switch on, that one filter answers the date range kind, and everything else keeps its own.
 * Facebook shows the newer filter screen as a bottom sheet behind a server flag, and that sheet
 * only knows lists and toggles, so the flag is answered false for the same tap and Facebook takes
 * the screen that has the picker.
 */
public final class SearchDateRange {
    /** The name Facebook gives the Posted date filter. */
    static final String FILTER = "rp_creation_time";

    /** The kind whose screen is the from and to picker. */
    static final String DATE_RANGE = "DATE_RANGE_PICKER";

    /** The member the report names once a filter's kind has been asked about. */
    static final String FILTER_KIND = "filter kind";

    /** The member the report names once the bottom sheet flag has been asked about. */
    static final String SHEET_FLAG = "bottom sheet flag";

    /** What the report counts each time the date filter opens as a range. */
    static final String OPENED = "Date filter opened as a range";

    private static final String FAMILY = FamilyNames.SEARCH_DATE_RANGE;

    /** True between {@link #kind} remapping a filter and {@link #bottomSheet} answering for the same tap. */
    private static boolean remapped;

    private SearchDateRange() {
    }

    /**
     * The hook where a tap on a filter reads its kind, handed the filter and the kind Facebook
     * read. The date range kind comes back for the Posted date filter while the switch is on;
     * otherwise, and for a throw, the kind Facebook read comes back.
     */
    public static Object kind(Object filter, Object facebookKind) {
        remapped = false;
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, FILTER_KIND);
            if (!(facebookKind instanceof Enum) || !Utils.settingsReady() || !Settings.SEARCH_DATE_RANGE.get()) {
                return facebookKind;
            }
            Enum<?> current = (Enum<?>) facebookKind;
            if (DATE_RANGE.equals(current.name()) || !FILTER.equals(filterName(filter))) return facebookKind;
            for (Object constant : current.getDeclaringClass().getEnumConstants()) {
                if (DATE_RANGE.equals(((Enum<?>) constant).name())) {
                    remapped = true;
                    HookStatus.counted(FAMILY, OPENED);
                    Logger.printDebug(() -> "Search date range: the date filter opens as a range (was " + current.name() + ")");
                    return constant;
                }
            }
            return facebookKind;
        } catch (Throwable failure) {
            remapped = false;
            HookStatus.threw(FAMILY, FILTER_KIND, failure);
            return facebookKind;
        }
    }

    /**
     * The hook on Facebook's bottom sheet flag, handed what Facebook read. False for the tap
     * {@link #kind} just remapped, so the range picker opens; otherwise what Facebook read.
     */
    public static boolean bottomSheet(boolean facebookSheet) {
        boolean mine = remapped;
        remapped = false;
        try {
            HookStatus.bound(FAMILY, SHEET_FLAG);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET_FLAG, failure);
        }
        return !mine && facebookSheet;
    }

    /** The filter's name, from the getName its interface keeps; null when it has none. */
    static String filterName(Object filter) {
        if (filter == null) return null;
        try {
            Method name = filter.getClass().getMethod("getName");
            Object value = name.invoke(filter);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException notAFilter) {
            return null;
        }
    }
}

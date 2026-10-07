/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.preference.ListPreference;

import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.shared.SettingsContextRule;

import java.util.IllegalFormatException;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * List rows show their summary as written. Android's ListPreference formats its summary with the
 * chosen entry, so the text size row's "130% of the size" summary threw as the row was drawn, and
 * picking any size but Facebook's own closed Facebook.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ListRowSummaryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.TEXT_SIZE.resetToDefault();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void androidsOwnListRowFormatsAPercentSignAndThrows() {
        ListPreference stock = new ListPreference(context());
        stock.setEntries(new CharSequence[] {"130%"});
        stock.setEntryValues(new CharSequence[] {"P130"});
        stock.setValue("P130");
        stock.setSummary("Facebook's text is 130% of the size your phone gives it.");
        assertThrows(IllegalFormatException.class, stock::getSummary);
    }

    @Test
    public void everyTextSizeChoiceShowsItsSummaryAsWritten() {
        for (TextSize.Scale scale : TextSize.Scale.values()) {
            Settings.TEXT_SIZE.save(scale);
            ValueRows.TextSizeRow row = HushfacebookPreferenceFragment.textSizeRow(context());
            assertEquals(scale.name(), HushfacebookPreferenceFragment.textSizeSummary(scale), String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void aListRowKeepsAPercentSignInItsSummary() {
        ValueRows.PlainSummaryList row = new ValueRows.PlainSummaryList(context()) { };
        row.setEntries(new CharSequence[] {"85%"});
        row.setEntryValues(new CharSequence[] {"P85"});
        row.setValue("P85");
        row.setSummary("85% of the size, 100%% sure");
        assertEquals("85% of the size, 100%% sure", String.valueOf(row.getSummary()));
    }
}

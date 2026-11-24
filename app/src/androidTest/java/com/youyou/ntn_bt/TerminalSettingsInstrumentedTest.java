package com.youyou.ntn_bt;

import android.widget.EditText;
import android.widget.TextView;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Uses a fake sender: exercises save/ack/read-back without changing MCU NV. */
@RunWith(AndroidJUnit4.class)
public class TerminalSettingsInstrumentedTest {
    private static final String OK = "\r\nOK\r\n";
    private static void fill(MainActivity activity, int id, String text) {
        ((EditText) activity.findViewById(id)).setText(text);
    }
    private static TerminalSettings start(MainActivity activity, List<String> sent) {
        TerminalSettings settings = new TerminalSettings(activity, command -> { sent.add(command); return sent.size(); });
        fill(activity, R.id.serverHostEdit, "Server.Example.com");
        fill(activity, R.id.serverRemotePortEdit, "8000"); fill(activity, R.id.serverLocalPortEdit, "0");
        activity.findViewById(R.id.terminalSettingsSave).performClick(); return settings;
    }
    private static String status(MainActivity activity) {
        return ((TextView) activity.findViewById(R.id.terminalSettingsStatus)).getText().toString();
    }
    private static void assertBaudLabel(MainActivity activity, String expected) {
        assertEquals(expected, ((TextView) activity.findViewById(R.id.terminalRs232Baud)).getText().toString());
        assertEquals(expected, ((TextView) activity.findViewById(R.id.settingsRs232Baud)).getText().toString());
    }
    private static void replies(TerminalSettings settings, String host) {
        settings.consume(1, OK); settings.consume(2, OK);
        settings.consume(3, "+MCUMODE:{\"effective\":0}"+OK);
        settings.consume(4, "+MCUHOST:{\"host\":\""+host+"\"}"+OK);
        settings.consume(5, "+MCUPORT:{\"remote\":8000,\"local\":0}"+OK);
        settings.consume(6, "+MCURS232:{\"effective_baud\":19200}"+OK);
    }
    @Test public void saveWaitsForEachAckAndVerifiesReadback() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                List<String> sent = new ArrayList<>(); TerminalSettings settings = start(activity, sent);
                assertEquals(1, sent.size()); assertEquals("AT+MCUHOST=Server.Example.com", sent.get(0));
                settings.consume(99, OK); assertEquals(1, sent.size());
                replies(settings, "Server.Example.com");
                assertEquals("AT+MCUPORT=8000,0", sent.get(1)); assertEquals(6, sent.size());
                assertEquals(activity.getString(R.string.settings_saved), status(activity));
            });
        }
    }
    @Test public void failedHostDoesNotSendPortsOrClaimSaved() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                List<String> sent = new ArrayList<>(); TerminalSettings settings = start(activity, sent);
                settings.consume(1, "\r\n+MCUERROR:3\r\nERROR\r\n");
                assertEquals(1, sent.size()); assertNotEquals(activity.getString(R.string.settings_saved), status(activity));
                assertTrue(activity.findViewById(R.id.terminalSettingsSave).isEnabled());
            });
        }
    }
    @Test public void wrongReadbackNeverClaimsSaved() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                List<String> sent = new ArrayList<>(); TerminalSettings settings = start(activity, sent);
                replies(settings, "other.example.com");
                assertEquals(activity.getString(R.string.settings_readback_failed), status(activity));
            });
        }
    }
    @Test public void manualBaudReplyUpdatesBothPagesWithoutAdvancingTheSettingsQueue() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                List<String> sent = new ArrayList<>();
                TerminalSettings settings = start(activity, sent);
                settings.consume(99, "\r\n+MCURS232:{\"saved_baud\":115200,\"effective_baud\":19200,\"format\":\"8N1\"}" + OK);
                assertBaudLabel(activity, activity.getString(R.string.rs232_current_baud, 19200));
                assertEquals(1, sent.size());
                assertTrue(settings.isBusy());

                settings.consume(100, "\r\n+MCUAPPLY:{\"state\":\"APPLIED\",\"baud\":38400}" + OK);
                settings.consume(101, "\r\n+MCUAPPLY:{\"state\":\"APPLIED\",\"mode\":1}" + OK);
                settings.consume(102, "\r\n+MCURS232:{\"saved_baud\":115200}" + OK);
                assertBaudLabel(activity, activity.getString(R.string.rs232_current_baud, 38400));

                String saveError = "\r\n+MCUERROR:3\r\nERROR\r\n";
                TextView writeStatus = activity.findViewById(R.id.rs232WriteStatus);
                settings.expectRs232Write(103);
                settings.consume(104, saveError);
                assertEquals(View.GONE, writeStatus.getVisibility());
                settings.consume(103, saveError);
                assertEquals(View.VISIBLE, writeStatus.getVisibility());
                assertEquals(activity.getString(R.string.rs232_save_failed), writeStatus.getText().toString());
                assertBaudLabel(activity, activity.getString(R.string.rs232_current_baud, 38400));

                settings.expectRs232Write(105);
                settings.consume(105, "+MCUAPPLY:{\"state\":\"APPLIED\",\"baud\":57600,\"persisted\":false}" + OK);
                assertEquals(activity.getString(R.string.settings_applied_volatile), writeStatus.getText().toString());
                assertBaudLabel(activity, activity.getString(R.string.rs232_current_baud, 57600));

                settings.disconnected();
                assertBaudLabel(activity, activity.getString(R.string.rs232_baud_unknown));
                assertEquals(View.GONE, writeStatus.getVisibility());
            });
        }
    }
    @Test public void temporaryModeIsVerifiedWithoutClaimingItWasSaved() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                List<String> sent = new ArrayList<>();
                TerminalSettings settings = new TerminalSettings(activity, command -> {
                    sent.add(command);
                    return sent.size();
                });
                activity.findViewById(R.id.mode1Button).performClick();
                assertEquals("AT+MCUMODE=1", sent.get(0));
                settings.consume(1, "+MCUAPPLY:{\"state\":\"APPLIED\",\"mode\":1,\"persisted\":false}" + OK);
                settings.consume(2, "+MCUMODE:{\"effective\":1,\"persisted\":false}" + OK);
                settings.consume(3, "+MCUHOST:{\"host\":\"\"}" + OK);
                settings.consume(4, "+MCUPORT:{\"remote\":0,\"local\":0}" + OK);
                settings.consume(5, "+MCURS232:{\"effective_baud\":19200}" + OK);
                assertEquals(activity.getString(R.string.settings_applied_volatile), status(activity));
                assertTrue(activity.findViewById(R.id.mode1Button).isSelected());
            });
        }
    }
}

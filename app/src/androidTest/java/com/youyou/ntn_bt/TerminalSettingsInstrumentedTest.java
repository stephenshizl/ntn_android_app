package com.youyou.ntn_bt;

import android.widget.EditText;
import android.widget.TextView;
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
}

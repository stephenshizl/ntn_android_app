package com.youyou.ntn_bt;

import android.os.SystemClock;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Spinner;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Sequential writes plus device read-back; no speculative saved state. */
final class TerminalSettings {
    interface Sender { int send(String command); }
    private final AppCompatActivity activity;
    private final Sender sender;
    private final EditText host, remote, local;
    private final TextView status, current;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private int pending, mode = -1, remotePort, localPort;
    private long started;
    private String command, actualHost = "", expectedHost;
    private int expectedMode = -1, expectedRemote, expectedLocal;
    private boolean receivedMode, receivedHost, receivedPorts, receivedBaud, saving, loaded;
    private static final String[] READ = {"AT+MCUMODE?", "AT+MCUHOST?", "AT+MCUPORT?", "AT+MCURS232?"};

    TerminalSettings(AppCompatActivity activity, Sender sender) {
        this.activity = activity; this.sender = sender;
        host = activity.findViewById(R.id.serverHostEdit);
        remote = activity.findViewById(R.id.serverRemotePortEdit);
        local = activity.findViewById(R.id.serverLocalPortEdit);
        status = activity.findViewById(R.id.terminalSettingsStatus);
        current = activity.findViewById(R.id.terminalSettingsCurrent);
        activity.findViewById(R.id.terminalSettingsQuery).setOnClickListener(v -> read());
        activity.findViewById(R.id.terminalSettingsSave).setOnClickListener(v -> save());
        activity.findViewById(R.id.mode0Button).setOnClickListener(v -> setMode(0));
        activity.findViewById(R.id.mode1Button).setOnClickListener(v -> setMode(1));
    }
    private void controls(boolean enabled) {
        for (int id : new int[]{R.id.terminalSettingsQuery, R.id.terminalSettingsSave,
                R.id.mode0Button, R.id.mode1Button}) activity.findViewById(id).setEnabled(enabled);
    }
    void disconnected() {
        pending = 0; queue.clear(); controls(true); loaded = false;
        mode = -1; actualHost = ""; expectedHost = null; expectedMode = -1;
        current.setText(R.string.settings_no_reading);
        status.setText(R.string.bt_name_disconnected);
    }
    boolean isBusy() { return pending != 0; }
    void ensureRead() { if (!loaded && pending == 0) read(); }
    void read() {
        if (pending != 0) return;
        saving = false; expectedHost = null; expectedMode = -1;
        begin();
    }
    private void setMode(int value) {
        if (pending != 0) return;
        saving = true; expectedMode = value; expectedHost = null;
        queue.add("AT+MCUMODE=" + value); begin();
    }
    private void save() {
        if (pending != 0) return;
        String value = host.getText().toString().trim();
        if (!EndpointConfig.validHost(value)) { host.setError(activity.getString(R.string.settings_invalid_host)); return; }
        int remoteValue, localValue;
        try { remoteValue = EndpointConfig.port(remote.getText().toString().trim(), false); }
        catch (IllegalArgumentException invalid) { remote.setError(activity.getString(R.string.settings_invalid_remote)); return; }
        try { localValue = EndpointConfig.port(local.getText().toString().trim(), true); }
        catch (IllegalArgumentException invalid) { local.setError(activity.getString(R.string.settings_invalid_local)); return; }
        saving = true; expectedHost = value; expectedRemote = remoteValue; expectedLocal = localValue; expectedMode = -1;
        queue.add("AT+MCUHOST=" + value);
        queue.add("AT+MCUPORT=" + remoteValue + "," + localValue);
        begin();
    }
    private void begin() {
        receivedMode = receivedHost = receivedPorts = receivedBaud = false;
        queue.addAll(Arrays.asList(READ)); controls(false);
        status.setText(saving ? R.string.settings_saving : R.string.gnss_querying);
        next();
    }
    private void next() {
        command = queue.poll();
        if (command == null) {
            boolean verified = receivedMode && receivedHost && receivedPorts && receivedBaud
                    && (expectedMode < 0 || expectedMode == mode)
                    && (expectedHost == null || (expectedHost.equals(actualHost)
                    && expectedRemote == remotePort && expectedLocal == localPort));
            loaded = verified;
            controls(true);
            status.setText(verified ? (saving ? R.string.settings_saved : R.string.settings_read)
                    : R.string.settings_readback_failed);
            return;
        }
        pending = sender.send(command); started = SystemClock.elapsedRealtime();
        if (pending == 0) fail(activity.getString(R.string.bt_not_connected));
    }
    void tick() {
        if (pending != 0 && SystemClock.elapsedRealtime() - started >= 7000L)
            fail(activity.getString(R.string.gnss_query_failed));
    }
    private void fail(String detail) {
        String failed = command;
        pending = 0; queue.clear(); controls(true); loaded = false;
        status.setText(activity.getString(R.string.settings_failed, failed, detail));
    }
    void consume(int request, String text) {
        if (pending == 0 || request != pending) return;
        pending = 0;
        if (!text.contains("\r\nOK\r\n")) { fail(text.trim()); return; }
        try {
            if (command.equals(READ[0])) {
                mode = json(text, "+MCUMODE:").getInt("effective"); receivedMode = true;
            } else if (command.equals(READ[1])) {
                actualHost = json(text, "+MCUHOST:").getString("host"); receivedHost = true;
            } else if (command.equals(READ[2])) {
                JSONObject ports = json(text, "+MCUPORT:");
                remotePort = ports.getInt("remote"); localPort = ports.getInt("local"); receivedPorts = true;
                host.setText(actualHost); remote.setText(remotePort == 0 ? "" : String.valueOf(remotePort)); local.setText(String.valueOf(localPort));
                current.setText(activity.getString(R.string.settings_current,
                        activity.getString(mode == 0 ? R.string.mode0_name : R.string.mode1_name),
                        actualHost.isEmpty() ? "—" : actualHost, remotePort, localPort));
                if (actualHost.isEmpty() || remotePort == 0)
                    current.setText(activity.getString(R.string.settings_unconfigured,
                            activity.getString(mode == 0 ? R.string.mode0_name : R.string.mode1_name)));
            } else if (command.equals(READ[3])) {
                String baud = String.valueOf(json(text, "+MCURS232:").getInt("effective_baud"));
                Spinner rates = activity.findViewById(R.id.baudSpinner);
                for (int i = 0; i < rates.getCount(); i++)
                    if (baud.equals(String.valueOf(rates.getItemAtPosition(i)))) rates.setSelection(i);
                receivedBaud = true;
            }
        } catch (Exception invalid) { fail(activity.getString(R.string.settings_readback_failed)); return; }
        next();
    }
    private static JSONObject json(String text, String prefix) throws Exception {
        int start = text.indexOf(prefix);
        if (start < 0) throw new IllegalArgumentException("Missing response");
        String line = text.substring(start + prefix.length()).split("[\\r\\n]", 2)[0];
        return new JSONObject(line);
    }
}

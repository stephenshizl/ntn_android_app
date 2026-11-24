package com.youyou.ntn_bt;

import android.os.SystemClock;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONException;
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
    private final TextView rs232WriteStatus;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private int pending, mode = -1, remotePort, localPort;
    private int rs232WriteRequest;
    private long started;
    private String command, actualHost = "", expectedHost;
    private int expectedMode = -1, expectedRemote, expectedLocal;
    private boolean receivedMode, receivedHost, receivedPorts, receivedBaud, saving, loaded;
    private boolean volatileSettings;
    private static final String[] READ = {"AT+MCUMODE?", "AT+MCUHOST?", "AT+MCUPORT?", "AT+MCURS232?"};

    TerminalSettings(AppCompatActivity activity, Sender sender) {
        this.activity = activity; this.sender = sender;
        host = activity.findViewById(R.id.serverHostEdit);
        remote = activity.findViewById(R.id.serverRemotePortEdit);
        local = activity.findViewById(R.id.serverLocalPortEdit);
        status = activity.findViewById(R.id.terminalSettingsStatus);
        current = activity.findViewById(R.id.terminalSettingsCurrent);
        rs232WriteStatus = activity.findViewById(R.id.rs232WriteStatus);
        activity.findViewById(R.id.terminalSettingsQuery).setOnClickListener(v -> read());
        activity.findViewById(R.id.terminalSettingsSave).setOnClickListener(v -> save());
        activity.findViewById(R.id.mode0Button).setOnClickListener(v -> setMode(0));
        activity.findViewById(R.id.mode1Button).setOnClickListener(v -> setMode(1));
        renderModeButtons();
    }
    private CharSequence highlightedMode(String text, String label, int value) {
        SpannableString result = new SpannableString(text);
        int labelStart = text.indexOf(label);
        int numberOffset = label.lastIndexOf(String.valueOf(value));
        if (labelStart >= 0 && numberOffset >= 0) {
            int start = labelStart + numberOffset;
            result.setSpan(new ForegroundColorSpan(activity.getColor(R.color.settings_mode_selected)),
                    start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(new StyleSpan(Typeface.BOLD), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }
    private void renderModeButtons() {
        for (int value = 0; value <= 1; value++) {
            TextView button = activity.findViewById(value == 0 ? R.id.mode0Button : R.id.mode1Button);
            String label = activity.getString(value == 0 ? R.string.mode0 : R.string.mode1);
            boolean selected = mode == value;
            button.setSelected(selected);
            button.setText(selected ? highlightedMode(label, label, value) : label);
        }
    }
    private void controls(boolean enabled) {
        for (int id : new int[]{R.id.terminalSettingsQuery, R.id.terminalSettingsSave,
                R.id.mode0Button, R.id.mode1Button}) activity.findViewById(id).setEnabled(enabled);
    }
    void disconnected() {
        pending = 0; queue.clear(); controls(true); loaded = false;
        mode = -1; actualHost = ""; expectedHost = null; expectedMode = -1;
        renderModeButtons();
        renderRs232Baud(0);
        expectRs232Write(0);
        current.setText(R.string.settings_no_reading);
        status.setText(R.string.bt_name_disconnected);
    }
    void expectRs232Write(int request) {
        rs232WriteRequest = request;
        rs232WriteStatus.setVisibility(TextView.GONE);
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
        volatileSettings = false;
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
            status.setText(verified ? (saving
                    ? (volatileSettings ? R.string.settings_applied_volatile : R.string.settings_saved)
                    : R.string.settings_read)
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
        boolean ok = text.contains("\r\nOK\r\n");
        int readBaud = ok ? consumeRs232Response(text) : 0;
        if (rs232WriteRequest != 0 && rs232WriteRequest == request) {
            rs232WriteRequest = 0;
            if (!ok && text.startsWith("\r\n+MCUERROR:3\r\n")) {
                showRs232WriteStatus(R.string.rs232_save_failed);
            }
        }
        if (pending == 0 || request != pending) return;
        pending = 0;
        if (!ok) { fail(text.trim()); return; }
        try {
            if (command.equals(READ[0])) {
                JSONObject modeResponse = json(text, "+MCUMODE:");
                mode = modeResponse.getInt("effective"); receivedMode = true;
                volatileSettings = !modeResponse.optBoolean("persisted", !volatileSettings);
                renderModeButtons();
            } else if (command.equals(READ[1])) {
                actualHost = json(text, "+MCUHOST:").getString("host"); receivedHost = true;
            } else if (command.equals(READ[2])) {
                JSONObject ports = json(text, "+MCUPORT:");
                remotePort = ports.getInt("remote"); localPort = ports.getInt("local"); receivedPorts = true;
                host.setText(actualHost); remote.setText(remotePort == 0 ? "" : String.valueOf(remotePort)); local.setText(String.valueOf(localPort));
                String modeName = activity.getString(mode == 0 ? R.string.mode0_name : R.string.mode1_name);
                String summary = actualHost.isEmpty() || remotePort == 0
                        ? activity.getString(R.string.settings_unconfigured, modeName)
                        : activity.getString(R.string.settings_current, modeName, actualHost, remotePort, localPort);
                current.setText(highlightedMode(summary, modeName, mode));
            } else if (command.equals(READ[3])) {
                if (readBaud == 0) {
                    fail(activity.getString(R.string.settings_readback_failed));
                    return;
                }
                Spinner rates = activity.findViewById(R.id.baudSpinner);
                for (int i = 0; i < rates.getCount(); i++)
                    if (String.valueOf(readBaud).equals(rates.getItemAtPosition(i))) rates.setSelection(i);
                receivedBaud = true;
            } else if (text.trim().startsWith("+MCUAPPLY:")) {
                volatileSettings = !json(text, "+MCUAPPLY:").optBoolean("persisted", !volatileSettings);
            }
        } catch (Exception invalid) { fail(activity.getString(R.string.settings_readback_failed)); return; }
        next();
    }
    private int consumeRs232Response(String text) {
        String response = text.trim();
        try {
            if (response.startsWith("+MCURS232:")) {
                int baud = json(response, "+MCURS232:").optInt("effective_baud");
                if (baud > 0) {
                    renderRs232Baud(baud);
                    return baud;
                }
            } else if (response.startsWith("+MCUAPPLY:")) {
                JSONObject applied = json(response, "+MCUAPPLY:");
                int baud = applied.optInt("baud");
                if (baud > 0 && "APPLIED".equals(applied.optString("state"))) {
                    renderRs232Baud(baud);
                    if (!applied.optBoolean("persisted", true)) {
                        showRs232WriteStatus(R.string.settings_applied_volatile);
                    }
                }
            }
        } catch (JSONException invalid) {
            return 0;
        }
        return 0;
    }

    private void showRs232WriteStatus(int message) {
        rs232WriteStatus.setText(message);
        rs232WriteStatus.setVisibility(TextView.VISIBLE);
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private void renderRs232Baud(int baud) {
        String label = baud > 0 ? activity.getString(R.string.rs232_current_baud, baud)
                : activity.getString(R.string.rs232_baud_unknown);
        ((TextView) activity.findViewById(R.id.terminalRs232Baud)).setText(label);
        ((TextView) activity.findViewById(R.id.settingsRs232Baud)).setText(label);
    }

    private static JSONObject json(String text, String prefix) throws JSONException {
        int start = text.indexOf(prefix);
        if (start < 0) throw new IllegalArgumentException("Missing response");
        String line = text.substring(start + prefix.length()).split("[\\r\\n]", 2)[0];
        return new JSONObject(line);
    }
}

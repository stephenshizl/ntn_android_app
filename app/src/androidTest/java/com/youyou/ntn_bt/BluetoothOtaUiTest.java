package com.youyou.ntn_bt;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** UI checks only; no scan, firmware selection or device writing occurs. */
@RunWith(AndroidJUnit4.class)
public class BluetoothOtaUiTest {
    private TextView find(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return (TextView)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) { TextView result=find(group.getChildAt(i),text); if(result!=null)return result; }
        }
        return null;
    }
    @Test public void bluetoothTargetIsExplicitAndWritingStartsDisabled() {
        try (ActivityScenario<BluetoothOtaActivity> scenario=ActivityScenario.launch(BluetoothOtaActivity.class)) {
            scenario.onActivity(activity -> {
                View root=activity.getWindow().getDecorView();
                assertNotNull(find(root,activity.getString(R.string.ota_title)));
                TextView notice=find(root,activity.getString(R.string.ota_module_notice));assertNotNull(notice);
                assertTrue(notice.getText().toString().contains("MCU")&&notice.getText().toString().contains("NTN"));
                TextView start=find(root,activity.getString(R.string.ota_start));assertTrue(start instanceof Button);assertFalse(start.isEnabled());
            });
        }
    }
    @Test public void firmwarePickerAndSingleBankRecoveryNoticeArePresent() {
        try (ActivityScenario<BluetoothOtaActivity> scenario=ActivityScenario.launch(BluetoothOtaActivity.class)) {
            scenario.onActivity(activity -> {
                View root=activity.getWindow().getDecorView();
                assertTrue(find(root,activity.getString(R.string.ota_choose_file)) instanceof Button);
                assertNotNull(find(root,activity.getString(R.string.ota_single_bank_notice)));
                assertNotNull(find(root,activity.getString(R.string.ota_no_file)));
            });
        }
    }
}

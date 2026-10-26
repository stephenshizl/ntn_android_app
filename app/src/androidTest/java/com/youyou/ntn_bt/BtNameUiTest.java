package com.youyou.ntn_bt;

import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Synthetic replies exercise rendering; they do not prove a radio rename. */
@RunWith(AndroidJUnit4.class)
public class BtNameUiTest {
    private void pending(MainActivity a,int id) throws Exception {
        Field field=MainActivity.class.getDeclaredField("pendingName");
        field.setAccessible(true);field.setInt(a,id);
    }
    private void reply(MainActivity a,int id,String text) throws Exception {
        Method method=MainActivity.class.getDeclaredMethod("consumeName",int.class,String.class);
        method.setAccessible(true);method.invoke(a,id,text);
    }
    private String status(MainActivity a) {return ((TextView)a.findViewById(R.id.btNameStatus)).getText().toString();}
    @Test public void deferredSeparatesDesiredFromActual() {
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                try {
                    pending(a,41);
                    reply(a,41,"\r\n+MCUBTNAME:{\"mode\":\"MANUAL\",\"source\":\"MANUAL\",\"state\":\"DEFERRED\",\"desired\":\"New_Name\",\"applied\":\"Old_Name\",\"mac\":\"98:D9:72:FD:D7:C0\",\"last_error\":0}\r\nOK\r\n");
                    assertTrue(status(a).contains("DEFERRED"));
                    String value=((TextView)a.findViewById(R.id.btNameValue)).getText().toString();
                    assertTrue(value.contains("New_Name")&&value.contains("Old_Name")&&value.contains("98:D9:72:FD:D7:C0"));
                } catch(Exception e) {throw new AssertionError(e);}
            });
        }
    }
    @Test public void wrongIdIgnoredAndErrorNeverShowsSuccess() {
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                try {
                    pending(a,42);String before=status(a);
                    reply(a,41,"\r\n+MCUERROR:3\r\nERROR\r\n");assertEquals(before,status(a));
                    reply(a,42,"\r\n+MCUERROR:3\r\nERROR\r\n");
                    assertEquals(a.getString(R.string.bt_name_failed),status(a));
                } catch(Exception e) {throw new AssertionError(e);}
            });
        }
    }
    @Test public void invalidNamesAreRejectedBeforeSending() {
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                EditText input=a.findViewById(R.id.btNameEdit);
                for(String value:new String[]{"", "12345678901234567890123456789012", "a,b", "中文"}) {
                    input.setError(null);input.setText(value);a.findViewById(R.id.btNameSetButton).performClick();
                    assertNotNull(input.getError());
                }
                input.setError(null);input.setText("AUTO");a.findViewById(R.id.btNameSetButton).performClick();
                assertNull(input.getError());
            });
        }
    }
}

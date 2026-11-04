package com.youyou.ntn_bt;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModemStatusTest {
    @Test public void refreshRequiresFiveSecondsAndAnIdleVisibleConnectedPage() {
        assertFalse(ModemStatus.shouldRefresh(true,true,true,true,true,4999L,0));
        assertTrue(ModemStatus.shouldRefresh(true,true,true,true,true,5000L,0));
        assertFalse(ModemStatus.shouldRefresh(false,true,true,true,true,5000L,0));
        assertFalse(ModemStatus.shouldRefresh(true,false,true,true,true,5000L,0));
        assertFalse(ModemStatus.shouldRefresh(true,true,false,true,true,5000L,0));
        assertFalse(ModemStatus.shouldRefresh(true,true,true,false,true,5000L,0));
        assertFalse(ModemStatus.shouldRefresh(true,true,true,true,false,5000L,0));
    }

    @Test public void readsRegistrationStatRatherThanUrcMode() {
        for (int stat = 0; stat <= 5; stat++)
            assertEquals(stat, ModemStatus.registration("AT+CEREG?\r\n+CEREG: 1," + stat + "\r\nOK\r\n"));
        assertEquals(5, ModemStatus.registration("+CEREG:2,5,\"1234\",\"12345678\",14\r\n"));
        assertEquals(2, ModemStatus.registration("+CEREG:2\r\n"));
    }

    @Test public void malformedOrErrorResponseNeverMeansRegistered() {
        for (String response : new String[]{"", "ERROR", "+CEREG:bad", "+CEREG:0,invalid"})
            assertEquals(-1, ModemStatus.registration(response));
        assertEquals(R.string.net_unknown, ModemStatus.registrationLabel(-1));
        assertEquals(R.string.net_unknown, ModemStatus.registrationLabel(4));
        assertEquals(R.string.net_home, ModemStatus.registrationLabel(1));
        assertEquals(R.string.net_roaming, ModemStatus.registrationLabel(5));
    }

    @Test public void simReadyAndLockedStatesAreDistinct() {
        assertEquals("READY", ModemStatus.sim("at+cpin?\r+CPIN:READY\r\n\r\nOK\r\n"));
        assertEquals(R.string.sim_ready, ModemStatus.simLabel("READY"));
        assertEquals(R.string.sim_pin, ModemStatus.simLabel("SIM PIN"));
        assertEquals(R.string.sim_puk, ModemStatus.simLabel("SIM PUK"));
        assertEquals(R.string.sim_not_ready, ModemStatus.simLabel("NOT READY"));
        assertEquals("UNKNOWN", ModemStatus.sim("+CME ERROR:13\r\n"));
        assertEquals(R.string.sim_unknown, ModemStatus.simLabel("UNKNOWN"));
    }
}

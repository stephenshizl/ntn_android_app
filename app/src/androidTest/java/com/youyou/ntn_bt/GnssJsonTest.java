package com.youyou.ntn_bt;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GnssJsonTest {
    @Test public void parsesActualNoAntennaResponse() throws Exception {
        GnssReading r=GnssReading.fromJson("{\"configured\":true,\"valid\":false,\"age_ms\":4294967295,\"lat_e7\":0,\"lon_e7\":0,\"sats\":0,\"fix_quality\":0,\"alt_valid\":false}",100);
        assertFalse(r.isFresh(100)); assertEquals(0,r.satellites);
        assertEquals(GnssReading.UNKNOWN_AGE,r.ageMs);
    }
    @Test public void validFixturePreservesSignsAndUnits() throws Exception {
        GnssReading r=GnssReading.fromJson("{\"configured\":true,\"valid\":true,\"age_ms\":10,\"lat_e7\":-312304000,\"lon_e7\":1214737000,\"alt_mm\":12345,\"alt_valid\":true,\"speed_cm_s\":250,\"sats\":9,\"fix_quality\":1}",100);
        assertTrue(r.isFresh(100)); assertEquals("-31.2304000",r.latitude());
        assertEquals("12.345",r.altitude()); assertEquals("2.50",r.speed());
    }
    @Test(expected=org.json.JSONException.class) public void missingValidIsNotAssumedTrue() throws Exception {
        GnssReading.fromJson("{\"age_ms\":0}",0);
    }
}

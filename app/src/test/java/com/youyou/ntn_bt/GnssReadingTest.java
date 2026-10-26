package com.youyou.ntn_bt;

import org.junit.Test;
import static org.junit.Assert.*;

public class GnssReadingTest {
    private GnssReading sample(boolean valid, long lat, long lon, long age) {
        return new GnssReading(true, valid, lat, lon, -1234L, true, 123L,
                8, 1, "GPS+BDS", age, 1000);
    }
    @Test public void noFixZerosAreNeverUsable() {
        assertFalse(sample(false, 0, 0, 0).isFresh(1000));
    }
    @Test public void actualValidZeroCoordinatesRemainAllowed() {
        assertTrue(sample(true, 0, 0, 0).isFresh(1000));
    }
    @Test public void signedCoordinatesHaveSevenDecimalPlaces() {
        GnssReading r = sample(true, 312304000, -1214737000, 10);
        assertEquals("31.2304000", r.latitude()); assertEquals("-121.4737000", r.longitude());
    }
    @Test public void invalidCoordinatesAndMissingCoordinatesAreRejected() {
        assertFalse(sample(true, 900000001, 0, 0).isFresh(1000));
        assertFalse(sample(true, 0, -1800000001, 0).isFresh(1000));
        assertFalse(new GnssReading(true,true,null,null,null,false,null,-1,-1,"—",0,0).isFresh(0));
    }
    @Test public void ageIncludesElapsedTimeAndExpires() {
        GnssReading r=sample(true,1,1,100);
        assertTrue(r.isFresh(5900)); assertFalse(r.isFresh(5901));
    }
    @Test public void unknownAgeDoesNotBecomeFresh() {
        GnssReading r=sample(true,1,1,GnssReading.UNKNOWN_AGE);
        assertFalse(r.isFresh(1000)); assertEquals(GnssReading.UNKNOWN_AGE,r.currentAge(90000));
    }
    @Test public void altitudeAndSpeedUseCorrectUnits() {
        GnssReading r=sample(true,1,1,0);
        assertEquals("-1.234",r.altitude()); assertEquals("1.23",r.speed());
    }
    @Test public void invalidTimeDoesNotExposePlaceholder() {
        GnssReading.TimeReading t=GnssReading.TimeReading.parse("0,,GNSS,4294967295",0);
        assertFalse(t.isFresh(0)); assertEquals("",t.utc);
    }
    @Test public void utcIsIndependentOfPositionAndExpires() {
        GnssReading.TimeReading t=GnssReading.TimeReading.parse("1,2026-10-25T00:00:00Z,GNSS,0",100);
        assertTrue(t.isFresh(100)); assertFalse(t.isFresh(5101));
        assertEquals("GNSS",t.source);
    }
    @Test(expected=IllegalArgumentException.class) public void malformedUtcIsRejected() {
        GnssReading.TimeReading.parse("1,broken,GNSS,0",0);
    }
    @Test(expected=IllegalArgumentException.class) public void negativeAgeIsRejected() {
        sample(true,1,1,-1);
    }
}

package com.youyou.ntn_bt;
import org.junit.Test;
import static org.junit.Assert.*;
public class AtCommandTest {
    @Test public void lowercaseNtnAndMcu() {
        assertEquals("ATI", AtCommand.normalize(" ati "));
        assertEquals("AT+MCUVER?", AtCommand.normalize("at+mcuver?"));
    }
    @Test public void argumentCasePreserved() {
        assertEquals("AT+CGDCONT=1,\"IP\",\"MyApn\"", AtCommand.normalize("at+cgdcont=1,\"IP\",\"MyApn\""));
        assertEquals("AT+MCUHOST=Server.Example.com", AtCommand.normalize("at+mcuhost=Server.Example.com"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectMultipleLines() { AtCommand.normalize("AT\r\nATI"); }
    @Test(expected=IllegalArgumentException.class) public void rejectUnicode() { AtCommand.normalize("AT+X=中文"); }
    @Test(expected=IllegalArgumentException.class) public void rejectTooLong() { AtCommand.normalize("AT" + "X".repeat(2045)); }
    @Test(expected=IllegalArgumentException.class) public void rejectEmpty() { AtCommand.normalize(""); }
    @Test(expected=IllegalArgumentException.class) public void rejectNonAt() { AtCommand.normalize("hello"); }
}

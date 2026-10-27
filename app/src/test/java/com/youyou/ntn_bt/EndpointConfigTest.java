package com.youyou.ntn_bt;
import org.junit.Test;
import static org.junit.Assert.*;
public class EndpointConfigTest {
    @Test public void ipv4AndDomain() {
        assertTrue(EndpointConfig.validHost("192.168.1.1"));
        assertTrue(EndpointConfig.validHost("ntn-server.example.com"));
        assertTrue(EndpointConfig.validHost("Server.Example.com"));
    }
    @Test public void rejectInvalidAddress() {
        for (String host : new String[]{"", "256.1.1.1", "1.2.3", "a..b", "-a.com", "a-.com", "a.com.", "https://a.com", "a.com:123", "a\r\nAT", "服务器.cn"})
            assertFalse(host, EndpointConfig.validHost(host));
    }
    @Test public void hostLengths() {
        assertFalse(EndpointConfig.validHost("a".repeat(64)+".com"));
        assertTrue(EndpointConfig.validHost("a".repeat(63)+".com"));
    }
    @Test public void portsAtBoundaries() {
        assertEquals(0, EndpointConfig.port("0", true));
        assertEquals(1, EndpointConfig.port("1", false));
        assertEquals(65535, EndpointConfig.port("65535", false));
    }
    @Test(expected=IllegalArgumentException.class) public void remoteZero() { EndpointConfig.port("0", false); }
    @Test(expected=IllegalArgumentException.class) public void outOfRange() { EndpointConfig.port("65536", true); }
    @Test(expected=IllegalArgumentException.class) public void portInjection() { EndpointConfig.port("1,2", true); }
}

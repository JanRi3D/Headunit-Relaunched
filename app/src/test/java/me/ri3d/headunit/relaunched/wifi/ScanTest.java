package me.ri3d.headunit.relaunched.wifi;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The two bits of the network sweep that are pure arithmetic and easy to get
 * backwards. Probing itself needs a network; this does not.
 *
 * Run with: gradlew :app:testDebugUnitTest
 */
public class ScanTest {

    @Test
    public void addressMathIsLittleEndian() {
        // WifiManager hands 192.168.1.42 over as 0x2A01A8C0.
        assertEquals("192.168.1.42", ServerScan.ipv4(0x2A01A8C0));
        // A last octet of 255 must not sign-extend out of the shift.
        assertEquals("10.0.0.255", ServerScan.ipv4(0xFF00000A));

        assertEquals("192.168.1.", ServerScan.subnetOf("192.168.1.42"));
        assertNull(ServerScan.subnetOf(null));
        assertNull(ServerScan.subnetOf("no dots here"));
    }
}

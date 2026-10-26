package ppa.cms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import ppa.cms.support.Addresses;

class AddressRulesTest {

    @Test
    void buildsReachabilityUrlsAndHidesPrivateHosts() {
        assertEquals(List.of("https://ppa.example:8443/app", "http://ppa.example:8443/app"), Addresses.statusUrls("ppa.example:8443/app?token=secret"));
        assertEquals(List.of("https://10.0.0.8/status"), Addresses.statusUrls("https://10.0.0.8/status"));
        assertTrue(Addresses.nonPublicIp("10.1.2.3"));
        assertTrue(Addresses.nonPublicIp("192.168.1.9"));
        assertFalse(Addresses.nonPublicIp("ppa.example"));
        assertEquals("10.1.2.3", Addresses.hostOnly("http://10.1.2.3:3306/db"));
        assertNull(Addresses.parse(""));
    }
}

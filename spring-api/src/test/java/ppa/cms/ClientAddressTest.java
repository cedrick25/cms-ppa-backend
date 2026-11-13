package ppa.cms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import ppa.cms.support.ClientAddress;

class ClientAddressTest {

    @Test
    void directConnectionKeepsTheSocketAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.8");
        request.addHeader("X-Real-IP", "198.51.100.4");
        assertEquals("203.0.113.8", ClientAddress.from(request));
    }

    @Test
    void proxyConnectionUsesTheClientAddressSeenByNginx() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.3");
        request.addHeader("X-Real-IP", "10.8.0.24");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.8.0.24");
        assertEquals("10.8.0.24", ClientAddress.from(request));
    }

    @Test
    void proxyConnectionFallsBackToTheNearestForwardedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.8.0.24");
        assertEquals("10.8.0.24", ClientAddress.from(request));
    }

    @Test
    void blankProxyHeadersKeepTheProxyAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::ffff:127.0.0.1");
        assertEquals("127.0.0.1", ClientAddress.from(request));
    }

    @Test
    void missingRequestHasNoAddress() {
        assertNull(ClientAddress.from(null));
    }
}

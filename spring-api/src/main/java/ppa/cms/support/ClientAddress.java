package ppa.cms.support;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ClientAddress {

    private static final Pattern IPV4 = Pattern.compile("^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    private ClientAddress() {
    }

    public static String from(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String peer = normalize(request.getRemoteAddr());
        if (peer != null && trustedProxy(peer)) {
            String real = normalize(request.getHeader("X-Real-IP"));
            if (real != null) {
                return real;
            }
            String forwarded = nearestForwarded(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                return forwarded;
            }
        }
        return peer;
    }

    private static String nearestForwarded(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String[] parts = header.split(",");
        for (int index = parts.length - 1; index >= 0; index--) {
            String address = normalize(parts[index]);
            if (address != null) {
                return address;
            }
        }
        return null;
    }

    static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String value = address.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        if (zone > 0) {
            value = value.substring(0, zone);
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("::ffff:") && value.indexOf('.', 7) > 0) {
            value = value.substring(7);
        }
        if (!ip(value)) {
            return null;
        }
        return value.length() > 45 ? value.substring(0, 45) : value;
    }

    private static boolean ip(String value) {
        if (IPV4.matcher(value).matches()) {
            return true;
        }
        if (value.indexOf(':') < 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')
                    || character == ':';
            if (!hex) {
                return false;
            }
        }
        return value.length() >= 2;
    }

    private static boolean trustedProxy(String address) {
        try {
            InetAddress ip = InetAddress.getByName(address);
            if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress()) {
                return true;
            }
            byte[] bytes = ip.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        } catch (Exception exception) {
            return false;
        }
    }
}

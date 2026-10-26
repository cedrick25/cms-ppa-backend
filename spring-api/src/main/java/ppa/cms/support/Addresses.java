package ppa.cms.support;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Addresses {

    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://.*", Pattern.CASE_INSENSITIVE);

    private Addresses() {
    }

    public record Parts(String scheme, String host, Integer port, String path) {
    }

    public static Parts parse(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean hasScheme = SCHEME.matcher(trimmed).matches();
        URI uri;
        try {
            uri = URI.create(hasScheme ? trimmed : "http://" + trimmed);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || !validHost(host)) {
            return null;
        }
        String scheme = hasScheme && uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
        String path = uri.getPath() == null ? "" : uri.getPath();
        Integer port = uri.getPort() < 0 ? null : uri.getPort();
        return new Parts(scheme, host.toLowerCase(Locale.ROOT), port, path);
    }

    public static String hostPort(String value) {
        Parts parts = parse(value);
        if (parts == null) {
            return null;
        }
        return parts.port() == null ? parts.host() : parts.host() + ":" + parts.port();
    }

    public static String hostOnly(String value) {
        Parts parts = parse(value);
        return parts == null ? null : parts.host();
    }

    public static List<String> statusUrls(String domain) {
        Parts parts = parse(domain);
        if (parts == null) {
            return List.of();
        }
        List<String> schemes = parts.scheme() == null ? List.of("https", "http") : List.of(parts.scheme());
        if (schemes.stream().anyMatch(scheme -> !scheme.equals("http") && !scheme.equals("https"))) {
            return List.of();
        }
        String authority = parts.port() == null ? parts.host() : parts.host() + ":" + parts.port();
        List<String> urls = new ArrayList<>();
        for (String scheme : schemes) {
            urls.add(scheme + "://" + authority + parts.path());
        }
        return urls;
    }

    public static boolean nonPublicIp(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.equals("::1") || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80");
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            if (!parts[index].matches("\\d{1,3}")) {
                return false;
            }
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) {
                return false;
            }
        }
        int first = octets[0];
        int second = octets[1];
        if (first == 10 || first == 127 || first == 0 || first >= 224) {
            return true;
        }
        if (first == 169 && second == 254) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 192 && second == 0 && (octets[2] == 0 || octets[2] == 2)) {
            return true;
        }
        return first == 198 && second == 51 && octets[2] == 100 || first == 203 && second == 0 && octets[2] == 113;
    }

    private static boolean validHost(String host) {
        if (host.indexOf(':') >= 0 || host.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
            return host.indexOf('.') > 0 || host.indexOf(':') >= 0;
        }
        return host.matches("(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
    }
}

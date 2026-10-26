package ppa.cms.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ppa.cms.web.ApiException;

public final class Texts {

    public static final String USERNAME_TAKEN = "This username is already in use. Choose a different username.";
    public static final String EMAIL_TAKEN = "This email address is already registered to another account.";
    public static final String NAME_TAKEN = "Another registered system already uses this name. Choose a different name.";

    private Texts() {
    }

    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static String namePart(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\s+", " ");
    }

    public static String composeName(String first, String middle, String last, String suffix) {
        List<String> parts = new ArrayList<>();
        if (first != null) {
            parts.add(first);
        }
        if (middle != null) {
            parts.add(middle);
        }
        if (last != null) {
            parts.add(last);
        }
        if (suffix != null) {
            parts.add(suffix);
        }
        return String.join(" ", parts);
    }

    public static String likeContains(String search) {
        return "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static Map<String, List<String>> errors() {
        return new LinkedHashMap<>();
    }

    public static void add(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, key -> new ArrayList<>()).add(message);
    }

    public static void finish(Map<String, List<String>> errors) {
        if (!errors.isEmpty()) {
            throw ApiException.errors(errors);
        }
    }
}

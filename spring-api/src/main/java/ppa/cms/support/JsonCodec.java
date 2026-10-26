package ppa.cms.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader and writer for the payloads this API stores.
 * Strings, numbers, booleans, null, objects, and arrays are enough.
 */
public final class JsonCodec {

    private JsonCodec() {
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(value, builder);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String json) {
        Object value = new Parser(json).parse();
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException("JSON object expected.");
    }

    private static void write(Object value, StringBuilder builder) {
        switch (value) {
            case null -> builder.append("null");
            case String text -> builder.append('"').append(escape(text)).append('"');
            case Boolean bool -> builder.append(bool ? "true" : "false");
            case Number number -> builder.append(number);
            case Map<?, ?> map -> {
                builder.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                    write(entry.getValue(), builder);
                }
                builder.append('}');
            }
            case List<?> list -> {
                builder.append('[');
                for (int index = 0; index < list.size(); index++) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    write(list.get(index), builder);
                }
                builder.append(']');
            }
            default -> builder.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = json;
        }

        private Object parse() {
            Object value = readValue();
            skip();
            if (index != json.length()) {
                throw new IllegalArgumentException("Unexpected characters after JSON.");
            }
            return value;
        }

        private Object readValue() {
            skip();
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON.");
            }
            char character = json.charAt(index);
            if (character == '{') {
                return readObject();
            }
            if (character == '[') {
                return readArray();
            }
            if (character == '"') {
                return readString();
            }
            if (character == 't' || character == 'f') {
                return readLiteral();
            }
            if (character == 'n') {
                expect("null");
                return null;
            }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return map;
            }
            while (index < json.length()) {
                skip();
                String key = readString();
                skip();
                if (index >= json.length() || json.charAt(index) != ':') {
                    throw new IllegalArgumentException("Expected ':' in JSON object.");
                }
                index++;
                map.put(key, readValue());
                skip();
                if (peek('}')) {
                    index++;
                    return map;
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("Expected ',' in JSON object.");
                }
                index++;
            }
            throw new IllegalArgumentException("Unclosed JSON object.");
        }

        private List<Object> readArray() {
            index++;
            List<Object> list = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            while (index < json.length()) {
                list.add(readValue());
                skip();
                if (peek(']')) {
                    index++;
                    return list;
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("Expected ',' in JSON array.");
                }
                index++;
            }
            throw new IllegalArgumentException("Unclosed JSON array.");
        }

        private String readString() {
            if (!peek('"')) {
                throw new IllegalArgumentException("Expected a JSON string.");
            }
            index++;
            StringBuilder builder = new StringBuilder();
            while (index < json.length()) {
                char character = json.charAt(index++);
                if (character == '"') {
                    return builder.toString();
                }
                if (character != '\\') {
                    builder.append(character);
                    continue;
                }
                if (index >= json.length()) {
                    break;
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (index + 4 > json.length()) {
                            throw new IllegalArgumentException("Bad unicode escape.");
                        }
                        builder.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape.");
                }
            }
            throw new IllegalArgumentException("Unclosed JSON string.");
        }

        private Object readLiteral() {
            if (json.startsWith("true", index)) {
                index += 4;
                return true;
            }
            if (json.startsWith("false", index)) {
                index += 5;
                return false;
            }
            throw new IllegalArgumentException("Expected true or false.");
        }

        private void expect(String literal) {
            if (!json.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected " + literal + ".");
            }
            index += literal.length();
        }

        private Number readNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < json.length() && "0123456789.eE+-".indexOf(json.charAt(index)) >= 0) {
                index++;
            }
            String number = json.substring(start, index);
            if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        }

        private void skip() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char character) {
            return index < json.length() && json.charAt(index) == character;
        }
    }
}

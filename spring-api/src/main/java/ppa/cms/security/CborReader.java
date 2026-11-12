package ppa.cms.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the definite-length CBOR subset used by WebAuthn attestation and COSE keys. */
final class CborReader {

    private final byte[] data;
    private int index;

    CborReader(byte[] data) {
        this.data = data;
    }

    static Object readOne(byte[] data) {
        CborReader reader = new CborReader(data);
        Object value = reader.read();
        if (reader.index != data.length) {
            throw new IllegalArgumentException("Unexpected data after the CBOR value.");
        }
        return value;
    }

    Object read() {
        int initial = unsigned();
        int major = initial >> 5;
        int info = initial & 0x1f;
        return switch (major) {
            case 0 -> length(info);
            case 1 -> -1L - length(info);
            case 2 -> bytes(length(info));
            case 3 -> new String(bytes(length(info)), StandardCharsets.UTF_8);
            case 4 -> array(length(info));
            case 5 -> map(length(info));
            case 7 -> simple(info);
            default -> throw new IllegalArgumentException("Unsupported CBOR type.");
        };
    }

    private List<Object> array(long count) {
        if (count > 256) {
            throw new IllegalArgumentException("CBOR array is too large.");
        }
        List<Object> values = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            values.add(read());
        }
        return values;
    }

    private Map<Object, Object> map(long count) {
        if (count > 64) {
            throw new IllegalArgumentException("CBOR map is too large.");
        }
        Map<Object, Object> values = new LinkedHashMap<>();
        for (long i = 0; i < count; i++) {
            values.put(read(), read());
        }
        return values;
    }

    private Object simple(int info) {
        if (info == 20) {
            return false;
        }
        if (info == 21) {
            return true;
        }
        if (info == 22) {
            return null;
        }
        throw new IllegalArgumentException("Unsupported CBOR simple value.");
    }

    private long length(int info) {
        if (info < 24) {
            return info;
        }
        if (info == 24) {
            return unsigned();
        }
        if (info == 25) {
            return ((long) unsigned() << 8) | unsigned();
        }
        if (info == 26) {
            return ((long) unsigned() << 24) | ((long) unsigned() << 16) | ((long) unsigned() << 8) | unsigned();
        }
        throw new IllegalArgumentException("Unsupported CBOR length.");
    }

    private byte[] bytes(long length) {
        if (length < 0 || length > data.length - index || length > 8192) {
            throw new IllegalArgumentException("CBOR byte string is too large.");
        }
        byte[] value = new byte[(int) length];
        System.arraycopy(data, index, value, 0, value.length);
        index += value.length;
        return value;
    }

    private int unsigned() {
        if (index >= data.length) {
            throw new IllegalArgumentException("CBOR value ended early.");
        }
        return data[index++] & 0xff;
    }
}

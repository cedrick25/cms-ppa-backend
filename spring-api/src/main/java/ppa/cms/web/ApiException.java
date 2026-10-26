package ppa.cms.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiException extends RuntimeException {

    private final int status;
    private final Map<String, Object> body;

    private ApiException(int status, Map<String, Object> body) {
        super(String.valueOf(body.get("message")));
        this.status = status;
        this.body = body;
    }

    public static ApiException message(int status, String message) {
        return new ApiException(status, Map.of("message", message));
    }

    public static ApiException errors(Map<String, List<String>> errors) {
        String message = errors.values().stream().flatMap(List::stream).findFirst().orElse("The given data was invalid.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("errors", errors);
        return new ApiException(422, body);
    }

    public int status() {
        return status;
    }

    public Map<String, Object> body() {
        return body;
    }
}

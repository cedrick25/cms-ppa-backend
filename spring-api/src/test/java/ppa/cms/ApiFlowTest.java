package ppa.cms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import ppa.cms.domain.Role;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.service.SystemImporter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ApiFlowTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SystemImporter importer;

    @BeforeEach
    void emptyDatabase() {
        jdbc.update("delete from cms_audit_events");
        jdbc.update("delete from notifications");
        jdbc.update("delete from sign_in_events");
        jdbc.update("delete from personal_access_tokens");
        jdbc.update("delete from system_status_checks");
        jdbc.update("delete from systems");
        jdbc.update("delete from audit_records");
        jdbc.update("delete from audit_source_statuses");
        jdbc.update("delete from password_reset_tokens");
        jdbc.update("delete from users");
    }

    @Test
    void healthAndSignInKeepTheCurrentContract() {
        ResponseEntity<Map<String, Object>> health = rest.exchange(url("/up"), HttpMethod.GET, null, JSON);
        assertEquals(200, health.getStatusCode().value());
        assertEquals("ok", health.getBody().get("status"));

        UserAccount admin = administrator("ada", "Correct-Horse-1");
        ResponseEntity<Map<String, Object>> wrong = rest.exchange(url("/api/sign-in"), HttpMethod.POST, json(Map.of("username", "ada", "password", "nope")), JSON);
        assertEquals(422, wrong.getStatusCode().value());

        admin.setDisabledAt(java.time.Instant.now());
        users.save(admin);
        ResponseEntity<Map<String, Object>> disabled = rest.exchange(url("/api/sign-in"), HttpMethod.POST, json(Map.of("username", "ada", "password", "Correct-Horse-1")), JSON);
        assertEquals(403, disabled.getStatusCode().value());
        admin.setDisabledAt(null);
        users.save(admin);

        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(url("/api/sign-in"), HttpMethod.POST, json(Map.of("username", "Ada", "password", "Correct-Horse-1")), JSON);
        assertEquals(200, signedIn.getStatusCode().value());
        assertEquals("Bearer", signedIn.getBody().get("token_type"));
        assertNotNull(signedIn.getBody().get("token"));
        ResponseEntity<Map<String, Object>> anonymous = rest.exchange(url("/api/users"), HttpMethod.GET, null, JSON);
        assertEquals(401, anonymous.getStatusCode().value());
    }

    @Test
    void administratorManagesUsersAndAUserCannot() {
        administrator("ada", "Correct-Horse-1");
        String token = token("ada", "Correct-Horse-1");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/users"),
                HttpMethod.POST,
                json(token, Map.of(
                        "first_name", "Grace",
                        "last_name", "Hopper",
                        "username", "Grace",
                        "email", "grace@example.com",
                        "role", "user",
                        "password", "hopper-pass",
                        "password_confirmation", "hopper-pass"
                )),
                JSON
        );
        assertEquals(201, created.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> createdUser = (Map<String, Object>) created.getBody().get("user");
        assertEquals("grace", createdUser.get("username"));
        assertEquals("Grace Hopper", createdUser.get("name"));

        String userToken = token("grace", "hopper-pass");
        ResponseEntity<Map<String, Object>> forbidden = rest.exchange(url("/api/users"), HttpMethod.GET, json(userToken, null), JSON);
        assertEquals(403, forbidden.getStatusCode().value());
        ResponseEntity<Map<String, Object>> audit = rest.exchange(url("/api/cms-audit-trail"), HttpMethod.GET, json(token, null), JSON);
        assertEquals(200, audit.getStatusCode().value());
        assertTrue(audit.getBody().get("data").toString().contains("Registered the account"));
    }

    @Test
    void systemSecretsStayHiddenOnTheListAndReadableOnTheDetail() {
        administrator("ada", "Correct-Horse-1");
        String token = token("ada", "Correct-Horse-1");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/systems"),
                HttpMethod.POST,
                json(token, Map.of("name", "Portal", "environment", "production", "domain", "https://10.0.0.5", "rdp_password", "secret-rdp", "notes", "keep private")),
                JSON
        );
        assertEquals(201, created.getStatusCode().value());
        Number id = (Number) ((Map<?, ?>) created.getBody().get("system")).get("id");
        ResponseEntity<Map<String, Object>> list = rest.exchange(url("/api/systems"), HttpMethod.GET, json(token, null), JSON);
        assertFalse(list.getBody().toString().contains("secret-rdp"));
        ResponseEntity<Map<String, Object>> detail = rest.exchange(url("/api/systems/" + id), HttpMethod.GET, json(token, null), JSON);
        assertTrue(detail.getBody().toString().contains("secret-rdp"));

        UserAccount user = user("mina", "user");
        user.setPassword(passwords.encode("mina-pass-1"));
        users.save(user);
        String userToken = token("mina", "mina-pass-1");
        ResponseEntity<Map<String, Object>> dashboard = rest.exchange(url("/api/dashboard/systems"), HttpMethod.GET, json(userToken, null), JSON);
        assertEquals(200, dashboard.getStatusCode().value());
        assertTrue(dashboard.getBody().toString().contains("10.0.0.5"));
        assertFalse(dashboard.getBody().toString().contains("secret-rdp"));
    }

    @Test
    void savedAuditRowsStayWhenASourceCannotBeRead() throws Exception {
        administrator("ada", "Correct-Horse-1");
        String token = token("ada", "Correct-Horse-1");
        jdbc.update("insert into audit_records (source, external_id, actor_name, created_at, updated_at) values ('uams', 4, 'Kept', current_timestamp, current_timestamp)");
        ResponseEntity<Map<String, Object>> refreshed = rest.exchange(url("/api/audit-trail/refresh"), HttpMethod.POST, json(token, Map.of()), JSON);
        assertEquals(200, refreshed.getStatusCode().value());
        assertTrue(refreshed.getBody().get("message").toString().contains("problems"));
        ResponseEntity<Map<String, Object>> list = rest.exchange(url("/api/audit-trail"), HttpMethod.GET, json(token, null), JSON);
        assertTrue(list.getBody().toString().contains("Kept"));
        assertNull(((Map<?, ?>) ((java.util.List<?>) list.getBody().get("data")).get(0)).get("ip"));
    }

    @Test
    void csvDryRunDoesNotSave() throws Exception {
        Path file = Files.createTempFile("systems", ".csv");
        Files.writeString(file, "System,AnyDesk\nPortal prod - Public site,123 456\n,secret\n");
        SystemImporter.ImportReport report = importer.importFile(file, true);
        assertTrue(report.errors().isEmpty());
        assertEquals(1, report.ready().size());
        assertEquals("production", report.ready().get(0).attributes().get("environment"));
        assertEquals(0, jdbc.queryForObject("select count(*) from systems", Long.class));
    }

    private UserAccount administrator(String username, String password) {
        UserAccount user = user(username, "administrator");
        user.setPassword(passwords.encode(password));
        return users.save(user);
    }

    private UserAccount user(String username, String role) {
        UserAccount user = new UserAccount();
        user.setName(username);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setRole("administrator".equals(role) ? Role.ADMINISTRATOR : Role.USER);
        user.setPassword(passwords.encode("unused"));
        return user;
    }

    private String token(String username, String password) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(url("/api/sign-in"), HttpMethod.POST, json(Map.of("username", username, "password", password)), JSON);
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        return String.valueOf(response.getBody().get("token"));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        return json(null, body);
    }

    private HttpEntity<Map<String, Object>> json(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }
}

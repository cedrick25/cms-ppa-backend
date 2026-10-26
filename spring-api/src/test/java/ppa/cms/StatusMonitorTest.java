package ppa.cms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import ppa.cms.domain.Role;
import ppa.cms.domain.SystemEnvironment;
import ppa.cms.persistence.RegisteredSystem;
import ppa.cms.persistence.RegisteredSystemRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.service.StatusMonitor;

@SpringBootTest
@ActiveProfiles("test")
class StatusMonitorTest {

    @Autowired
    private StatusMonitor monitor;

    @Autowired
    private ProbeHolder probe;

    @Autowired
    private RegisteredSystemRepository systems;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reportsDownOnlyAfterTwoFailuresAndRecoveryAfterThat() {
        jdbc.update("delete from notifications");
        jdbc.update("delete from system_status_checks");
        jdbc.update("delete from systems");
        jdbc.update("delete from users");
        UserAccount user = new UserAccount();
        user.setName("Ada");
        user.setUsername("ada");
        user.setEmail("ada@example.com");
        user.setRole(Role.ADMINISTRATOR);
        user.setPassword(passwords.encode("Correct-Horse-1"));
        users.save(user);
        RegisteredSystem system = new RegisteredSystem();
        system.setName("Portal");
        system.setEnvironment(SystemEnvironment.PRODUCTION);
        system.setDomain("https://ppa.example");
        systems.save(system);

        probe.outcome = StatusMonitor.ProbeOutcome.failed(new java.net.ConnectException("Connection refused"));
        monitor.checkDue(true);
        assertEquals(0, count());
        monitor.checkDue(true);
        assertEquals(1, count());
        assertTrue(jdbc.queryForObject("select data from notifications", String.class).contains("\"event\":\"down\""));

        probe.outcome = new StatusMonitor.ProbeOutcome(new StatusMonitor.ProbeResponse(200, 12), false, null);
        monitor.checkDue(true);
        assertEquals(2, count());
        assertTrue(jdbc.queryForObject("select data from notifications where data like '%\"event\":\"recovered\"%'", String.class).contains("\"event\":\"recovered\""));
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from notifications", Integer.class);
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        @Primary
        ProbeHolder probeHolder() {
            return new ProbeHolder();
        }
    }

    static class ProbeHolder implements StatusMonitor.ReachabilityProbe {
        StatusMonitor.ProbeOutcome outcome = StatusMonitor.ProbeOutcome.failed(new java.net.ConnectException("Connection refused"));

        @Override
        public StatusMonitor.ProbeOutcome get(String url) {
            return outcome;
        }
    }
}

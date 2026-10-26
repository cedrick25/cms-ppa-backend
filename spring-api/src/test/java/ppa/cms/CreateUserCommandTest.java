package ppa.cms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import ppa.cms.command.CliCommands;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "cli"})
class CreateUserCommandTest {

    @Autowired
    private CliCommands commands;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsAnAdministratorAndRejectsAWeakPassword() {
        jdbc.update("delete from users");
        assertEquals(1, commands.createAdministrator("Ada Lovelace", "Ada", "ada@example.com", "short"));
        assertEquals(0, jdbc.queryForObject("select count(*) from users", Integer.class));
        assertEquals(0, commands.createAdministrator("Ada Lovelace", "Ada", "ada@example.com", "Correct-Horse-1"));
        assertEquals("administrator", jdbc.queryForObject("select role from users", String.class));
        assertTrue(jdbc.queryForObject("select password from users", String.class).startsWith("$2"));
    }
}

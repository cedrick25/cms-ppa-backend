package ppa.cms.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final CmsProperties properties;

    public SchemaInitializer(JdbcTemplate jdbc, CmsProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.isSchemaInit() || tableExists()) {
            return;
        }
        String script = new String(new ClassPathResource("schema.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : script.split(";")) {
            String sql = Arrays.stream(statement.split("\\R")).filter(line -> !line.trim().startsWith("--")).reduce("", (left, right) -> left + "\n" + right).trim();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
    }

    private boolean tableExists() {
        try {
            jdbc.queryForObject("select count(*) from users", Long.class);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

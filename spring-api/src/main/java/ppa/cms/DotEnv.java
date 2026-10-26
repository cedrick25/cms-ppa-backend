package ppa.cms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a local .env file into system properties before Spring starts.
 * Variables already set in the environment are left alone.
 */
public final class DotEnv {

    private DotEnv() {
    }

    public static void load() {
        Path env = Path.of(".env");
        if (!Files.isRegularFile(env)) {
            env = Path.of("..", ".env");
        }
        if (!Files.isRegularFile(env)) {
            env = Path.of("spring-api", ".env");
        }
        if (!Files.isRegularFile(env)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(env)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                int split = line.indexOf('=');
                if (split < 1) {
                    continue;
                }
                String key = line.substring(0, split).trim();
                String value = stripQuotes(line.substring(split + 1).trim());
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + env.toAbsolutePath(), exception);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}

package ppa.cms.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ppa.cms.config.CmsProperties;
import ppa.cms.domain.Role;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.service.AuditService;
import ppa.cms.service.StatusMonitor;
import ppa.cms.service.SystemImporter;
import org.springframework.security.crypto.password.PasswordEncoder;

@Component
@Profile("cli")
public class CliCommands implements CommandLineRunner, ExitCodeGenerator {

    public static final Set<String> NAMES = Set.of("create-user", "import-systems", "check-status", "audit-sync");

    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final CmsProperties properties;
    private final SystemImporter importer;
    private final StatusMonitor monitor;
    private final AuditService audit;
    private int code;

    public CliCommands(
            UserAccountRepository users,
            PasswordEncoder passwords,
            CmsProperties properties,
            SystemImporter importer,
            StatusMonitor monitor,
            AuditService audit
    ) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
        this.importer = importer;
        this.monitor = monitor;
        this.audit = audit;
    }

    public static boolean isCommand(String name) {
        return NAMES.contains(name);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0 || !isCommand(args[0])) {
            System.err.println("Use create-user, import-systems, check-status, or audit-sync.");
            code = 1;
            return;
        }
        Parsed parsed = Parsed.of(args);
        code = switch (args[0]) {
            case "create-user" -> createUser(parsed);
            case "import-systems" -> importSystems(parsed);
            case "check-status" -> {
                System.out.println(monitor.checkDue(parsed.flags().contains("all")));
                yield 0;
            }
            case "audit-sync" -> {
                audit.sync().forEach(result -> System.out.println(result.get("label") + ": saved " + result.get("saved")));
                yield 0;
            }
            default -> 1;
        };
    }

    @Override
    public int getExitCode() {
        return code;
    }

    public int createAdministrator(String name, String username, String email, String password) {
        return createUser(new Parsed(
                java.util.Map.of("name", name, "username", username, "email", email, "password", password),
                java.util.List.of(),
                java.util.List.of()
        ));
    }

    private int createUser(Parsed parsed) {
        String passwordOption = parsed.options().get("password");
        String password = passwordOption != null && !passwordOption.isBlank() ? passwordOption : properties.getAdmin().getPassword();
        String name = first(parsed.options().get("name"), properties.getAdmin().getName());
        String username = first(parsed.options().get("username"), properties.getAdmin().getUsername()).toLowerCase(Locale.ROOT).trim();
        String email = first(parsed.options().get("email"), properties.getAdmin().getEmail()).toLowerCase(Locale.ROOT).trim();
        if (passwordOption != null && !passwordOption.isBlank()) {
            System.err.println("The password was supplied with --password and may remain in shell history. Prefer CMS_ADMIN_PASSWORD.");
        }
        List<String> errors = new ArrayList<>();
        if (name.isBlank()) {
            errors.add("A display name is required. Set CMS_ADMIN_NAME or pass --name.");
        } else if (name.length() > 100) {
            errors.add("The name field must not be greater than 100 characters.");
        }
        if (username.isBlank()) {
            errors.add("A username is required. Set CMS_ADMIN_USERNAME or pass --username.");
        } else if (username.length() < 3 || username.length() > 50 || !username.matches("[a-z0-9._-]+")) {
            errors.add("Username must be 3 to 50 characters and use only letters, numbers, dots, underscores, and hyphens.");
        } else if (users.existsByUsername(username)) {
            errors.add("An account with that username already exists.");
        }
        if (email.isBlank()) {
            errors.add("An email address is required. Set CMS_ADMIN_EMAIL or pass --email.");
        } else if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length() > 255) {
            errors.add("Enter a valid email address.");
        } else if (users.existsByEmail(email)) {
            errors.add("An account with that email address already exists.");
        }
        if (password == null || password.isBlank()) {
            errors.add("A password is required. Set CMS_ADMIN_PASSWORD in the environment for this command, then remove it. Do not put the password in source code.");
        } else {
            if (password.length() < 12) {
                errors.add("The password field must be at least 12 characters.");
            }
            if (password.chars().noneMatch(Character::isLetter)) {
                errors.add("The password field must contain at least one letter.");
            }
            if (password.chars().noneMatch(Character::isUpperCase) || password.chars().noneMatch(Character::isLowerCase)) {
                errors.add("The password field must contain at least one uppercase and one lowercase letter.");
            }
            if (password.chars().noneMatch(Character::isDigit)) {
                errors.add("The password field must contain at least one number.");
            }
        }
        if (!errors.isEmpty()) {
            errors.forEach(System.err::println);
            return 1;
        }
        UserAccount user = new UserAccount();
        user.setName(name.trim());
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(Role.ADMINISTRATOR);
        user.setPassword(passwords.encode(password));
        users.save(user);
        System.out.println("Administrator account created for username \"" + username + "\".");
        System.out.println("Remove CMS_ADMIN_PASSWORD from the environment now. The password was not written to a file or log.");
        return 0;
    }

    private int importSystems(Parsed parsed) throws Exception {
        if (parsed.positionals().isEmpty()) {
            System.err.println("Give the path to the CSV file.");
            return 1;
        }
        boolean dryRun = parsed.flags().contains("dry-run");
        SystemImporter.ImportReport report = importer.importFile(Path.of(parsed.positionals().get(0)), dryRun);
        if (report.failure() != null) {
            System.err.println(report.failure());
            return 1;
        }
        if (!report.errors().isEmpty()) {
            report.errors().forEach(System.err::println);
            System.out.println("Nothing was imported. Fix the rows above in the spreadsheet and export it again.");
            return 1;
        }
        for (SystemImporter.Ready ready : report.ready()) {
            System.out.println(ready.row() + "  " + ready.attributes().get("name") + "  " + (ready.attributes().get("environment") == null ? "-" : ready.attributes().get("environment")));
        }
        for (SystemImporter.Skipped skipped : report.skipped()) {
            System.err.println("Row " + skipped.row() + ": skipped \"" + skipped.name() + "\" because a system with that name is already registered.");
        }
        if (dryRun) {
            System.out.println("Dry run: " + report.ready().size() + " system(s) would be imported. Nothing was saved.");
        } else {
            System.out.println(report.ready().size() + " system(s) imported.");
        }
        return 0;
    }

    private static String first(String option, String configured) {
        if (option != null && !option.isBlank()) {
            return option.trim();
        }
        return configured == null ? "" : configured.trim();
    }

    private record Parsed(Map<String, String> options, List<String> positionals, List<String> flags) {
        static Parsed of(String[] args) {
            Map<String, String> options = new LinkedHashMap<>();
            List<String> positionals = new ArrayList<>();
            List<String> flags = new ArrayList<>();
            for (int index = 1; index < args.length; index++) {
                String arg = args[index];
                if (!arg.startsWith("--")) {
                    positionals.add(arg);
                    continue;
                }
                String body = arg.substring(2);
                int equals = body.indexOf('=');
                if (equals >= 0) {
                    options.put(body.substring(0, equals), body.substring(equals + 1));
                } else if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                    options.put(body, args[++index]);
                } else {
                    flags.add(body);
                    options.put(body, "true");
                }
            }
            return new Parsed(options, positionals, flags);
        }
    }
}

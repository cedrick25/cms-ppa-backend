package ppa.cms.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.domain.SystemEnvironment;

@Service
public class SystemImporter {

    private static final Pattern ANYDESK = Pattern.compile("^(\\d[\\d ]*\\d|[\\w.-]+@ad)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUSTDESK = Pattern.compile("^\\d[\\d ]*\\d$");
    private static final Pattern SSH_TARGET = Pattern.compile("^ssh\\s+([^@\\s]+)@(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELED_HOST = Pattern.compile("^(\\S+)\\s*\\((RDP|SSH)\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDRESS = Pattern.compile("^(https?://)?(localhost|[a-z0-9-]+(\\.[a-z0-9-]+)+)(:\\d+)?(/\\S*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY = Pattern.compile("(?<![a-z])old(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCTION = Pattern.compile("(?<![a-z])prod(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST = Pattern.compile("(?<![a-z])(test|stg|uat|demo|training)(?![a-z])", Pattern.CASE_INSENSITIVE);

    private final SystemService systems;

    public SystemImporter(SystemService systems) {
        this.systems = systems;
    }

    public ImportReport inspect(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return ImportReport.failed("Can't read the file at " + path + ".");
        }
        List<String[]> rows = readCsv(path);
        if (rows.isEmpty() || clean(rows.get(0).length == 0 ? "" : rows.get(0)[0]).equals("System") == false) {
            return ImportReport.failed("The first column of the header row must be \"System\". Export the credentials sheet as CSV and try again.");
        }
        List<Block> blocks = blocks(rows);
        List<Ready> ready = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Block block : blocks) {
            Map<String, String> attributes = attributes(block.rows());
            String name = attributes.get("name");
            if (seen.contains(name.toLowerCase(Locale.ROOT)) || systems.nameTaken(name)) {
                skipped.add(new Skipped(block.row(), name));
                continue;
            }
            seen.add(name.toLowerCase(Locale.ROOT));
            try {
                systems.validateImported(attributes);
            } catch (ppa.cms.web.ApiException exception) {
                Object details = exception.body().get("errors");
                if (details instanceof Map<?, ?> map) {
                    for (Object messages : map.values()) {
                        if (messages instanceof List<?> list) {
                            for (Object message : list) {
                                errors.add("Row " + block.row() + " (" + name + "): " + message);
                            }
                        }
                    }
                } else {
                    errors.add("Row " + block.row() + " (" + name + "): " + exception.getMessage());
                }
                continue;
            }
            ready.add(new Ready(block.row(), attributes));
        }
        return new ImportReport(null, ready, skipped, errors);
    }

    @Transactional
    public ImportReport importFile(Path path, boolean dryRun) throws IOException {
        ImportReport report = inspect(path);
        if (report.failure() != null || !report.errors().isEmpty() || dryRun) {
            return report;
        }
        for (Ready ready : report.ready()) {
            systems.saveImported(ready.attributes());
        }
        return report;
    }

    private List<Block> blocks(List<String[]> rows) {
        List<Block> blocks = new ArrayList<>();
        int sheetRow = 1;
        for (int index = 1; index < rows.size(); index++) {
            sheetRow++;
            String[] cells = new String[Math.max(9, rows.get(index).length)];
            for (int column = 0; column < cells.length; column++) {
                cells[column] = column < rows.get(index).length ? clean(rows.get(index)[column]) : "";
            }
            if (!cells[0].isEmpty()) {
                List<String[]> blockRows = new ArrayList<>();
                blockRows.add(cells);
                blocks.add(new Block(sheetRow, blockRows));
            } else if (!blocks.isEmpty()) {
                blocks.get(blocks.size() - 1).rows().add(cells);
            }
        }
        return blocks;
    }

    private Map<String, String> attributes(List<String[]> rows) {
        String[] nameParts = rows.get(0)[0].split(" - ", 2);
        String name = nameParts[0].trim().replaceAll("\\s+", " ");
        String description = nameParts.length > 1 ? nameParts[1].trim() : "";
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("name", name);
        attributes.put("description", description);
        SystemEnvironment environment = environmentFor(name);
        attributes.put("environment", environment == null ? null : environment.value());
        List<String> unmapped = new ArrayList<>();
        Mapped anydesk = remoteDesk(column(rows, 1), "anydesk", ANYDESK);
        Mapped rustdesk = remoteDesk(column(rows, 2), "rustdesk", RUSTDESK);
        Mapped server = server(column(rows, 3), name);
        Mapped ip = address(column(rows, 4), "local_ip", "local", false);
        Mapped mysql = address(column(rows, 5), "mysql_host", "mysql", true);
        Mapped domain = address(column(rows, 6), "domain", "domain", false);
        Mapped vm = address(column(rows, 7), "vm_host", "vm", false);
        add(attributes, unmapped, "AnyDesk", anydesk);
        add(attributes, unmapped, "RustDesk", rustdesk);
        add(attributes, unmapped, "Server Cred", server);
        add(attributes, unmapped, "IP", ip);
        add(attributes, unmapped, "MySQL", mysql);
        add(attributes, unmapped, "Domain", domain);
        add(attributes, unmapped, "VM", vm);
        attributes.put("notes", notes(rows, unmapped));
        attributes.replaceAll((key, value) -> value == null || value.isEmpty() ? null : value);
        return attributes;
    }

    private static void add(Map<String, String> attributes, List<String> unmapped, String label, Mapped mapped) {
        attributes.putAll(mapped.fields());
        if (!mapped.leftover().isEmpty()) {
            unmapped.add(label + ": " + String.join(" / ", mapped.leftover()));
        }
    }

    private static SystemEnvironment environmentFor(String name) {
        if (LEGACY.matcher(name).find()) {
            return SystemEnvironment.LEGACY;
        }
        if (PRODUCTION.matcher(name).find()) {
            return SystemEnvironment.PRODUCTION;
        }
        if (TEST.matcher(name).find()) {
            return SystemEnvironment.TEST;
        }
        return null;
    }

    private static Mapped remoteDesk(List<String> values, String prefix, Pattern pattern) {
        if (values.isEmpty() || !pattern.matcher(values.get(0)).matches()) {
            return new Mapped(Map.of(), filled(values));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(prefix + "_id", values.get(0));
        fields.put(prefix + "_password", values.size() > 1 ? values.get(1) : "");
        return new Mapped(fields, filled(values.subList(Math.min(2, values.size()), values.size())));
    }

    private static Mapped server(List<String> values, String systemName) {
        String first = values.isEmpty() ? "" : values.get(0);
        var ssh = SSH_TARGET.matcher(first);
        if (ssh.matches()) {
            List<String> passwords = new ArrayList<>();
            for (String value : values.subList(1, values.size())) {
                if (!value.isEmpty() && !value.equals(ssh.group(1))) {
                    passwords.add(value);
                }
            }
            return new Mapped(Map.of(
                    "ssh_host", ssh.group(2),
                    "ssh_username", ssh.group(1),
                    "ssh_password", passwords.isEmpty() ? "" : passwords.get(0)
            ), passwords.size() > 1 ? passwords.subList(1, passwords.size()) : List.of());
        }
        var labeled = LABELED_HOST.matcher(first);
        if (labeled.matches()) {
            String prefix = labeled.group(2).toLowerCase(Locale.ROOT);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(prefix + "_host", labeled.group(1));
            fields.put(prefix + "_username", values.size() > 1 ? values.get(1) : "");
            fields.put(prefix + "_password", values.size() > 2 ? values.get(2) : "");
            return new Mapped(fields, filled(values.subList(Math.min(3, values.size()), values.size())));
        }
        if (first.isEmpty()) {
            return new Mapped(Map.of(), filled(values));
        }
        String prefix = systemName.toLowerCase(Locale.ROOT).contains("windows") ? "rdp" : "ssh";
        return new Mapped(Map.of(
                prefix + "_username", first,
                prefix + "_password", values.size() > 1 ? values.get(1) : ""
        ), filled(values.subList(Math.min(2, values.size()), values.size())));
    }

    private static Mapped address(List<String> values, String addressField, String prefix, boolean requireHost) {
        String address = values.isEmpty() ? "" : values.get(0);
        if (requireHost && !address.isEmpty() && !ADDRESS.matcher(address).matches()) {
            return new Mapped(Map.of(), filled(values));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(addressField, address);
        fields.put(prefix + "_username", values.size() > 1 ? values.get(1) : "");
        fields.put(prefix + "_password", values.size() > 2 ? values.get(2) : "");
        return new Mapped(fields, filled(values.subList(Math.min(3, values.size()), values.size())));
    }

    private static String notes(List<String[]> rows, List<String> unmapped) {
        List<String> paragraphs = new ArrayList<>();
        int columns = rows.stream().mapToInt(row -> row.length).max().orElse(0);
        for (int index = 8; index < columns; index++) {
            List<String> values = new ArrayList<>();
            for (String[] row : rows) {
                if (index < row.length && !row[index].isEmpty()) {
                    values.add(row[index]);
                }
            }
            if (!values.isEmpty()) {
                paragraphs.add(String.join("\n", values));
            }
        }
        if (!unmapped.isEmpty()) {
            paragraphs.add("Imported from the spreadsheet as written:\n" + String.join("\n", unmapped));
        }
        return String.join("\n\n", paragraphs);
    }

    private static List<String> column(List<String[]> rows, int index) {
        List<String> values = new ArrayList<>();
        for (String[] row : rows) {
            values.add(index < row.length ? row[index] : "");
        }
        return values;
    }

    private static List<String> filled(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isEmpty()).toList();
    }

    private static String clean(String cell) {
        return cell == null ? "" : cell.replace("\uFEFF", "").trim();
    }

    static List<String[]> readCsv(Path path) throws IOException {
        String text = Files.readString(path);
        List<String[]> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(character);
                }
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (character == '\n') {
                cells.add(cell.toString());
                rows.add(cells.toArray(String[]::new));
                cells = new ArrayList<>();
                cell.setLength(0);
            } else if (character != '\r') {
                cell.append(character);
            }
        }
        if (quoted || cell.length() > 0 || !cells.isEmpty()) {
            cells.add(cell.toString());
            rows.add(cells.toArray(String[]::new));
        }
        return rows;
    }

    public record Ready(int row, Map<String, String> attributes) {
    }

    public record Skipped(int row, String name) {
    }

    public record ImportReport(String failure, List<Ready> ready, List<Skipped> skipped, List<String> errors) {
        static ImportReport failed(String failure) {
            return new ImportReport(failure, List.of(), List.of(), List.of());
        }
    }

    private record Block(int row, List<String[]> rows) {
    }

    private record Mapped(Map<String, String> fields, List<String> leftover) {
    }
}

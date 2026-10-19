<?php

namespace App\Console\Commands;

use App\Enums\SystemEnvironment;
use App\Http\Requests\Concerns\ValidatesSystemDetails;
use App\Models\System;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Validator;

class ImportSystemsCommand extends Command
{
    use ValidatesSystemDetails;

    protected $signature = 'cms:import-systems
        {path : CSV export of the credentials spreadsheet (System, Anydesk, RustDesk, Server Cred, IP, MySql, Domain, VM, Notes)}
        {--dry-run : Check the file and list what would be imported without saving anything}';

    protected $description = 'Import system credentials from a CSV export of the credentials spreadsheet';

    /**
     * Spreadsheet column positions. Every column after VM is treated as notes.
     */
    private const SYSTEM = 0;

    private const ANYDESK = 1;

    private const RUSTDESK = 2;

    private const SERVER = 3;

    private const IP = 4;

    private const MYSQL = 5;

    private const DOMAIN = 6;

    private const VM = 7;

    private const FIRST_NOTES_COLUMN = 8;

    /**
     * @var array<int, string>
     */
    private const COLUMN_LABELS = [
        self::ANYDESK => 'AnyDesk',
        self::RUSTDESK => 'RustDesk',
        self::SERVER => 'Server Cred',
        self::IP => 'IP',
        self::MYSQL => 'MySQL',
        self::DOMAIN => 'Domain',
        self::VM => 'VM',
    ];

    /**
     * @var array<string, string>
     */
    private const FIELD_GROUPS = [
        'anydesk_id' => 'AnyDesk', 'anydesk_password' => 'AnyDesk',
        'rustdesk_id' => 'RustDesk', 'rustdesk_password' => 'RustDesk',
        'rdp_host' => 'RDP', 'rdp_username' => 'RDP', 'rdp_password' => 'RDP',
        'ssh_host' => 'SSH', 'ssh_username' => 'SSH', 'ssh_password' => 'SSH',
        'local_ip' => 'Local IP', 'local_username' => 'Local IP', 'local_password' => 'Local IP',
        'mysql_host' => 'MySQL', 'mysql_username' => 'MySQL', 'mysql_password' => 'MySQL',
        'domain' => 'Domain', 'domain_username' => 'Domain', 'domain_password' => 'Domain',
        'vm_host' => 'VM', 'vm_username' => 'VM', 'vm_password' => 'VM',
        'notes' => 'Notes',
    ];

    public function handle(): int
    {
        $path = (string) $this->argument('path');

        if (! is_file($path) || ! is_readable($path)) {
            $this->error("Can't read the file at {$path}.");

            return self::FAILURE;
        }

        $blocks = $this->readBlocks($path);

        if ($blocks === null) {
            $this->error('The first column of the header row must be "System". Export the credentials sheet as CSV and try again.');

            return self::FAILURE;
        }

        $toImport = [];
        $skipped = [];
        $errors = [];
        $seenNames = [];

        foreach ($blocks as $block) {
            $attributes = $this->attributesFor($block['rows']);
            $name = $attributes['name'];

            if (isset($seenNames[mb_strtolower($name)]) || System::query()->where('name', $name)->exists()) {
                $skipped[] = [$block['row'], $name];

                continue;
            }
            $seenNames[mb_strtolower($name)] = true;

            $validator = Validator::make($attributes, $this->systemDetailRules(), $this->systemDetailMessages());

            if ($validator->fails()) {
                foreach ($validator->errors()->all() as $message) {
                    $errors[] = "Row {$block['row']} ({$name}): {$message}";
                }

                continue;
            }

            $toImport[] = ['row' => $block['row'], 'attributes' => $validator->validated()];
        }

        if ($errors !== []) {
            foreach ($errors as $error) {
                $this->error($error);
            }
            $this->line('Nothing was imported. Fix the rows above in the spreadsheet and export it again.');

            return self::FAILURE;
        }

        $this->table(
            ['Row', 'System', 'Environment', 'Saved details'],
            array_map(fn (array $entry): array => [
                $entry['row'],
                $entry['attributes']['name'],
                $entry['attributes']['environment'] ?? '-',
                $this->summarize($entry['attributes']),
            ], $toImport),
        );

        foreach ($skipped as [$row, $name]) {
            $this->warn("Row {$row}: skipped \"{$name}\" because a system with that name is already registered.");
        }

        if ($this->option('dry-run')) {
            $this->info('Dry run: '.count($toImport).' system(s) would be imported. Nothing was saved.');

            return self::SUCCESS;
        }

        DB::transaction(function () use ($toImport): void {
            foreach ($toImport as $entry) {
                System::query()->create($entry['attributes']);
            }
        });

        Log::info('System credentials imported.', [
            'file' => basename($path),
            'imported' => array_map(fn (array $entry): string => $entry['attributes']['name'], $toImport),
            'skipped' => array_column($skipped, 1),
        ]);

        $this->info(count($toImport).' system(s) imported.');

        return self::SUCCESS;
    }

    /**
     * Group spreadsheet rows into one block per system: a row with a system name followed by
     * the rows below it (usernames, then passwords) until the next system name.
     *
     * @return list<array{row: int, rows: list<list<string>>}>|null
     */
    private function readBlocks(string $path): ?array
    {
        $handle = fopen($path, 'r');
        $header = fgetcsv($handle, null, ',', '"', '');

        if (! is_array($header) || $this->clean((string) ($header[0] ?? '')) !== 'System') {
            fclose($handle);

            return null;
        }

        $blocks = [];
        $sheetRow = 1;

        while (($record = fgetcsv($handle, null, ',', '"', '')) !== false) {
            $sheetRow++;
            $cells = array_map(fn (?string $cell): string => $this->clean((string) $cell), $record);

            if (($cells[self::SYSTEM] ?? '') !== '') {
                $blocks[] = ['row' => $sheetRow, 'rows' => [$cells]];
            } elseif ($blocks !== []) {
                $blocks[array_key_last($blocks)]['rows'][] = $cells;
            }
        }

        fclose($handle);

        return $blocks;
    }

    /**
     * @param  list<list<string>>  $rows
     * @return array<string, string|null>
     */
    private function attributesFor(array $rows): array
    {
        [$name, $description] = array_pad(explode(' - ', $rows[0][self::SYSTEM], 2), 2, '');
        $name = (string) preg_replace('/\s+/', ' ', trim($name));
        $column = fn (int $index): array => array_map(fn (array $row): string => $row[$index] ?? '', $rows);

        $attributes = [
            'name' => $name,
            'description' => trim($description),
            'environment' => $this->environmentFor($name)?->value,
        ];
        $unmapped = [];

        $mapped = [
            [self::ANYDESK, $this->remoteDesk($column(self::ANYDESK), 'anydesk', '/^(\d[\d ]*\d|[\w.-]+@ad)$/i')],
            [self::RUSTDESK, $this->remoteDesk($column(self::RUSTDESK), 'rustdesk', '/^\d[\d ]*\d$/')],
            [self::SERVER, $this->serverCredentials($column(self::SERVER), $name)],
            [self::IP, $this->addressGroup($column(self::IP), 'local_ip', 'local')],
            [self::MYSQL, $this->addressGroup($column(self::MYSQL), 'mysql_host', 'mysql', requireHost: true)],
            [self::DOMAIN, $this->addressGroup($column(self::DOMAIN), 'domain', 'domain')],
            [self::VM, $this->addressGroup($column(self::VM), 'vm_host', 'vm')],
        ];

        foreach ($mapped as [$index, [$fields, $leftover]]) {
            $attributes += $fields;
            if ($leftover !== []) {
                $unmapped[] = self::COLUMN_LABELS[$index].': '.implode(' / ', $leftover);
            }
        }

        $attributes['notes'] = $this->notesFor($rows, $unmapped);

        return array_map(fn (?string $value): ?string => $value === null || $value === '' ? null : $value, $attributes);
    }

    private function environmentFor(string $name): ?SystemEnvironment
    {
        return match (true) {
            preg_match('/(?<![a-z])old(?![a-z])/i', $name) === 1 => SystemEnvironment::Legacy,
            preg_match('/(?<![a-z])prod(?![a-z])/i', $name) === 1 => SystemEnvironment::Production,
            preg_match('/(?<![a-z])(test|stg|uat|demo|training)(?![a-z])/i', $name) === 1 => SystemEnvironment::Test,
            default => null,
        };
    }

    /**
     * AnyDesk and RustDesk cells hold the ID, then the password. Anything that isn't an ID is left for notes.
     *
     * @param  list<string>  $values
     * @return array{0: array<string, string>, 1: list<string>}
     */
    private function remoteDesk(array $values, string $prefix, string $idPattern): array
    {
        if (preg_match($idPattern, $values[0] ?? '') !== 1) {
            return [[], $this->filled($values)];
        }

        return [
            ["{$prefix}_id" => $values[0], "{$prefix}_password" => $values[1] ?? ''],
            $this->filled(array_slice($values, 2)),
        ];
    }

    /**
     * Server Cred cells are written as "ssh user@host", "host (RDP)" / "host (SSH)", or just a
     * username followed by its password. A bare username is RDP for Windows systems, SSH otherwise.
     *
     * @param  list<string>  $values
     * @return array{0: array<string, string>, 1: list<string>}
     */
    private function serverCredentials(array $values, string $systemName): array
    {
        $first = $values[0] ?? '';

        if (preg_match('/^ssh\s+([^@\s]+)@(\S+)$/i', $first, $match) === 1) {
            $passwords = array_values(array_filter(
                array_slice($values, 1),
                fn (string $value): bool => $value !== '' && $value !== $match[1],
            ));

            return [
                ['ssh_host' => $match[2], 'ssh_username' => $match[1], 'ssh_password' => $passwords[0] ?? ''],
                array_slice($passwords, 1),
            ];
        }

        if (preg_match('/^(\S+)\s*\((RDP|SSH)\)$/i', $first, $match) === 1) {
            $prefix = strtolower($match[2]);

            return [
                ["{$prefix}_host" => $match[1], "{$prefix}_username" => $values[1] ?? '', "{$prefix}_password" => $values[2] ?? ''],
                $this->filled(array_slice($values, 3)),
            ];
        }

        if ($first === '') {
            return [[], $this->filled($values)];
        }

        $prefix = stripos($systemName, 'windows') !== false ? 'rdp' : 'ssh';

        return [
            ["{$prefix}_username" => $first, "{$prefix}_password" => $values[1] ?? ''],
            $this->filled(array_slice($values, 2)),
        ];
    }

    /**
     * IP, MySQL, Domain, and VM cells hold the address, then the username, then the password.
     *
     * @param  list<string>  $values
     * @return array{0: array<string, string>, 1: list<string>}
     */
    private function addressGroup(array $values, string $addressField, string $prefix, bool $requireHost = false): array
    {
        $address = $values[0] ?? '';

        if ($requireHost && $address !== '' && ! $this->looksLikeAddress($address)) {
            return [[], $this->filled($values)];
        }

        return [
            [$addressField => $address, "{$prefix}_username" => $values[1] ?? '', "{$prefix}_password" => $values[2] ?? ''],
            $this->filled(array_slice($values, 3)),
        ];
    }

    private function looksLikeAddress(string $value): bool
    {
        return preg_match('~^(https?://)?(localhost|[a-z0-9-]+(\.[a-z0-9-]+)+)(:\d+)?(/\S*)?$~i', $value) === 1;
    }

    /**
     * @param  list<list<string>>  $rows
     * @param  list<string>  $unmapped
     */
    private function notesFor(array $rows, array $unmapped): string
    {
        $paragraphs = [];
        $columnCount = max(array_map('count', $rows));

        for ($index = self::FIRST_NOTES_COLUMN; $index < $columnCount; $index++) {
            $values = $this->filled(array_map(fn (array $row): string => $row[$index] ?? '', $rows));
            if ($values !== []) {
                $paragraphs[] = implode("\n", $values);
            }
        }

        if ($unmapped !== []) {
            $paragraphs[] = "Imported from the spreadsheet as written:\n".implode("\n", $unmapped);
        }

        return implode("\n\n", $paragraphs);
    }

    /**
     * @param  array<string, string|null>  $attributes
     */
    private function summarize(array $attributes): string
    {
        $groups = [];
        foreach (self::FIELD_GROUPS as $field => $group) {
            if (($attributes[$field] ?? null) !== null) {
                $groups[$group] = true;
            }
        }

        return $groups === [] ? 'Name only' : implode(', ', array_keys($groups));
    }

    /**
     * @param  list<string>  $values
     * @return list<string>
     */
    private function filled(array $values): array
    {
        return array_values(array_filter($values, fn (string $value): bool => $value !== ''));
    }

    private function clean(string $cell): string
    {
        return trim(str_replace("\u{FEFF}", '', $cell));
    }
}

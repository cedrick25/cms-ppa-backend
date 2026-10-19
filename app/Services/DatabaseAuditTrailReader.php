<?php

namespace App\Services;

use App\Contracts\AuditTrailReader;
use App\Enums\AuditSource;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Schema;
use Throwable;

/**
 * Reads the audit table that UAMS and CMIS already maintain. The session is read-only,
 * so a bug in this class cannot insert, update, or delete rows in those systems.
 */
class DatabaseAuditTrailReader implements AuditTrailReader
{
    private const NAME_COLUMNS = ['USER_FULLNAME', 'USER_FNAME', 'USER_MNAME', 'USER_LNAME', 'USER_EMAIL'];

    public function read(AuditSource $source): AuditSnapshot
    {
        /** @var array<string, mixed>|null $settings */
        $settings = config("cms.audit_trail.sources.{$source->value}");
        $database = is_array($settings) ? trim((string) ($settings['database'] ?? '')) : '';
        $connection = is_array($settings) ? (string) ($settings['connection'] ?? '') : '';

        if ($database === '' || $connection === '') {
            return AuditSnapshot::unavailable("{$source->label()} is not configured for audit reading.");
        }

        try {
            $this->readOnly($connection);

            if (! Schema::connection($connection)->hasTable('audit_trail')) {
                return AuditSnapshot::unavailable("{$source->label()} has no audit trail to read.");
            }

            return new AuditSnapshot(true, null, $this->records($connection));
        } catch (Throwable $exception) {
            Log::warning('Audit trail could not be read.', [
                'source' => $source->value,
                'exception' => $exception::class,
                'message' => $exception->getMessage(),
            ]);

            return AuditSnapshot::unavailable("{$source->label()} could not be read. Records already saved are unchanged.");
        }
    }

    /**
     * @return list<array{
     *     external_id: int,
     *     occurred_at: string|null,
     *     actor_name: string|null,
     *     user_level: string|null,
     *     field_office: string|null,
     *     module: string|null,
     *     action: string|null
     * }>
     */
    private function records(string $connection): array
    {
        $schema = Schema::connection($connection);
        $hasUsers = $schema->hasTable('USERS');
        $userColumns = $hasUsers ? $schema->getColumnListing('USERS') : [];
        $hasLevels = $schema->hasTable('USER_LEVEL');
        $levelColumns = $hasLevels ? $schema->getColumnListing('USER_LEVEL') : [];
        $joinedLevel = $hasUsers && $hasLevels
            && $this->hasColumn($levelColumns, 'USER_LEVEL_NAME')
            && $this->hasColumn($userColumns, 'USER_LEVEL_ID');

        $query = DB::connection($connection)->table('audit_trail as a');

        if ($hasUsers) {
            $query->leftJoin('USERS as u', 'u.USER_ID', '=', 'a.created_by');
        }

        if ($joinedLevel) {
            $query->leftJoin('USER_LEVEL as l', 'l.USER_LEVEL_ID', '=', 'u.USER_LEVEL_ID');
        }

        $select = [
            'a.audit_trail_id as external_id',
            'a.created_date as occurred_at',
            'a.module',
            'a.action',
        ];

        foreach (self::NAME_COLUMNS as $column) {
            if ($hasUsers && $this->hasColumn($userColumns, $column)) {
                $select[] = "u.{$column}";
            }
        }

        if ($hasUsers && $this->hasColumn($userColumns, 'FIELD_OFFICE')) {
            $select[] = 'u.FIELD_OFFICE';
        }

        if ($joinedLevel) {
            $select[] = 'l.USER_LEVEL_NAME';
        }

        $limit = max(1, (int) config('cms.audit_trail.sync_limit'));
        $rows = $query
            ->select($select)
            ->orderByDesc('a.audit_trail_id')
            ->limit($limit)
            ->get();

        $records = [];

        foreach ($rows as $row) {
            $externalId = (int) ($row->external_id ?? 0);

            if ($externalId < 1 || isset($records[$externalId])) {
                continue;
            }

            $records[$externalId] = [
                'external_id' => $externalId,
                'occurred_at' => $this->text($row, 'occurred_at', 32),
                'actor_name' => $this->actorName($row),
                'user_level' => $this->text($row, 'USER_LEVEL_NAME', 255),
                'field_office' => $this->text($row, 'FIELD_OFFICE', 255),
                'module' => $this->text($row, 'module', 255),
                'action' => $this->text($row, 'action', 2000),
            ];
        }

        return array_values($records);
    }

    private function readOnly(string $connection): void
    {
        $database = DB::connection($connection);
        $timeout = max(1, (int) config('cms.audit_trail.statement_timeout_ms'));

        if ($database->getDriverName() === 'mysql') {
            $database->statement('SET SESSION TRANSACTION READ ONLY');
            $database->statement("SET SESSION MAX_EXECUTION_TIME={$timeout}");

            return;
        }

        if ($database->getDriverName() === 'sqlite') {
            $database->statement('PRAGMA query_only = 1');
        }
    }

    /**
     * @param  list<string>  $columns
     */
    private function hasColumn(array $columns, string $name): bool
    {
        foreach ($columns as $column) {
            if (strcasecmp($column, $name) === 0) {
                return true;
            }
        }

        return false;
    }

    private function actorName(object $row): ?string
    {
        $fullName = $this->text($row, 'USER_FULLNAME', 255);

        if ($fullName !== null) {
            return $fullName;
        }

        $parts = array_values(array_filter([
            $this->text($row, 'USER_FNAME', 255),
            $this->text($row, 'USER_MNAME', 255),
            $this->text($row, 'USER_LNAME', 255),
        ]));

        if ($parts !== []) {
            return $this->limit(implode(' ', $parts), 255);
        }

        return $this->text($row, 'USER_EMAIL', 255);
    }

    private function text(object $row, string $column, int $limit): ?string
    {
        $value = $row->{$column} ?? $row->{strtolower($column)} ?? null;

        if (! is_scalar($value)) {
            return null;
        }

        return $this->limit(trim((string) $value), $limit);
    }

    private function limit(string $value, int $limit): ?string
    {
        if ($value === '') {
            return null;
        }

        return mb_substr($value, 0, $limit);
    }
}

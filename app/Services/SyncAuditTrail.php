<?php

namespace App\Services;

use App\Contracts\AuditTrailReader;
use App\Enums\AuditSource;
use App\Models\AuditRecord;
use App\Models\AuditSourceStatus;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use Throwable;

class SyncAuditTrail
{
    public function __construct(private AuditTrailReader $reader) {}

    /**
     * Copies recent rows into CMS-PPA. A source that cannot be read keeps the copy already saved.
     *
     * @return list<array{source: string, label: string, available: bool, saved: int, last_error: string|null, last_synced_at: string|null}>
     */
    public function sync(): array
    {
        $results = [];

        foreach (AuditSource::cases() as $source) {
            $snapshot = $this->reader->read($source);
            $status = AuditSourceStatus::query()->firstOrNew(['source' => $source]);
            $saved = 0;

            if ($snapshot->available) {
                try {
                    $saved = DB::transaction(fn (): int => $this->store($source, $snapshot->records));
                    $status->fill([
                        'available' => true,
                        'last_error' => null,
                        'last_synced_at' => now(),
                    ]);
                } catch (Throwable $exception) {
                    report($exception);
                    $status->fill([
                        'available' => false,
                        'last_error' => "{$source->label()} could not be saved. Records already saved are unchanged.",
                    ]);
                }
            } else {
                $status->fill([
                    'available' => false,
                    'last_error' => $snapshot->error,
                ]);
            }

            $status->save();

            $results[] = [
                'source' => $source->value,
                'label' => $source->label(),
                'available' => (bool) $status->available,
                'saved' => $saved,
                'last_error' => $status->last_error,
                'last_synced_at' => $status->last_synced_at?->toIso8601String(),
            ];
        }

        return $results;
    }

    /**
     * @param  list<array{
     *     external_id: int,
     *     occurred_at: string|null,
     *     actor_name: string|null,
     *     user_level: string|null,
     *     field_office: string|null,
     *     module: string|null,
     *     action: string|null
     * }>  $records
     */
    private function store(AuditSource $source, array $records): int
    {
        if ($records === []) {
            return 0;
        }

        $now = now();
        $rows = [];

        foreach ($records as $record) {
            $externalId = (int) $record['external_id'];

            if ($externalId < 1) {
                continue;
            }

            $rows[$externalId] = [
                'source' => $source->value,
                'external_id' => $externalId,
                'occurred_at' => $this->occurredAt($record['occurred_at']),
                'actor_name' => $record['actor_name'],
                'user_level' => $record['user_level'],
                'field_office' => $record['field_office'],
                'module' => $record['module'],
                'action' => $record['action'],
                'created_at' => $now,
                'updated_at' => $now,
            ];
        }

        if ($rows === []) {
            return 0;
        }

        AuditRecord::query()->upsert(
            array_values($rows),
            ['source', 'external_id'],
            ['occurred_at', 'actor_name', 'user_level', 'field_office', 'module', 'action', 'updated_at'],
        );

        return count($rows);
    }

    private function occurredAt(?string $value): ?string
    {
        if ($value === null || trim($value) === '') {
            return null;
        }

        try {
            return Carbon::parse($value, (string) config('cms.display_timezone'))->utc()->format('Y-m-d H:i:s');
        } catch (Throwable) {
            return null;
        }
    }
}

<?php

namespace App\Http\Controllers;

use App\Enums\AuditSource;
use App\Http\Resources\AuditRecordResource;
use App\Models\AuditRecord;
use App\Models\AuditSourceStatus;
use App\Services\SyncAuditTrail;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Validation\Rule;

class AuditTrailController extends Controller
{
    public function index(Request $request): JsonResponse
    {
        return $this->listing($request);
    }

    /**
     * Reads the source systems once, then returns the saved copy. The list itself never contacts them.
     */
    public function refresh(Request $request, SyncAuditTrail $sync): JsonResponse
    {
        $this->filters($request);
        $sync->sync();

        $payload = $this->listing($request)->getData(true);
        $payload['message'] = $this->refreshMessage($payload['sources'] ?? []);

        return response()->json($payload);
    }

    /**
     * @return array{search?: string, source?: string, from?: string, to?: string, page?: int}
     */
    private function filters(Request $request): array
    {
        $sources = array_column(AuditSource::cases(), 'value');

        return $request->validate([
            'search' => ['nullable', 'string', 'max:100'],
            'source' => ['nullable', Rule::in(['all', ...$sources])],
            'from' => ['nullable', 'date'],
            'to' => ['nullable', 'date', 'after_or_equal:from'],
            'page' => ['nullable', 'integer', 'min:1'],
        ]);
    }

    private function listing(Request $request): JsonResponse
    {
        $sources = array_column(AuditSource::cases(), 'value');
        $filters = $this->filters($request);

        $search = trim($filters['search'] ?? '');
        $source = $filters['source'] ?? 'all';
        $timezone = (string) config('cms.display_timezone');
        $from = isset($filters['from']) ? Carbon::parse($filters['from'], $timezone)->startOfDay()->utc() : null;
        $to = isset($filters['to']) ? Carbon::parse($filters['to'], $timezone)->endOfDay()->utc() : null;
        $like = '%'.addcslashes($search, '\\%_').'%';

        $matching = fn (): Builder => AuditRecord::query()
            ->when($search !== '', function (Builder $query) use ($like): void {
                $query->where(function (Builder $query) use ($like): void {
                    foreach (AuditRecord::SEARCHABLE as $column) {
                        $query->orWhereRaw($column.' like ? escape ?', [$like, '\\']);
                    }
                });
            })
            ->when($from !== null, fn (Builder $query) => $query->where('occurred_at', '>=', $from))
            ->when($to !== null, fn (Builder $query) => $query->where('occurred_at', '<=', $to));

        $page = $matching()
            ->when($source !== 'all', fn (Builder $query) => $query->where('source', $source))
            ->orderByRaw('occurred_at is null')
            ->orderByDesc('occurred_at')
            ->orderByDesc('external_id')
            ->orderByDesc('id')
            ->paginate(max(1, (int) config('cms.audit_trail.per_page')));

        $counts = ['all' => $matching()->count()];

        foreach ($sources as $value) {
            $counts[$value] = $matching()->where('source', $value)->count();
        }

        return response()->json([
            'data' => AuditRecordResource::collection($page->getCollection())->resolve($request),
            'meta' => [
                'current_page' => $page->currentPage(),
                'last_page' => $page->lastPage(),
                'per_page' => $page->perPage(),
                'total' => $page->total(),
                'from' => $page->firstItem(),
                'to' => $page->lastItem(),
                'total_saved' => AuditRecord::query()->count(),
                'timezone' => $timezone,
                'reads' => 'saved_records',
                'generated_at' => now()->toIso8601String(),
            ],
            'counts' => $counts,
            'sources' => $this->sources(),
        ]);
    }

    /**
     * @return list<array{source: string, label: string, available: bool|null, last_synced_at: string|null, last_error: string|null, saved: int}>
     */
    private function sources(): array
    {
        $statuses = AuditSourceStatus::query()->get()->keyBy(fn (AuditSourceStatus $status): string => $status->source->value);
        $saved = AuditRecord::query()->selectRaw('source, count(*) as saved')->groupBy('source')->pluck('saved', 'source');
        $sources = [];

        foreach (AuditSource::cases() as $source) {
            $status = $statuses->get($source->value);

            $sources[] = [
                'source' => $source->value,
                'label' => $source->label(),
                'available' => $status?->available,
                'last_synced_at' => $status?->last_synced_at?->toIso8601String(),
                'last_error' => $status?->last_error,
                'saved' => (int) ($saved[$source->value] ?? 0),
            ];
        }

        return $sources;
    }

    /**
     * @param  list<array<string, mixed>>  $sources
     */
    private function refreshMessage(array $sources): string
    {
        $problems = [];

        foreach ($sources as $source) {
            $error = $source['last_error'] ?? null;

            if (is_string($error) && $error !== '') {
                $problems[] = $error;
            }
        }

        if ($problems === []) {
            return 'Audit records refreshed.';
        }

        return 'Audit records refreshed with problems: '.implode(' ', $problems);
    }
}

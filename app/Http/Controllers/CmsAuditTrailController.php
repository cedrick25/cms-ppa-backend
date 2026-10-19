<?php

namespace App\Http\Controllers;

use App\Enums\CmsAuditModule;
use App\Http\Resources\CmsAuditEventResource;
use App\Models\CmsAuditEvent;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Validation\Rule;

class CmsAuditTrailController extends Controller
{
    public function index(Request $request): JsonResponse
    {
        $modules = array_column(CmsAuditModule::cases(), 'value');
        $filters = $request->validate([
            'search' => ['nullable', 'string', 'max:100'],
            'module' => ['nullable', Rule::in(['all', ...$modules])],
            'from' => ['nullable', 'date'],
            'to' => ['nullable', 'date', 'after_or_equal:from'],
            'page' => ['nullable', 'integer', 'min:1'],
        ]);

        $search = trim($filters['search'] ?? '');
        $module = $filters['module'] ?? 'all';
        $timezone = (string) config('cms.display_timezone');
        $from = isset($filters['from']) ? Carbon::parse($filters['from'], $timezone)->startOfDay()->utc() : null;
        $to = isset($filters['to']) ? Carbon::parse($filters['to'], $timezone)->endOfDay()->utc() : null;
        $like = '%'.addcslashes($search, '\\%_').'%';

        $matching = fn (): Builder => CmsAuditEvent::query()
            ->when($search !== '', function (Builder $query) use ($like): void {
                $query->where(function (Builder $query) use ($like): void {
                    foreach (CmsAuditEvent::SEARCHABLE as $column) {
                        $query->orWhereRaw($column.' like ? escape ?', [$like, '\\']);
                    }
                });
            })
            ->when($from !== null, fn (Builder $query) => $query->where('occurred_at', '>=', $from))
            ->when($to !== null, fn (Builder $query) => $query->where('occurred_at', '<=', $to));

        $page = $matching()
            ->when($module !== 'all', fn (Builder $query) => $query->where('module', $module))
            ->orderByDesc('occurred_at')
            ->orderByDesc('id')
            ->paginate(max(1, (int) config('cms.cms_audit_trail.per_page')));

        $counts = ['all' => $matching()->count()];

        foreach ($modules as $value) {
            $counts[$value] = $matching()->where('module', $value)->count();
        }

        return response()->json([
            'data' => CmsAuditEventResource::collection($page->getCollection())->resolve($request),
            'meta' => [
                'current_page' => $page->currentPage(),
                'last_page' => $page->lastPage(),
                'per_page' => $page->perPage(),
                'total' => $page->total(),
                'from' => $page->firstItem(),
                'to' => $page->lastItem(),
                'total_recorded' => CmsAuditEvent::query()->count(),
                'timezone' => $timezone,
                'generated_at' => now()->toIso8601String(),
            ],
            'counts' => $counts,
        ]);
    }
}

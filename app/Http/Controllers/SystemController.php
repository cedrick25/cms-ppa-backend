<?php

namespace App\Http\Controllers;

use App\Enums\CmsAuditAction;
use App\Enums\SystemEnvironment;
use App\Http\Requests\StoreSystemRequest;
use App\Http\Requests\UpdateSystemRequest;
use App\Http\Resources\SystemDetailResource;
use App\Http\Resources\SystemResource;
use App\Models\CmsAuditEvent;
use App\Models\System;
use App\Models\User;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;
use Illuminate\Validation\Rule;
use Illuminate\Validation\ValidationException;

class SystemController extends Controller
{
    private const PER_PAGE = 20;

    public function index(Request $request): JsonResponse
    {
        $environments = array_column(SystemEnvironment::cases(), 'value');

        $filters = $request->validate([
            'search' => ['nullable', 'string', 'max:100'],
            'environment' => ['nullable', Rule::in(['all', ...$environments])],
            'page' => ['nullable', 'integer', 'min:1'],
        ]);

        $search = trim($filters['search'] ?? '');
        $environment = $filters['environment'] ?? 'all';

        $matching = fn (): Builder => System::query()->when($search !== '', function (Builder $query) use ($search) {
            $query->where(function (Builder $query) use ($search) {
                foreach (System::SEARCHABLE as $column) {
                    $query->orWhere($column, 'like', "%{$search}%");
                }
            });
        });

        $page = $matching()
            ->when($environment !== 'all', fn (Builder $query) => $query->where('environment', $environment))
            ->orderBy('name')
            ->orderBy('id')
            ->paginate(self::PER_PAGE);

        $counts = ['all' => $matching()->count()];

        foreach ($environments as $value) {
            $counts[$value] = $matching()->where('environment', $value)->count();
        }

        return response()->json([
            'data' => SystemResource::collection($page->getCollection())->resolve($request),
            'meta' => [
                'current_page' => $page->currentPage(),
                'last_page' => $page->lastPage(),
                'per_page' => $page->perPage(),
                'total' => $page->total(),
                'from' => $page->firstItem(),
                'to' => $page->lastItem(),
                'total_registered' => System::query()->count(),
            ],
            'counts' => $counts,
        ]);
    }

    public function show(Request $request, System $system): JsonResponse
    {
        $this->audit('System credentials viewed.', $request, $system);

        return response()->json([
            'system' => (new SystemDetailResource($system))->resolve($request),
        ]);
    }

    public function store(StoreSystemRequest $request): JsonResponse
    {
        $system = System::query()->create($request->validated());

        $this->audit('System credentials registered.', $request, $system);
        $this->recordCmsAudit($request, CmsAuditAction::CredentialsRegistered, "Registered credentials for {$system->name}.", $system);

        return $this->systemResponse($request, $system, "System registered: {$system->name}.", 201);
    }

    public function update(UpdateSystemRequest $request, System $system): JsonResponse
    {
        $system->fill($request->validated());
        $changed = array_keys($system->getDirty());
        $system->save();

        if ($changed !== []) {
            $this->audit('System credentials updated.', $request, $system, ['changed' => $changed]);
            $this->recordCmsAudit($request, CmsAuditAction::CredentialsUpdated, "Updated credentials for {$system->name}.", $system, ['changed' => $changed]);
        }

        return $this->systemResponse($request, $system, $changed === []
            ? "No changes were needed for {$system->name}."
            : "Changes saved for {$system->name}.");
    }

    public function destroy(Request $request, System $system): JsonResponse
    {
        $system->delete();

        $this->audit('System credentials removed.', $request, $system);
        $this->recordCmsAudit($request, CmsAuditAction::CredentialsRemoved, "Removed credentials for {$system->name}.", $system);

        return response()->json([
            'message' => "System removed: {$system->name}.",
        ]);
    }

    /**
     * Records that someone opened a remote desk. The address and password are not stored.
     */
    public function openRemoteDesk(Request $request, System $system): JsonResponse
    {
        $type = $request->validate([
            'type' => ['required', 'string', Rule::in(['anydesk', 'rustdesk', 'rdp', 'ssh'])],
        ], [
            'type.required' => 'Choose a remote desk to open.',
            'type.in' => 'Choose AnyDesk, RustDesk, RDP, or SSH.',
        ])['type'];

        $entry = collect($system->remoteAccess())->firstWhere('type', $type);

        if (! is_array($entry)) {
            $label = match ($type) {
                'anydesk' => 'AnyDesk',
                'rustdesk' => 'RustDesk',
                'rdp' => 'RDP',
                'ssh' => 'SSH',
                default => 'remote',
            };

            throw ValidationException::withMessages([
                'type' => "This system has no {$label} remote desk.",
            ]);
        }

        $desk = (string) $entry['label'];
        $this->recordCmsAudit(
            $request,
            CmsAuditAction::RemoteDeskOpened,
            "Opened the {$desk} remote desk for {$system->name}.",
            $system,
            ['remote_desk' => $desk],
        );

        return response()->json([
            'message' => "Opened the {$desk} remote desk for {$system->name}.",
        ]);
    }

    /**
     * Only field names are logged, never credential values.
     *
     * @param  array<string, mixed>  $context
     */
    private function audit(string $event, Request $request, System $system, array $context = []): void
    {
        Log::info($event, [
            'system_id' => $system->id,
            'system_name' => $system->name,
            'performed_by' => $request->user()?->username,
        ] + $context);
    }

    /**
     * @param  array<string, mixed>  $details
     */
    private function recordCmsAudit(Request $request, CmsAuditAction $action, string $summary, System $system, array $details = []): void
    {
        $actor = $request->user();

        if (! $actor instanceof User) {
            return;
        }

        CmsAuditEvent::record($actor, $action, $summary, $system->name, $system, $details);
    }

    private function systemResponse(Request $request, System $system, string $message, int $status = 200): JsonResponse
    {
        return response()->json([
            'message' => $message,
            'system' => (new SystemDetailResource($system))->resolve($request),
        ], $status);
    }
}

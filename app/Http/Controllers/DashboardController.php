<?php

namespace App\Http\Controllers;

use App\Enums\CmsAuditAction;
use App\Enums\SystemEnvironment;
use App\Http\Resources\DashboardSystemResource;
use App\Http\Resources\SignInEventResource;
use App\Models\CmsAuditEvent;
use App\Models\SignInEvent;
use App\Models\System;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Validation\Rule;

/**
 * Read-only dashboard widgets. These actions only read saved data, so a slow or unreachable
 * monitored system can never delay or fail these responses.
 */
class DashboardController extends Controller
{
    /**
     * Charts of activity inside CMS-PPA. Sign-ins stay on the sign-in list.
     *
     * @var array<string, array{label: string, actions: list<CmsAuditAction>}>
     */
    private const ACTIVITY_SERIES = [
        'remote_desks' => [
            'label' => 'Remote desks opened',
            'actions' => [CmsAuditAction::RemoteDeskOpened],
        ],
        'credential_changes' => [
            'label' => 'Credential changes',
            'actions' => [
                CmsAuditAction::CredentialsRegistered,
                CmsAuditAction::CredentialsUpdated,
                CmsAuditAction::CredentialsRemoved,
            ],
        ],
        'account_changes' => [
            'label' => 'Account changes',
            'actions' => [
                CmsAuditAction::UserRegistered,
                CmsAuditAction::UserUpdated,
                CmsAuditAction::UserDisabled,
                CmsAuditAction::UserRemoved,
            ],
        ],
    ];

    /**
     * @var array<string, string>
     */
    private const ACTIVITY_RANGES = [
        'current_week' => 'This week',
        'last_week' => 'Last week',
        'last_30_days' => 'Last 30 days',
    ];

    public function systems(Request $request): JsonResponse
    {
        $systems = System::query()
            ->where('environment', SystemEnvironment::Production)
            ->with('statusCheck')
            ->orderBy('name')
            ->orderBy('id')
            ->get();

        return response()->json([
            'data' => DashboardSystemResource::collection($systems)->resolve($request),
            'meta' => [
                'total' => $systems->count(),
                'check_interval_minutes' => (int) config('cms.system_status.interval_minutes'),
                'stale_after_minutes' => (int) config('cms.system_status.stale_after_minutes'),
                'timezone' => config('cms.display_timezone'),
                'generated_at' => now()->toIso8601String(),
            ],
        ]);
    }

    public function signIns(Request $request): JsonResponse
    {
        $limit = max(1, (int) config('cms.sign_ins.dashboard_limit'));

        $events = SignInEvent::query()
            ->with('user:id,name')
            ->latest('signed_in_at')
            ->latest('id')
            ->limit($limit)
            ->get();

        return response()->json([
            'data' => SignInEventResource::collection($events)->resolve($request),
            'meta' => [
                'shows' => 'recent_sign_ins',
                'limit' => $limit,
                'timezone' => config('cms.display_timezone'),
                'generated_at' => now()->toIso8601String(),
            ],
        ]);
    }

    public function activity(Request $request): JsonResponse
    {
        $filters = $request->validate([
            'series' => ['required', Rule::in(array_keys(self::ACTIVITY_SERIES))],
            'range' => ['sometimes', Rule::in(array_keys(self::ACTIVITY_RANGES))],
        ]);

        $series = $filters['series'];
        $range = $filters['range'] ?? 'current_week';
        $definition = self::ACTIVITY_SERIES[$series];
        $timezone = (string) config('cms.display_timezone');
        [$start, $end] = $this->activityWindow($range, $timezone);

        $counts = [];
        $cursor = $start->copy()->startOfDay();
        $lastDay = $end->copy()->startOfDay();

        while ($cursor->lte($lastDay)) {
            $counts[$cursor->toDateString()] = 0;
            $cursor->addDay();
        }

        $actions = array_map(
            fn (CmsAuditAction $action): string => $action->value,
            $definition['actions'],
        );

        CmsAuditEvent::query()
            ->whereIn('action', $actions)
            ->whereBetween('occurred_at', [$start->copy()->utc(), $end->copy()->utc()])
            ->get(['occurred_at'])
            ->each(function (CmsAuditEvent $event) use (&$counts, $timezone): void {
                $day = $event->occurred_at->timezone($timezone)->toDateString();

                if (array_key_exists($day, $counts)) {
                    $counts[$day]++;
                }
            });

        $points = [];

        foreach ($counts as $date => $count) {
            $points[] = [
                'date' => $date,
                'label' => Carbon::parse($date, $timezone)->format('M j'),
                'count' => $count,
            ];
        }

        return response()->json([
            'data' => $points,
            'meta' => [
                'series' => $series,
                'series_label' => $definition['label'],
                'range' => $range,
                'range_label' => self::ACTIVITY_RANGES[$range],
                'from' => $start->toDateString(),
                'to' => $end->toDateString(),
                'total' => array_sum($counts),
                'timezone' => $timezone,
                'generated_at' => now()->toIso8601String(),
            ],
        ]);
    }

    /**
     * Monday-start weeks in the display timezone. The current week stops at the end of today.
     *
     * @return array{0: Carbon, 1: Carbon}
     */
    private function activityWindow(string $range, string $timezone): array
    {
        $now = now($timezone);
        $startOfThisWeek = $now->copy()->startOfWeek(Carbon::MONDAY);

        $start = match ($range) {
            'last_week' => $startOfThisWeek->copy()->subWeek(),
            'last_30_days' => $now->copy()->startOfDay()->subDays(29),
            default => $startOfThisWeek->copy(),
        };

        $end = $range === 'last_week'
            ? $start->copy()->endOfWeek(Carbon::SUNDAY)
            : $now->copy()->endOfDay();

        return [$start, $end];
    }
}

<?php

namespace App\Http\Controllers;

use App\Enums\SystemEnvironment;
use App\Http\Resources\DashboardSystemResource;
use App\Http\Resources\SignInEventResource;
use App\Models\SignInEvent;
use App\Models\System;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * Read-only dashboard widgets. Both actions only read saved data, so a slow or unreachable
 * monitored system can never delay or fail these responses.
 */
class DashboardController extends Controller
{
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
}

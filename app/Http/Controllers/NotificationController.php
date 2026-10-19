<?php

namespace App\Http\Controllers;

use App\Http\Resources\NotificationResource;
use App\Models\User;
use App\Notifications\SystemStatusChanged;
use Illuminate\Database\Eloquent\Relations\MorphMany;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Notifications\DatabaseNotification;

/**
 * The signed-in account's website status notifications, newest first. Another account's notification is reported as not found.
 */
class NotificationController extends Controller
{
    /**
     * The dashboard polls this every few seconds. A single summary query decides whether anything changed;
     * if the dashboard already has the current list (its If-None-Match matches), it gets an empty 304.
     */
    public function index(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $request->user();
        $limit = max(1, (int) config('cms.notifications.dashboard_limit'));
        $timezone = config('cms.display_timezone');

        $summary = $this->websiteStatusNotifications($user)->reorder()->toBase()
            ->selectRaw('count(*) as total, coalesce(sum(case when read_at is null then 1 else 0 end), 0) as unread, max(created_at) as latest_created_at, max(read_at) as latest_read_at')
            ->first();

        $response = new JsonResponse;
        $response->setEtag(hash('xxh128', implode('|', [
            $user->id, $limit, $timezone, $summary->total, $summary->unread, $summary->latest_created_at, $summary->latest_read_at,
        ])));

        if ($response->isNotModified($request)) {
            return $response;
        }

        $notifications = $this->websiteStatusNotifications($user)->limit($limit)->get();

        return $response->setData([
            'data' => NotificationResource::collection($notifications)->resolve($request),
            'meta' => [
                'unread_count' => (int) $summary->unread,
                'limit' => $limit,
                'timezone' => $timezone,
                'generated_at' => now()->toIso8601String(),
            ],
        ]);
    }

    public function markAsRead(Request $request, string $notification): JsonResponse
    {
        /** @var User $user */
        $user = $request->user();
        $record = $this->websiteStatusNotifications($user)->whereKey($notification)->firstOrFail();

        $record->markAsRead();

        return response()->json([
            'notification' => (new NotificationResource($record))->resolve($request),
            'meta' => ['unread_count' => $this->websiteStatusNotifications($user)->whereNull('read_at')->count()],
        ]);
    }

    public function markAllAsRead(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $request->user();
        $marked = $this->websiteStatusNotifications($user)->whereNull('read_at')->update(['read_at' => now()]);

        return response()->json([
            'message' => $marked === 1 ? '1 notification marked as read.' : "{$marked} notifications marked as read.",
            'meta' => ['unread_count' => 0],
        ]);
    }

    /**
     * The dashboard only reports websites going down or coming back up.
     *
     * @return MorphMany<DatabaseNotification, User>
     */
    private function websiteStatusNotifications(User $user): MorphMany
    {
        return $user->notifications()->where('type', SystemStatusChanged::class);
    }
}

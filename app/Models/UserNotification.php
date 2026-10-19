<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\MassPrunable;
use Illuminate\Notifications\DatabaseNotification;

/**
 * A dashboard notification stored for one account. Read notifications are
 * kept for a shorter period than unread ones; both are removed daily by
 * `php artisan model:prune`.
 */
class UserNotification extends DatabaseNotification
{
    use MassPrunable;

    /**
     * @return Builder<static>
     */
    public function prunable(): Builder
    {
        return static::query()
            ->where('read_at', '<', now()->subDays((int) config('cms.notifications.read_retention_days')))
            ->orWhere('created_at', '<', now()->subDays((int) config('cms.notifications.retention_days')));
    }
}

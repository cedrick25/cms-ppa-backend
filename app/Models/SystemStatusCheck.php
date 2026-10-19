<?php

namespace App\Models;

use App\Enums\SystemStatus;
use Database\Factories\SystemStatusCheckFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

#[Fillable([
    'status', 'reason', 'http_status', 'response_time_ms',
    'consecutive_failures', 'checked_at', 'next_check_at',
])]
class SystemStatusCheck extends Model
{
    /** @use HasFactory<SystemStatusCheckFactory> */
    use HasFactory;

    /**
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'status' => SystemStatus::class,
            'checked_at' => 'datetime',
            'next_check_at' => 'datetime',
        ];
    }

    /**
     * @return BelongsTo<System, $this>
     */
    public function system(): BelongsTo
    {
        return $this->belongsTo(System::class);
    }

    /**
     * True when the scheduled re-check is overdue, which means the checker isn't running and this result may be out of date.
     */
    public function isStale(): bool
    {
        $grace = (int) config('cms.system_status.stale_after_minutes');

        return $this->next_check_at->copy()->addMinutes($grace)->isPast();
    }
}

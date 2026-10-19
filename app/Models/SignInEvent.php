<?php

namespace App\Models;

use Database\Factories\SignInEventFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\MassPrunable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * One successful sign-in to CMS-PPA. This is history, not a list of active sessions.
 */
#[Fillable(['signed_in_at'])]
class SignInEvent extends Model
{
    /** @use HasFactory<SignInEventFactory> */
    use HasFactory, MassPrunable;

    public $timestamps = false;

    /**
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'signed_in_at' => 'datetime',
        ];
    }

    /**
     * @return BelongsTo<User, $this>
     */
    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /**
     * @return Builder<static>
     */
    public function prunable(): Builder
    {
        return static::query()->where('signed_in_at', '<', now()->subDays((int) config('cms.sign_ins.retention_days')));
    }
}

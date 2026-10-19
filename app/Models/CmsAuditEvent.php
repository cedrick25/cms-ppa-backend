<?php

namespace App\Models;

use App\Enums\CmsAuditAction;
use App\Enums\CmsAuditModule;
use Database\Factories\CmsAuditEventFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;

/**
 * One thing a person did inside CMS-PPA. The name is kept even if the account is later removed.
 */
#[Fillable([
    'occurred_at',
    'actor_id',
    'actor_name',
    'actor_username',
    'module',
    'action',
    'subject_type',
    'subject_id',
    'subject_label',
    'summary',
    'details',
])]
class CmsAuditEvent extends Model
{
    /** @use HasFactory<CmsAuditEventFactory> */
    use HasFactory;

    /**
     * Columns an administrator can search. Values are bound; this list is not taken from the request.
     */
    public const SEARCHABLE = ['actor_name', 'actor_username', 'subject_label', 'summary'];

    /**
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'occurred_at' => 'datetime',
            'module' => CmsAuditModule::class,
            'action' => CmsAuditAction::class,
            'details' => 'array',
        ];
    }

    protected static function booted(): void
    {
        static::saving(function (CmsAuditEvent $event): void {
            $event->module = $event->action->module();
        });
    }

    /**
     * @param  array<string, mixed>  $details
     */
    public static function record(
        User $actor,
        CmsAuditAction $action,
        string $summary,
        ?string $subjectLabel = null,
        ?Model $subject = null,
        array $details = [],
    ): self {
        $subjectType = self::subjectType($subject);

        return static::query()->create([
            'occurred_at' => now(),
            'actor_id' => $actor->id,
            'actor_name' => $actor->name,
            'actor_username' => $actor->username,
            'action' => $action,
            'subject_type' => $subjectType,
            'subject_id' => $subjectType === null ? null : $subject?->getKey(),
            'subject_label' => $subjectLabel,
            'summary' => $summary,
            'details' => self::safeDetails($details),
        ]);
    }

    private static function subjectType(?Model $subject): ?string
    {
        return match (true) {
            $subject instanceof System => 'system',
            $subject instanceof User => 'user',
            default => null,
        };
    }

    /**
     * Field names and the remote-desk kind only. Values, passwords, and addresses are dropped.
     *
     * @param  array<string, mixed>  $details
     * @return array<string, mixed>|null
     */
    private static function safeDetails(array $details): ?array
    {
        $safe = [];

        $remoteDesk = $details['remote_desk'] ?? null;

        if (is_string($remoteDesk) && $remoteDesk !== '') {
            $safe['remote_desk'] = $remoteDesk;
        }

        $changed = $details['changed'] ?? null;

        if (is_array($changed)) {
            $fields = array_values(array_filter(
                $changed,
                fn (mixed $field): bool => is_string($field) && $field !== '',
            ));

            if ($fields !== []) {
                $safe['changed'] = $fields;
            }
        }

        return $safe === [] ? null : $safe;
    }
}

<?php

namespace App\Http\Resources;

use App\Models\CmsAuditEvent;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * One CMS activity row. Passwords, remote identifiers, and network addresses are not part of this response.
 *
 * @mixin CmsAuditEvent
 */
class CmsAuditEventResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'occurred_at' => $this->occurred_at?->toIso8601String(),
            'actor_name' => $this->actor_name,
            'actor_username' => $this->actor_username,
            'module' => $this->module->value,
            'module_label' => $this->module->label(),
            'action' => $this->action->value,
            'action_label' => $this->action->label(),
            'subject_label' => $this->subject_label,
            'summary' => $this->summary,
            'remote_desk' => $this->remoteDesk(),
            'changed' => $this->changedFields(),
        ];
    }

    private function remoteDesk(): ?string
    {
        $remoteDesk = $this->details['remote_desk'] ?? null;

        return is_string($remoteDesk) && $remoteDesk !== '' ? $remoteDesk : null;
    }

    /**
     * @return list<string>
     */
    private function changedFields(): array
    {
        $changed = $this->details['changed'] ?? null;

        if (! is_array($changed)) {
            return [];
        }

        return array_values(array_filter(
            $changed,
            fn (mixed $field): bool => is_string($field) && $field !== '',
        ));
    }
}

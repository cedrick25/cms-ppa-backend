<?php

namespace App\Http\Resources;

use App\Models\AuditRecord;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * One saved audit row. Network addresses from the source system are not part of this copy.
 *
 * @mixin AuditRecord
 */
class AuditRecordResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'source' => $this->source->value,
            'source_label' => $this->source->label(),
            'external_id' => $this->external_id,
            'occurred_at' => $this->occurred_at?->toIso8601String(),
            'actor_name' => $this->actor_name,
            'user_level' => $this->user_level,
            'field_office' => $this->field_office,
            'module' => $this->module,
            'action' => $this->action,
        ];
    }
}

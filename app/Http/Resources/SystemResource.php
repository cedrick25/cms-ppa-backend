<?php

namespace App\Http\Resources;

use App\Models\System;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * The list view of a system. Never includes passwords or notes.
 *
 * @mixin System
 */
class SystemResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'description' => $this->description,
            'environment' => $this->environment,
            'remote_access' => $this->remoteAccess(),
            'local_ip' => $this->local_ip,
            'mysql_host' => $this->mysql_host,
            'domain' => $this->domain,
            'vm_host' => $this->vm_host,
            'created_at' => $this->created_at?->toIso8601String(),
            'updated_at' => $this->updated_at?->toIso8601String(),
        ];
    }
}

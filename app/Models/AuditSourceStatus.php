<?php

namespace App\Models;

use App\Enums\AuditSource;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;

#[Fillable(['source', 'available', 'last_synced_at', 'last_error'])]
class AuditSourceStatus extends Model
{
    /**
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'source' => AuditSource::class,
            'available' => 'boolean',
            'last_synced_at' => 'datetime',
        ];
    }
}

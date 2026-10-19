<?php

namespace App\Models;

use App\Enums\AuditSource;
use Database\Factories\AuditRecordFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;

#[Fillable([
    'source', 'external_id', 'occurred_at', 'actor_name', 'user_level', 'field_office', 'module', 'action',
])]
class AuditRecord extends Model
{
    /** @use HasFactory<AuditRecordFactory> */
    use HasFactory;

    /**
     * Columns an administrator can search. Values are bound; this list is not taken from the request.
     */
    public const SEARCHABLE = ['actor_name', 'user_level', 'field_office', 'module', 'action'];

    /**
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'source' => AuditSource::class,
            'external_id' => 'integer',
            'occurred_at' => 'datetime',
        ];
    }
}

<?php

namespace App\Services;

/**
 * Rows read from one PPA system. An unavailable snapshot never replaces records already saved in CMS-PPA.
 */
readonly class AuditSnapshot
{
    /**
     * @param  list<array{
     *     external_id: int,
     *     occurred_at: string|null,
     *     actor_name: string|null,
     *     user_level: string|null,
     *     field_office: string|null,
     *     module: string|null,
     *     action: string|null
     * }>  $records
     */
    public function __construct(
        public bool $available,
        public ?string $error,
        public array $records,
    ) {}

    public static function unavailable(string $error): self
    {
        return new self(false, $error, []);
    }
}

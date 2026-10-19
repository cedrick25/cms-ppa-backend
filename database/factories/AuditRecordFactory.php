<?php

namespace Database\Factories;

use App\Enums\AuditSource;
use App\Models\AuditRecord;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<AuditRecord>
 */
class AuditRecordFactory extends Factory
{
    /**
     * @return array<string, mixed>
     */
    public function definition(): array
    {
        return [
            'source' => AuditSource::Uams,
            'external_id' => fake()->unique()->numberBetween(1, 1_000_000_000),
            'occurred_at' => now(),
            'actor_name' => fake()->name(),
            'user_level' => 'Administrator',
            'field_office' => 'Manila',
            'module' => 'Users',
            'action' => 'Updated a record',
        ];
    }

    public function fromSource(AuditSource $source): static
    {
        return $this->state(fn (array $attributes): array => [
            'source' => $source,
        ]);
    }
}

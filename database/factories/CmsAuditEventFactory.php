<?php

namespace Database\Factories;

use App\Enums\CmsAuditAction;
use App\Models\CmsAuditEvent;
use App\Models\User;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<CmsAuditEvent>
 */
class CmsAuditEventFactory extends Factory
{
    /**
     * @return array<string, mixed>
     */
    public function definition(): array
    {
        return [
            'occurred_at' => now(),
            'actor_id' => User::factory(),
            'actor_name' => fake()->name(),
            'actor_username' => fake()->unique()->userName(),
            'action' => CmsAuditAction::SignedIn,
            'subject_type' => null,
            'subject_id' => null,
            'subject_label' => null,
            'summary' => 'Signed in.',
            'details' => null,
        ];
    }
}

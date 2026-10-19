<?php

namespace Database\Factories;

use App\Enums\SystemStatus;
use App\Models\System;
use App\Models\SystemStatusCheck;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<SystemStatusCheck>
 */
class SystemStatusCheckFactory extends Factory
{
    /**
     * Define the model's default state: a fresh successful check.
     *
     * @return array<string, mixed>
     */
    public function definition(): array
    {
        return [
            'system_id' => System::factory(),
            'status' => SystemStatus::Up,
            'reason' => 'Responded with HTTP 200 over HTTPS.',
            'http_status' => 200,
            'response_time_ms' => fake()->numberBetween(40, 900),
            'consecutive_failures' => 0,
            'checked_at' => now(),
            'next_check_at' => now()->addMinutes(5),
        ];
    }

    /**
     * Indicate that the system did not answer, after the given number of consecutive failures.
     */
    public function down(int $failures = 1): static
    {
        return $this->state(fn (array $attributes) => [
            'status' => SystemStatus::Down,
            'reason' => 'The connection was refused.',
            'http_status' => null,
            'response_time_ms' => null,
            'consecutive_failures' => $failures,
        ]);
    }

    /**
     * Indicate that the scheduled re-check is long overdue, so the result is out of date.
     */
    public function stale(): static
    {
        return $this->state(fn (array $attributes) => [
            'checked_at' => now()->subHours(3),
            'next_check_at' => now()->subHours(3)->addMinutes(5),
        ]);
    }
}

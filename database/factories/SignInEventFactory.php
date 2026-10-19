<?php

namespace Database\Factories;

use App\Models\SignInEvent;
use App\Models\User;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<SignInEvent>
 */
class SignInEventFactory extends Factory
{
    /**
     * Define the model's default state.
     *
     * @return array<string, mixed>
     */
    public function definition(): array
    {
        return [
            'user_id' => User::factory(),
            'signed_in_at' => now(),
        ];
    }
}

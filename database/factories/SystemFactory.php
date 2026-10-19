<?php

namespace Database\Factories;

use App\Enums\SystemEnvironment;
use App\Models\System;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<System>
 */
class SystemFactory extends Factory
{
    /**
     * Define the model's default state.
     *
     * @return array<string, mixed>
     */
    public function definition(): array
    {
        $ip = fake()->localIpv4();

        return [
            'name' => strtoupper(fake()->unique()->lexify('????')).' PROD',
            'description' => fake()->catchPhrase(),
            'environment' => SystemEnvironment::Production,
            'local_ip' => "{$ip}/".fake()->slug(2),
            'local_username' => 'admin',
            'local_password' => fake()->password(),
        ];
    }

    /**
     * Indicate that every remote access and server credential is filled in.
     */
    public function withAllCredentials(): static
    {
        return $this->state(fn (array $attributes) => [
            'anydesk_id' => (string) fake()->numberBetween(100000000, 999999999),
            'anydesk_password' => fake()->password(),
            'rustdesk_id' => (string) fake()->numberBetween(100000000, 999999999),
            'rustdesk_password' => fake()->password(),
            'rdp_host' => fake()->localIpv4(),
            'rdp_username' => 'Administrator',
            'rdp_password' => fake()->password(),
            'ssh_host' => fake()->localIpv4(),
            'ssh_username' => 'root',
            'ssh_password' => fake()->password(),
            'mysql_host' => fake()->localIpv4().'/phpmyadmin',
            'mysql_username' => 'root',
            'mysql_password' => fake()->password(),
            'domain' => fake()->domainName(),
            'domain_username' => fake()->safeEmail(),
            'domain_password' => fake()->password(),
            'vm_host' => fake()->localIpv4(),
            'vm_username' => 'root',
            'vm_password' => fake()->password(),
            'notes' => fake()->sentence(),
        ]);
    }
}

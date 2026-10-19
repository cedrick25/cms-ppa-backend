<?php

namespace Tests\Feature;

use App\Models\SignInEvent;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Tests\TestCase;

class SignInActivityTest extends TestCase
{
    use RefreshDatabase;

    public function test_a_successful_sign_in_is_recorded_with_server_time_and_listed_on_the_next_load(): void
    {
        $this->travelTo(Carbon::parse('2026-10-15 08:30:00', 'UTC'));
        $admin = User::factory()->administrator()->create(['name' => 'Cms Admin']);
        $older = SignInEvent::factory()->create(['signed_in_at' => now()->subHour()]);
        User::factory()->create([
            'name' => 'Juan Dela Cruz',
            'username' => 'juan',
            'password' => 'Correct-Horse-12',
        ]);

        $this->postJson('/api/sign-in', ['username' => 'juan', 'password' => 'Correct-Horse-12'])->assertOk();

        $this->actingAs($admin)->getJson('/api/dashboard/sign-ins')
            ->assertOk()
            ->assertJsonPath('meta.shows', 'recent_sign_ins')
            ->assertJsonPath('data.0.name', 'Juan Dela Cruz')
            ->assertJsonPath('data.0.signed_in_at', '2026-10-15T08:30:00+00:00')
            ->assertJsonPath('data.1.id', $older->id);
    }

    public function test_failed_and_disabled_sign_ins_are_not_recorded(): void
    {
        User::factory()->create(['username' => 'ada', 'password' => 'Correct-Horse-12']);
        User::factory()->disabled()->create(['username' => 'off', 'password' => 'Correct-Horse-12']);

        $this->postJson('/api/sign-in', ['username' => 'ada', 'password' => 'wrong-password'])->assertStatus(422);
        $this->postJson('/api/sign-in', ['username' => 'off', 'password' => 'Correct-Horse-12'])->assertForbidden();

        $this->assertDatabaseCount('sign_in_events', 0);
    }

    public function test_only_administrators_can_see_sign_in_activity(): void
    {
        $this->getJson('/api/dashboard/sign-ins')->assertUnauthorized();

        $this->actingAs(User::factory()->create())->getJson('/api/dashboard/sign-ins')->assertForbidden();
    }

    public function test_the_list_is_newest_first_limited_and_exposes_only_name_and_time(): void
    {
        $this->freezeTime();
        config(['cms.sign_ins.dashboard_limit' => 2]);
        SignInEvent::factory()->create(['signed_in_at' => now()->subDays(2)]);
        $newest = SignInEvent::factory()->create(['signed_in_at' => now()]);
        $middle = SignInEvent::factory()->create(['signed_in_at' => now()->subDay()]);

        $response = $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/dashboard/sign-ins')
            ->assertOk()
            ->assertJsonCount(2, 'data')
            ->assertJsonPath('data.*.id', [$newest->id, $middle->id]);

        $this->assertSame(['id', 'name', 'signed_in_at'], array_keys($response->json('data.0')));
    }

    public function test_an_empty_history_returns_an_empty_list(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/dashboard/sign-ins')
            ->assertOk()
            ->assertJsonCount(0, 'data');
    }

    public function test_events_older_than_the_retention_period_are_pruned(): void
    {
        config(['cms.sign_ins.retention_days' => 90]);
        $old = SignInEvent::factory()->create(['signed_in_at' => now()->subDays(91)]);
        $recent = SignInEvent::factory()->create(['signed_in_at' => now()->subDays(89)]);

        $this->artisan('model:prune', ['--model' => [SignInEvent::class]])->assertSuccessful();

        $this->assertModelMissing($old);
        $this->assertModelExists($recent);
    }
}

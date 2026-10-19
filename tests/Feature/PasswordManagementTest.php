<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

class PasswordManagementTest extends TestCase
{
    use RefreshDatabase;

    private const CURRENT = 'Original-Secret-1';

    private const NEW = 'Brand-New-Secret-2';

    public function test_guests_cannot_reset_passwords(): void
    {
        $user = User::factory()->create();

        $this->putJson("/api/users/{$user->id}/password", [])->assertUnauthorized();
    }

    public function test_administrator_can_reset_a_regular_users_password(): void
    {
        Log::spy();
        $admin = User::factory()->administrator()->create(['username' => 'admin']);
        $user = User::factory()->create(['email' => 'ben@example.com', 'password' => self::CURRENT]);
        $token = $user->createApiToken();
        DB::table('password_reset_tokens')->insert(['email' => 'ben@example.com', 'token' => 'hashed', 'created_at' => now()]);

        $response = $this->actingAs($admin)->putJson("/api/users/{$user->id}/password", [
            'password' => self::NEW,
            'password_confirmation' => self::NEW,
        ])->assertOk();

        $this->assertStringNotContainsString(self::NEW, $response->getContent());
        $this->assertTrue(Hash::check(self::NEW, $user->fresh()->password));
        $this->assertDatabaseMissing('personal_access_tokens', ['token' => hash('sha256', $token)]);
        $this->assertDatabaseMissing('password_reset_tokens', ['email' => 'ben@example.com']);

        Log::shouldHaveReceived('info')->withArgs(fn (string $message, array $context) => $message === 'User password reset by administrator.'
            && $context['performed_by'] === 'admin'
            && ! str_contains(json_encode($context), self::NEW))->once();
    }

    public function test_administrator_cannot_reset_another_administrators_password(): void
    {
        $admin = User::factory()->administrator()->create();
        $otherAdmin = User::factory()->administrator()->create(['password' => self::CURRENT]);

        $this->actingAs($admin)->putJson("/api/users/{$otherAdmin->id}/password", [
            'password' => self::NEW,
            'password_confirmation' => self::NEW,
        ])->assertForbidden()
            ->assertJsonPath('message', "Administrators manage their own passwords. You can't set a password for another administrator.");

        $this->assertTrue(Hash::check(self::CURRENT, $otherAdmin->fresh()->password));
    }

    public function test_administrator_must_use_the_self_service_form_for_their_own_password(): void
    {
        $admin = User::factory()->administrator()->create(['password' => self::CURRENT]);

        $this->actingAs($admin)->putJson("/api/users/{$admin->id}/password", [
            'password' => self::NEW,
            'password_confirmation' => self::NEW,
        ])->assertForbidden();

        $this->assertTrue(Hash::check(self::CURRENT, $admin->fresh()->password));
    }

    public function test_regular_users_cannot_reset_other_users_passwords(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create(['password' => self::CURRENT]);

        $this->actingAs($user)->putJson("/api/users/{$other->id}/password", [
            'password' => self::NEW,
            'password_confirmation' => self::NEW,
        ])->assertForbidden();

        $this->assertTrue(Hash::check(self::CURRENT, $other->fresh()->password));
    }

    public function test_reset_password_must_be_confirmed(): void
    {
        $admin = User::factory()->administrator()->create();
        $user = User::factory()->create(['password' => self::CURRENT]);

        $this->actingAs($admin)->putJson("/api/users/{$user->id}/password", [
            'password' => self::NEW,
            'password_confirmation' => 'something-else',
        ])->assertUnprocessable()
            ->assertJsonValidationErrors(['password_confirmation' => "Passwords don't match."]);

        $this->assertTrue(Hash::check(self::CURRENT, $user->fresh()->password));
    }

    public function test_user_list_shows_whose_password_the_administrator_can_reset(): void
    {
        $admin = User::factory()->administrator()->create(['username' => 'admin']);
        User::factory()->administrator()->create(['username' => 'other.admin']);
        User::factory()->create(['username' => 'regular']);

        $rows = collect($this->actingAs($admin)->getJson('/api/users')->assertOk()->json('data'))->keyBy('username');

        $this->assertFalse($rows['admin']['can']['reset_password']);
        $this->assertFalse($rows['other.admin']['can']['reset_password']);
        $this->assertTrue($rows['regular']['can']['reset_password']);
        $this->assertSame('administrator', $rows['other.admin']['role']);
    }
}

<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Gate;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

class UserManagementTest extends TestCase
{
    use RefreshDatabase;

    private function admin(): User
    {
        return User::factory()->administrator()->create([
            'name' => 'Admin Person',
            'username' => 'admin',
            'email' => 'admin@example.com',
        ]);
    }

    public function test_guests_cannot_reach_any_user_management_endpoint(): void
    {
        $user = User::factory()->create();

        $this->get('/users')->assertNotFound();
        $this->getJson('/api/users')->assertUnauthorized();
        $this->patchJson("/api/users/{$user->id}", ['name' => 'X'])->assertUnauthorized();
        $this->postJson("/api/users/{$user->id}/disable")->assertUnauthorized();
        $this->postJson("/api/users/{$user->id}/enable")->assertUnauthorized();
        $this->deleteJson("/api/users/{$user->id}", ['confirm_username' => $user->username])->assertUnauthorized();

        $this->assertDatabaseHas('users', ['id' => $user->id, 'disabled_at' => null]);
    }

    public function test_disabled_accounts_cannot_manage_users_and_are_signed_out(): void
    {
        $disabled = User::factory()->disabled()->create();
        $target = User::factory()->create();

        $this->actingAs($disabled)
            ->postJson("/api/users/{$target->id}/disable")
            ->assertUnauthorized()
            ->assertJsonPath('message', 'This account is disabled. Contact a CMS-PPA administrator.');

        $this->assertGuest();
        $this->assertDatabaseHas('users', ['id' => $target->id, 'disabled_at' => null]);
    }

    public function test_an_authenticated_user_without_permission_receives_forbidden(): void
    {
        Gate::define('manage-users', fn (): bool => false);

        $this->actingAs($this->admin())
            ->getJson('/api/users')
            ->assertForbidden();
    }

    public function test_regular_users_cannot_manage_users(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create();

        $this->actingAs($user)->getJson('/api/user')->assertOk()
            ->assertJsonPath('role', 'user')
            ->assertJsonPath('can.manage_users', false);
        $this->actingAs($user)->getJson('/api/users')->assertForbidden();
        $this->actingAs($user)->postJson("/api/users/{$other->id}/disable")->assertForbidden();

        $this->assertDatabaseHas('users', ['id' => $other->id, 'disabled_at' => null]);
    }

    public function test_administrator_can_change_their_own_role_and_then_loses_access(): void
    {
        $admin = $this->admin();
        $details = [
            'first_name' => 'Admin',
            'last_name' => 'Person',
            'email' => $admin->email,
            'username' => $admin->username,
            'role' => 'user',
        ];

        $this->actingAs($admin)->patchJson("/api/users/{$admin->id}", $details)
            ->assertOk()
            ->assertJsonPath('user.role', 'user');

        $this->assertFalse($admin->fresh()->isAdministrator());

        $this->actingAs($admin->fresh())->getJson('/api/users')->assertForbidden();
    }

    public function test_regular_users_cannot_change_their_own_role(): void
    {
        $user = User::factory()->create();

        $this->actingAs($user)->patchJson("/api/users/{$user->id}", [
            'first_name' => $user->first_name,
            'last_name' => $user->last_name,
            'email' => $user->email,
            'username' => $user->username,
            'role' => 'administrator',
        ])->assertForbidden();

        $this->assertFalse($user->fresh()->isAdministrator());
    }

    public function test_list_returns_accounts_without_passwords_and_flags_the_current_user(): void
    {
        $admin = $this->admin();
        User::factory()->create(['name' => 'Ben Cruz', 'username' => 'bcruz', 'email' => 'ben@example.com']);
        User::factory()->disabled()->create(['username' => 'old.account']);

        $response = $this->actingAs($admin)->getJson('/api/users')->assertOk();

        $response->assertJsonCount(3, 'data')
            ->assertJsonPath('meta.total', 3)
            ->assertJsonPath('meta.total_registered', 3)
            ->assertJsonPath('counts.all', 3)
            ->assertJsonPath('counts.active', 2)
            ->assertJsonPath('counts.disabled', 1)
            ->assertJsonStructure(['data' => [[
                'id', 'name', 'username', 'email', 'status', 'disabled_at', 'created_at', 'updated_at', 'is_current_user',
            ]]]);

        $rows = collect($response->json('data'))->keyBy('username');
        $this->assertTrue($rows['admin']['is_current_user']);
        $this->assertFalse($rows['bcruz']['is_current_user']);
        $this->assertSame('active', $rows['bcruz']['status']);
        $this->assertSame('disabled', $rows['old.account']['status']);

        foreach ($response->json('data') as $row) {
            $this->assertArrayNotHasKey('password', $row);
            $this->assertArrayNotHasKey('remember_token', $row);
        }
        $this->assertStringNotContainsString('$2y$', $response->getContent());
    }

    public function test_list_can_be_searched_and_filtered_by_status(): void
    {
        $admin = $this->admin();
        User::factory()->create(['name' => 'Ben Cruz', 'username' => 'bcruz', 'email' => 'ben@example.com']);
        User::factory()->disabled()->create(['name' => 'Bea Reyes', 'username' => 'breyes', 'email' => 'bea@ports.test']);
        User::factory()->create(['name' => 'Carla Lim', 'username' => 'clim', 'email' => 'carla@ports.test']);

        $this->actingAs($admin)->getJson('/api/users?search=ports.test')
            ->assertOk()
            ->assertJsonCount(2, 'data')
            ->assertJsonPath('counts.all', 2)
            ->assertJsonPath('counts.active', 1)
            ->assertJsonPath('counts.disabled', 1)
            ->assertJsonPath('meta.total_registered', 4);

        $this->actingAs($admin)->getJson('/api/users?search=b&status=disabled')
            ->assertOk()
            ->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.username', 'breyes');

        $this->actingAs($admin)->getJson('/api/users?search=nobody-matches')
            ->assertOk()
            ->assertJsonCount(0, 'data')
            ->assertJsonPath('meta.total', 0)
            ->assertJsonPath('meta.total_registered', 4);

        $this->actingAs($admin)->getJson('/api/users?status=everyone')
            ->assertStatus(422)
            ->assertJsonValidationErrors(['status']);
    }

    public function test_list_is_paginated(): void
    {
        $admin = $this->admin();
        User::factory()->count(24)->create();

        $this->actingAs($admin)->getJson('/api/users?page=2')
            ->assertOk()
            ->assertJsonCount(5, 'data')
            ->assertJsonPath('meta.current_page', 2)
            ->assertJsonPath('meta.last_page', 2)
            ->assertJsonPath('meta.from', 21)
            ->assertJsonPath('meta.to', 25);
    }

    public function test_administrator_can_update_permitted_fields_without_touching_the_password(): void
    {
        Log::spy();
        $admin = $this->admin();
        $user = User::factory()->create([
            'name' => 'Ben Cruz',
            'username' => 'bcruz',
            'email' => 'ben@example.com',
            'password' => Hash::make('Original-Secret-1'),
        ]);
        $originalHash = $user->password;

        $this->actingAs($admin)->patchJson("/api/users/{$user->id}", [
            'name' => 'Ignored Name',
            'first_name' => '  Benjamin ',
            'middle_name' => 'Reyes',
            'last_name' => 'Dela  Cruz',
            'suffix' => 'Jr.',
            'email' => ' BENJAMIN@Example.com ',
            'username' => ' BCruz2 ',
            'role' => 'administrator',
            'password' => 'should-be-ignored',
        ])->assertOk()
            ->assertJsonPath('message', 'Changes saved for Benjamin Reyes Dela Cruz Jr.')
            ->assertJsonPath('user.name', 'Benjamin Reyes Dela Cruz Jr.')
            ->assertJsonPath('user.first_name', 'Benjamin')
            ->assertJsonPath('user.middle_name', 'Reyes')
            ->assertJsonPath('user.last_name', 'Dela Cruz')
            ->assertJsonPath('user.suffix', 'Jr.')
            ->assertJsonPath('user.email', 'benjamin@example.com')
            ->assertJsonPath('user.username', 'bcruz2')
            ->assertJsonPath('user.role', 'administrator')
            ->assertJsonMissingPath('user.password');

        $user->refresh();
        $this->assertSame($originalHash, $user->password);
        $this->assertTrue(Hash::check('Original-Secret-1', $user->password));

        Log::shouldHaveReceived('info')->withArgs(function (string $message, array $context) {
            return $message === 'User account updated.'
                && $context['performed_by'] === 'admin'
                && ! str_contains(json_encode($context), 'should-be-ignored');
        })->once();
    }

    public function test_saving_unchanged_details_is_allowed(): void
    {
        $admin = $this->admin();
        $user = User::factory()->create([
            'name' => 'Ben Cruz',
            'first_name' => 'Ben',
            'last_name' => 'Cruz',
            'username' => 'bcruz',
            'email' => 'ben@example.com',
        ]);

        $this->actingAs($admin)->patchJson("/api/users/{$user->id}", [
            'first_name' => 'Ben',
            'last_name' => 'Cruz',
            'email' => 'ben@example.com',
            'username' => 'bcruz',
            'role' => 'user',
        ])->assertOk()
            ->assertJsonPath('message', 'No changes were needed for Ben Cruz.');
    }

    public function test_clearing_middle_name_and_suffix_recomposes_the_name(): void
    {
        $admin = $this->admin();
        $user = User::factory()->create([
            'name' => 'Ben Reyes Cruz Jr.',
            'first_name' => 'Ben',
            'middle_name' => 'Reyes',
            'last_name' => 'Cruz',
            'suffix' => 'Jr.',
        ]);

        $this->actingAs($admin)->patchJson("/api/users/{$user->id}", [
            'first_name' => 'Ben',
            'middle_name' => '',
            'last_name' => 'Cruz',
            'suffix' => '',
            'email' => $user->email,
            'username' => $user->username,
            'role' => 'user',
        ])->assertOk()
            ->assertJsonPath('user.name', 'Ben Cruz')
            ->assertJsonPath('user.middle_name', null)
            ->assertJsonPath('user.suffix', null);

        $this->assertDatabaseHas('users', ['id' => $user->id, 'name' => 'Ben Cruz', 'middle_name' => null, 'suffix' => null]);
    }

    public function test_update_validates_every_field_on_the_server(): void
    {
        $admin = $this->admin();
        $user = User::factory()->create();

        $this->actingAs($admin)->patchJson("/api/users/{$user->id}", [
            'first_name' => '',
            'last_name' => '',
            'email' => 'not-an-email',
            'username' => 'no spaces!',
            'role' => 'superuser',
        ])->assertStatus(422)
            ->assertJsonValidationErrors(['first_name', 'last_name', 'email', 'username', 'role']);

        $this->actingAs($admin)->patchJson("/api/users/{$user->id}", [
            'first_name' => str_repeat('a', 51),
            'last_name' => 'Cruz',
            'suffix' => str_repeat('b', 11),
            'email' => 'ok@example.com',
            'username' => 'ab',
            'role' => 'user',
        ])->assertStatus(422)
            ->assertJsonValidationErrors(['first_name', 'suffix', 'username']);

        $this->actingAs($admin)->patchJson("/api/users/{$user->id}", [
            'first_name' => str_repeat('a', 50),
            'middle_name' => str_repeat('b', 30),
            'last_name' => str_repeat('c', 30),
            'email' => 'ok@example.com',
            'username' => 'okay',
            'role' => 'user',
        ])->assertStatus(422)
            ->assertJsonValidationErrors(['name']);
    }

    public function test_update_rejects_an_email_or_username_used_by_another_account(): void
    {
        $admin = $this->admin();
        $user = User::factory()->create(['username' => 'bcruz', 'email' => 'ben@example.com']);

        $this->actingAs($admin)->patchJson("/api/users/{$user->id}", [
            'first_name' => 'Ben',
            'last_name' => 'Cruz',
            'email' => 'ADMIN@example.com',
            'username' => 'Admin',
            'role' => 'user',
        ])->assertStatus(422)
            ->assertJsonValidationErrors(['email', 'username']);

        $this->assertDatabaseHas('users', ['id' => $user->id, 'username' => 'bcruz', 'email' => 'ben@example.com']);
    }

    public function test_updating_a_missing_user_returns_not_found(): void
    {
        $this->actingAs($this->admin())->patchJson('/api/users/9999', [
            'first_name' => 'Ghost',
            'last_name' => 'User',
            'email' => 'ghost@example.com',
            'username' => 'ghost',
            'role' => 'user',
        ])->assertNotFound();
    }

    public function test_disabled_user_cannot_sign_in_until_re_enabled(): void
    {
        $admin = $this->admin();
        $user = User::factory()->create(['username' => 'bcruz', 'password' => Hash::make('Original-Secret-1')]);

        $this->actingAs($admin)->postJson("/api/users/{$user->id}/disable")
            ->assertOk()
            ->assertJsonPath('user.status', 'disabled');
        $this->assertNotNull($user->fresh()->disabled_at);

        auth()->logout();

        $this->postJson('/api/sign-in', ['username' => 'bcruz', 'password' => 'Original-Secret-1'])
            ->assertForbidden()
            ->assertJsonPath('message', 'This account is disabled. Contact a CMS-PPA administrator.');
        $this->assertGuest();

        $this->actingAs($admin)->postJson("/api/users/{$user->id}/enable")
            ->assertOk()
            ->assertJsonPath('user.status', 'active');
        $this->assertNull($user->fresh()->disabled_at);

        auth()->guard('web')->logout();

        $this->postJson('/api/sign-in', ['username' => 'bcruz', 'password' => 'Original-Secret-1'])
            ->assertOk()
            ->assertJsonPath('user.username', 'bcruz');
        $this->assertGuest();
    }

    public function test_wrong_password_for_a_disabled_account_does_not_reveal_its_status(): void
    {
        User::factory()->disabled()->create(['username' => 'bcruz', 'password' => Hash::make('Original-Secret-1')]);

        $this->postJson('/api/sign-in', ['username' => 'bcruz', 'password' => 'wrong'])
            ->assertStatus(422)
            ->assertJsonMissing(['message' => 'This account is disabled. Contact a CMS-PPA administrator.']);
    }

    public function test_disabling_a_signed_in_user_rejects_their_token(): void
    {
        $user = User::factory()->create();
        $token = $user->createApiToken();

        $this->withToken($token)->getJson('/api/user')->assertOk();

        $user->forceFill(['disabled_at' => now()])->save();

        $this->withToken($token)->getJson('/api/user')
            ->assertUnauthorized()
            ->assertJsonPath('message', 'This account is disabled. Contact a CMS-PPA administrator.');

        $this->withToken($token)->getJson('/api/user')->assertUnauthorized();
    }

    public function test_disabling_removes_database_sessions_for_that_user(): void
    {
        config(['session.driver' => 'database']);
        $admin = $this->admin();
        $user = User::factory()->create();
        $plain = $user->createApiToken();
        DB::table('sessions')->insert([
            'id' => 'victim-session',
            'user_id' => $user->id,
            'payload' => '',
            'last_activity' => time(),
        ]);

        $this->actingAs($admin)->postJson("/api/users/{$user->id}/disable")->assertOk();

        $this->assertDatabaseMissing('sessions', ['id' => 'victim-session']);
        $this->assertDatabaseMissing('personal_access_tokens', ['token' => hash('sha256', $plain)]);
    }

    public function test_administrator_cannot_disable_or_remove_their_own_account(): void
    {
        $admin = $this->admin();

        $this->actingAs($admin)->postJson("/api/users/{$admin->id}/disable")
            ->assertStatus(422)
            ->assertJsonValidationErrors(['account'])
            ->assertJsonPath('errors.account.0', "You can't disable your own account.");

        $this->actingAs($admin)->deleteJson("/api/users/{$admin->id}", ['confirm_username' => 'admin'])
            ->assertStatus(422)
            ->assertJsonPath('errors.account.0', "You can't remove your own account.");

        $this->assertDatabaseHas('users', ['id' => $admin->id, 'disabled_at' => null]);
    }

    public function test_removal_requires_the_matching_username(): void
    {
        $admin = $this->admin();
        $user = User::factory()->create(['username' => 'bcruz']);

        $this->actingAs($admin)->deleteJson("/api/users/{$user->id}")
            ->assertStatus(422)
            ->assertJsonValidationErrors(['confirm_username']);

        $this->actingAs($admin)->deleteJson("/api/users/{$user->id}", ['confirm_username' => 'bcruzz'])
            ->assertStatus(422)
            ->assertJsonPath('errors.confirm_username.0', 'The username you typed does not match this account.');

        $this->assertDatabaseHas('users', ['id' => $user->id]);
    }

    public function test_removal_deletes_the_account_and_its_sessions_and_reset_tokens_and_logs_it(): void
    {
        Log::spy();
        config(['session.driver' => 'database']);
        $admin = $this->admin();
        $user = User::factory()->create(['name' => 'Ben Cruz', 'username' => 'bcruz', 'email' => 'ben@example.com']);
        DB::table('sessions')->insert([
            'id' => 'ben-session',
            'user_id' => $user->id,
            'payload' => '',
            'last_activity' => time(),
        ]);
        DB::table('password_reset_tokens')->insert([
            'email' => 'ben@example.com',
            'token' => 'hashed-token',
            'created_at' => now(),
        ]);

        $this->actingAs($admin)->deleteJson("/api/users/{$user->id}", ['confirm_username' => ' BCruz '])
            ->assertOk()
            ->assertJsonPath('message', 'Account removed: Ben Cruz (bcruz).');

        $this->assertDatabaseMissing('users', ['id' => $user->id]);
        $this->assertDatabaseMissing('sessions', ['id' => 'ben-session']);
        $this->assertDatabaseMissing('password_reset_tokens', ['email' => 'ben@example.com']);
        $this->assertDatabaseHas('users', ['id' => $admin->id]);

        Log::shouldHaveReceived('info')->withArgs(fn (string $message, array $context) => $message === 'User account removed.'
            && $context['username'] === 'bcruz'
            && $context['performed_by'] === 'admin')->once();

        $this->actingAs($admin)->deleteJson("/api/users/{$user->id}", ['confirm_username' => 'bcruz'])
            ->assertNotFound();
    }
}

<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

class AccountUpdateTest extends TestCase
{
    use RefreshDatabase;

    private const CURRENT = 'Original-Secret-1';

    private const NEW = 'Brand-New-Secret-2';

    private function holder(): User
    {
        return User::factory()->create([
            'name' => 'Carla Diaz',
            'first_name' => 'Carla',
            'last_name' => 'Diaz',
            'username' => 'cdiaz',
            'email' => 'carla@example.com',
            'password' => self::CURRENT,
        ]);
    }

    /**
     * @param  array<string, mixed>  $overrides
     * @return array<string, mixed>
     */
    private function details(User $user, array $overrides = []): array
    {
        return [
            'first_name' => $user->first_name,
            'middle_name' => $user->middle_name,
            'last_name' => $user->last_name,
            'suffix' => $user->suffix,
            'username' => $user->username,
            'email' => $user->email,
            'current_password' => self::CURRENT,
            ...$overrides,
        ];
    }

    public function test_guests_cannot_update_an_account(): void
    {
        $this->patchJson('/api/user', [])->assertUnauthorized();
    }

    public function test_user_can_update_their_own_details_but_not_their_role(): void
    {
        Log::spy();
        $user = $this->holder();
        $admin = User::factory()->administrator()->create();
        $currentToken = $user->createApiToken();
        $otherToken = $user->createApiToken();

        $this->withToken($currentToken)->patchJson('/api/user', $this->details($user, [
            'first_name' => '  Carla   Mae ',
            'middle_name' => 'Reyes',
            'suffix' => 'Jr.',
            'username' => ' CMDiaz ',
            'email' => 'Carla.Diaz@Example.com',
            'role' => 'administrator',
        ]))->assertOk()
            ->assertJsonPath('message', 'Your account details have been updated.')
            ->assertJsonPath('user.name', 'Carla Mae Reyes Diaz Jr.')
            ->assertJsonPath('user.first_name', 'Carla Mae')
            ->assertJsonPath('user.username', 'cmdiaz')
            ->assertJsonPath('user.email', 'carla.diaz@example.com')
            ->assertJsonPath('user.role', 'user');

        $this->assertDatabaseHas('users', ['id' => $user->id, 'username' => 'cmdiaz', 'role' => 'user']);
        $this->assertTrue(Hash::check(self::CURRENT, $user->fresh()->password));
        $this->assertDatabaseHas('personal_access_tokens', ['token' => hash('sha256', $otherToken)]);

        $this->assertSame(0, $admin->notifications()->count());
        $this->assertSame(0, $user->notifications()->count());

        Log::shouldHaveReceived('info')->withArgs(fn (string $message, array $context) => $message === 'User updated their own account.'
            && $context['user_id'] === $user->id
            && $context['password_changed'] === false)->once();
    }

    public function test_a_new_password_is_optional_and_signs_out_other_devices(): void
    {
        $user = $this->holder();
        $currentToken = $user->createApiToken();
        $otherToken = $user->createApiToken();

        $this->withToken($currentToken)->patchJson('/api/user', $this->details($user, [
            'password' => self::NEW,
            'password_confirmation' => self::NEW,
        ]))->assertOk()
            ->assertJsonPath('message', 'Your password has been changed. Any other devices signed in to your account have been signed out.');

        $this->assertTrue(Hash::check(self::NEW, $user->fresh()->password));
        $this->assertDatabaseHas('personal_access_tokens', ['token' => hash('sha256', $currentToken)]);
        $this->assertDatabaseMissing('personal_access_tokens', ['token' => hash('sha256', $otherToken)]);
    }

    public function test_saving_unchanged_details_is_allowed(): void
    {
        $user = $this->holder();
        $admin = User::factory()->administrator()->create();

        $this->withToken($user->createApiToken())->patchJson('/api/user', $this->details($user))
            ->assertOk()
            ->assertJsonPath('message', 'No changes were needed.');

        $this->assertSame(0, $admin->notifications()->count());
    }

    public function test_every_change_requires_the_correct_current_password(): void
    {
        $user = $this->holder();

        $this->actingAs($user)->patchJson('/api/user', $this->details($user, ['current_password' => '', 'username' => 'changed']))
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['current_password' => 'Enter your current password to save these changes.']);

        $this->actingAs($user)->patchJson('/api/user', $this->details($user, [
            'current_password' => 'not-my-password',
            'password' => self::NEW,
            'password_confirmation' => self::NEW,
        ]))->assertUnprocessable()
            ->assertJsonValidationErrors(['current_password' => 'Your current password is incorrect.']);

        $this->assertSame('cdiaz', $user->fresh()->username);
        $this->assertTrue(Hash::check(self::CURRENT, $user->fresh()->password));
    }

    public function test_a_new_password_must_differ_from_the_current_one_and_be_confirmed(): void
    {
        $user = $this->holder();

        $this->actingAs($user)->patchJson('/api/user', $this->details($user, [
            'password' => self::CURRENT,
            'password_confirmation' => self::CURRENT,
        ]))->assertUnprocessable()
            ->assertJsonValidationErrors(['password' => 'Choose a new password that is different from your current one.']);

        $this->actingAs($user)->patchJson('/api/user', $this->details($user, ['password' => self::NEW]))
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['password_confirmation' => 'Re-enter the new password to confirm it.']);

        $this->actingAs($user)->patchJson('/api/user', $this->details($user, [
            'password' => self::NEW,
            'password_confirmation' => 'something-else',
        ]))->assertUnprocessable()
            ->assertJsonValidationErrors(['password_confirmation' => "Passwords don't match."]);

        $this->assertTrue(Hash::check(self::CURRENT, $user->fresh()->password));
    }

    public function test_details_are_validated_with_messages_addressed_to_the_account_holder(): void
    {
        $user = $this->holder();
        User::factory()->create(['username' => 'taken', 'email' => 'taken@example.com']);

        $this->actingAs($user)->patchJson('/api/user', $this->details($user, [
            'first_name' => ' ',
            'last_name' => '',
            'email' => 'TAKEN@example.com',
            'username' => 'taken',
        ]))->assertUnprocessable()
            ->assertJsonValidationErrors([
                'first_name' => 'Enter your first name.',
                'last_name' => 'Enter your last name.',
                'email' => 'This email address is already registered to another account.',
                'username' => 'This username is already in use. Choose a different username.',
            ]);

        $this->assertSame('cdiaz', $user->fresh()->username);
    }

    public function test_background_polling_does_not_use_up_the_account_update_limit(): void
    {
        $user = $this->holder();
        $token = $user->createApiToken();

        for ($poll = 0; $poll < 6; $poll++) {
            $this->withToken($token)->getJson('/api/notifications')->assertOk();
        }
        $this->withToken($token)->getJson('/api/dashboard/systems')->assertOk();

        $this->withToken($token)->patchJson('/api/user', $this->details($user, ['username' => 'cmdiaz']))
            ->assertOk()
            ->assertJsonPath('user.username', 'cmdiaz');
    }

    public function test_account_updates_are_limited_to_six_attempts_a_minute(): void
    {
        $user = $this->holder();
        $token = $user->createApiToken();

        for ($attempt = 0; $attempt < 6; $attempt++) {
            $this->withToken($token)->patchJson('/api/user', $this->details($user))->assertOk();
        }

        $this->withToken($token)->patchJson('/api/user', $this->details($user))->assertTooManyRequests();
    }

    public function test_account_details_include_the_name_parts_for_the_form(): void
    {
        $user = $this->holder();

        $this->actingAs($user)->getJson('/api/user')
            ->assertOk()
            ->assertJsonPath('first_name', 'Carla')
            ->assertJsonPath('middle_name', null)
            ->assertJsonPath('last_name', 'Diaz')
            ->assertJsonPath('email', 'carla@example.com');
    }
}

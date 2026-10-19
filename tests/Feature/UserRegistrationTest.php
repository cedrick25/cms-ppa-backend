<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Hash;
use Tests\TestCase;

class UserRegistrationTest extends TestCase
{
    use RefreshDatabase;

    private const PASSWORD = 'Harbor-Lights-2026';

    /**
     * @return array<string, string>
     */
    private function validInput(array $overrides = []): array
    {
        return array_merge([
            'first_name' => 'Maria',
            'last_name' => 'Santos',
            'email' => 'maria.santos@example.com',
            'username' => 'msantos',
            'role' => 'user',
            'password' => self::PASSWORD,
            'password_confirmation' => self::PASSWORD,
        ], $overrides);
    }

    public function test_guests_cannot_submit_registration(): void
    {
        $this->get('/users/register')->assertNotFound();
        $this->postJson('/api/users', $this->validInput())->assertUnauthorized();
        $this->assertDatabaseCount('users', 0);
    }

    public function test_regular_users_cannot_register_accounts(): void
    {
        $this->actingAs(User::factory()->create())
            ->postJson('/api/users', $this->validInput())
            ->assertForbidden();

        $this->assertDatabaseMissing('users', ['username' => 'msantos']);
    }

    public function test_the_chosen_role_is_stored(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput(['role' => 'administrator']))
            ->assertCreated()
            ->assertJsonPath('user.role', 'administrator');

        $this->assertTrue(User::query()->where('username', 'msantos')->firstOrFail()->isAdministrator());
    }

    public function test_unknown_role_is_rejected(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput(['role' => 'superuser']))
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['role' => 'Choose Administrator or User.']);
    }

    public function test_account_is_created_with_normalized_values_and_a_hashed_password(): void
    {
        $response = $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput([
                'first_name' => '  Maria  ',
                'last_name' => ' Santos ',
                'email' => ' Maria.Santos@Example.com ',
                'username' => ' MSantos ',
            ]));

        $response->assertCreated()
            ->assertJsonPath('user.name', 'Maria Santos')
            ->assertJsonPath('user.email', 'maria.santos@example.com')
            ->assertJsonPath('user.username', 'msantos');

        $this->assertStringNotContainsString(self::PASSWORD, $response->getContent());

        $user = User::query()->where('username', 'msantos')->firstOrFail();
        $this->assertNotSame(self::PASSWORD, $user->password);
        $this->assertTrue(Hash::check(self::PASSWORD, $user->password));
    }

    public function test_name_is_composed_from_first_middle_last_name_and_suffix(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput([
                'first_name' => 'Juan  Miguel',
                'middle_name' => ' Reyes ',
                'last_name' => 'Dela Cruz',
                'suffix' => 'Jr.',
            ]))
            ->assertCreated()
            ->assertJsonPath('user.name', 'Juan Miguel Reyes Dela Cruz Jr.');

        $this->assertDatabaseHas('users', [
            'username' => 'msantos',
            'name' => 'Juan Miguel Reyes Dela Cruz Jr.',
            'first_name' => 'Juan Miguel',
            'middle_name' => 'Reyes',
            'last_name' => 'Dela Cruz',
            'suffix' => 'Jr.',
        ]);
    }

    public function test_submitted_name_is_ignored_in_favor_of_the_name_parts(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput([
                'name' => 'Someone Else',
                'middle_name' => '',
                'suffix' => '',
            ]))
            ->assertCreated()
            ->assertJsonPath('user.name', 'Maria Santos');
    }

    public function test_combined_name_longer_than_100_characters_is_rejected(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput([
                'first_name' => str_repeat('a', 50),
                'middle_name' => str_repeat('b', 30),
                'last_name' => str_repeat('c', 30),
            ]))
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['name'])
            ->assertJsonMissingValidationErrors(['first_name', 'middle_name', 'last_name']);

        $this->assertDatabaseMissing('users', ['username' => 'msantos']);
    }

    public function test_new_user_can_sign_in_after_registration(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput())
            ->assertCreated();

        $response = $this->postJson('/api/sign-in', [
            'username' => 'msantos',
            'password' => self::PASSWORD,
        ])->assertOk();

        $this->withToken($response->json('token'))
            ->getJson('/api/user')
            ->assertOk()
            ->assertJsonPath('username', 'msantos');
    }

    public function test_required_fields_are_validated(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', [])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['first_name', 'last_name', 'email', 'username', 'role', 'password', 'password_confirmation'])
            ->assertJsonMissingValidationErrors(['middle_name', 'suffix', 'name']);
    }

    public function test_invalid_email_and_username_are_rejected(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput([
                'email' => 'not-an-email',
                'username' => 'has space',
            ]))
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['email', 'username'])
            ->assertJsonMissingValidationErrors(['password']);

        $this->assertDatabaseMissing('users', ['email' => 'not-an-email']);
    }

    public function test_any_non_empty_password_is_accepted(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput([
                'password' => 'abc',
                'password_confirmation' => 'abc',
            ]))
            ->assertCreated();

        $user = User::query()->where('username', 'msantos')->firstOrFail();
        $this->assertTrue(Hash::check('abc', $user->password));
    }

    public function test_password_confirmation_must_match(): void
    {
        $response = $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/users', $this->validInput(['password_confirmation' => 'Harbor-Lights-2025']))
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['password_confirmation' => "Passwords don't match."])
            ->assertJsonMissingValidationErrors(['password']);

        $this->assertStringNotContainsString(self::PASSWORD, $response->getContent());
    }

    public function test_duplicate_username_and_email_are_reported_per_field(): void
    {
        $admin = User::factory()->administrator()->create([
            'username' => 'msantos',
            'email' => 'maria.santos@example.com',
        ]);

        $this->actingAs($admin)
            ->postJson('/api/users', $this->validInput([
                'username' => 'MSANTOS',
                'email' => 'Maria.Santos@example.com',
            ]))
            ->assertUnprocessable()
            ->assertJsonValidationErrors([
                'username' => 'This username is already in use.',
                'email' => 'This email address is already registered',
            ]);

        $this->assertDatabaseCount('users', 1);
    }
}

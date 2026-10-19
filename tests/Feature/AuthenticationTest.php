<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Hash;
use Tests\TestCase;

class AuthenticationTest extends TestCase
{
    use RefreshDatabase;

    public function test_the_api_does_not_serve_application_pages(): void
    {
        $this->get('/sign-in')->assertNotFound();
        $this->get('/dashboard')->assertNotFound();
        $this->getJson('/api/user')->assertUnauthorized();
    }

    public function test_registered_user_can_sign_in_and_sign_out(): void
    {
        User::factory()->administrator()->create([
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ]);

        $response = $this->postJson('/api/sign-in', [
            'username' => 'Ada',
            'password' => 'Correct-Horse-12',
        ])->assertOk()
            ->assertJsonPath('token_type', 'Bearer')
            ->assertJsonPath('user.username', 'ada')
            ->assertJsonPath('user.role', 'administrator')
            ->assertJsonPath('user.can.manage_users', true);

        $token = $response->json('token');
        $this->assertIsString($token);
        $this->assertGuest();

        $this->withToken($token)->getJson('/api/user')
            ->assertOk()
            ->assertJsonPath('username', 'ada');

        $this->withToken($token)->postJson('/api/sign-out')
            ->assertOk()
            ->assertJsonPath('message', 'Signed out.');

        $this->withToken($token)->getJson('/api/user')->assertUnauthorized();
    }

    public function test_token_last_used_time_is_saved_at_most_once_a_minute(): void
    {
        $user = User::factory()->create([
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ]);

        $token = $this->postJson('/api/sign-in', [
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ])->json('token');

        $this->freezeSecond();
        $firstUse = now();
        $this->withToken($token)->getJson('/api/user')->assertOk();
        $this->assertTrue($user->tokens()->sole()->last_used_at->equalTo($firstUse));

        $this->travel(30)->seconds();
        $this->withToken($token)->getJson('/api/user')->assertOk();
        $this->assertTrue($user->tokens()->sole()->last_used_at->equalTo($firstUse));

        $this->travel(2)->minutes();
        $this->withToken($token)->getJson('/api/user')->assertOk();
        $this->assertTrue($user->tokens()->sole()->last_used_at->equalTo(now()));
    }

    public function test_unknown_user_and_wrong_password_return_the_same_error(): void
    {
        User::factory()->create([
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ]);

        $unknown = $this->postJson('/api/sign-in', [
            'username' => 'nobody',
            'password' => 'Correct-Horse-12',
        ]);

        $wrong = $this->postJson('/api/sign-in', [
            'username' => 'ada',
            'password' => 'not-the-password',
        ]);

        $unknown->assertStatus(422)->assertJsonPath('message', 'Incorrect username or password.');
        $wrong->assertStatus(422)->assertJsonPath('message', 'Incorrect username or password.');
        $this->assertGuest();
    }

    public function test_required_fields_and_username_format_are_validated(): void
    {
        $this->postJson('/api/sign-in', [
            'username' => '',
            'password' => '',
        ])->assertStatus(422)
            ->assertJsonValidationErrors(['username', 'password']);

        $this->postJson('/api/sign-in', [
            'username' => 'bad name',
            'password' => 'Correct-Horse-12',
        ])->assertStatus(422)
            ->assertJsonValidationErrors(['username']);
    }

    public function test_sign_in_attempts_are_rate_limited(): void
    {
        User::factory()->create([
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ]);

        for ($attempt = 0; $attempt < 5; $attempt++) {
            $this->postJson('/api/sign-in', [
                'username' => 'ada',
                'password' => 'wrong-password',
            ])->assertStatus(422);
        }

        $response = $this->postJson('/api/sign-in', [
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ]);

        $response->assertStatus(429);
        $this->assertStringContainsString('Too many sign-in attempts', (string) $response->json('message'));
    }

    public function test_passwords_are_stored_as_hashes(): void
    {
        $user = User::factory()->create([
            'password' => 'Correct-Horse-12',
        ]);

        $this->assertNotSame('Correct-Horse-12', $user->password);
        $this->assertMatchesRegularExpression('/^\$2[aby]\$/', $user->password);
        $this->assertTrue(Hash::check('Correct-Horse-12', $user->password));
        $this->assertDatabaseMissing('users', ['password' => 'Correct-Horse-12']);
    }
}

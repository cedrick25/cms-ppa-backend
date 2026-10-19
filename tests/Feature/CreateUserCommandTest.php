<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Hash;
use Tests\TestCase;

class CreateUserCommandTest extends TestCase
{
    use RefreshDatabase;

    public function test_command_creates_a_hashed_account_without_printing_the_password(): void
    {
        $password = 'Correct-Horse-12';

        $exit = Artisan::call('cms:create-user', [
            '--name' => 'Ada Lovelace',
            '--username' => 'Ada',
            '--email' => 'Ada@Example.com',
            '--password' => $password,
        ]);

        $output = Artisan::output();

        $this->assertSame(0, $exit);
        $this->assertStringContainsString('Administrator account created for username "ada".', $output);
        $this->assertStringNotContainsString($password, $output);

        $user = User::query()->where('username', 'ada')->firstOrFail();
        $this->assertSame('ada@example.com', $user->email);
        $this->assertTrue($user->isAdministrator());
        $this->assertNotSame($password, $user->password);
        $this->assertTrue(Hash::check($password, $user->password));
    }

    public function test_command_refuses_to_run_without_a_password(): void
    {
        config(['cms.admin.password' => null]);

        $exit = Artisan::call('cms:create-user', [
            '--name' => 'Ada Lovelace',
            '--username' => 'ada',
            '--email' => 'ada@example.com',
        ]);

        $this->assertSame(1, $exit);
        $this->assertDatabaseCount('users', 0);
        $this->assertStringNotContainsString('password123', Artisan::output());
    }
}

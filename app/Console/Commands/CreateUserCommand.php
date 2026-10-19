<?php

namespace App\Console\Commands;

use App\Enums\Role;
use App\Models\User;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Validator;
use Illuminate\Validation\Rules\Password;

class CreateUserCommand extends Command
{
    protected $signature = 'cms:create-user
        {--name= : Display name}
        {--username= : Sign-in username}
        {--email= : Account email address}
        {--password= : Password. Prefer the CMS_ADMIN_PASSWORD environment variable so it is not stored in shell history}';

    protected $description = 'Create a CMS-PPA administrator account from the environment or command options';

    public function handle(): int
    {
        $passwordFromOption = $this->option('password');
        $password = is_string($passwordFromOption) && $passwordFromOption !== ''
            ? $passwordFromOption
            : config('cms.admin.password');

        $data = [
            'name' => $this->stringOption('name', 'cms.admin.name'),
            'username' => strtolower(trim($this->stringOption('username', 'cms.admin.username'))),
            'email' => strtolower(trim($this->stringOption('email', 'cms.admin.email'))),
            'password' => is_string($password) ? $password : '',
        ];

        if (is_string($passwordFromOption) && $passwordFromOption !== '') {
            $this->warn('The password was supplied with --password and may remain in shell history. Prefer CMS_ADMIN_PASSWORD.');
        }

        $validator = Validator::make($data, [
            'name' => ['required', 'string', 'max:100'],
            'username' => ['required', 'string', 'min:3', 'max:50', 'regex:/^[a-z0-9._-]+$/', 'unique:users,username'],
            'email' => ['required', 'string', 'email', 'max:255', 'unique:users,email'],
            'password' => ['required', 'string', Password::min(12)->letters()->mixedCase()->numbers()],
        ], [
            'name.required' => 'A display name is required. Set CMS_ADMIN_NAME or pass --name.',
            'username.required' => 'A username is required. Set CMS_ADMIN_USERNAME or pass --username.',
            'username.unique' => 'An account with that username already exists.',
            'email.required' => 'An email address is required. Set CMS_ADMIN_EMAIL or pass --email.',
            'email.unique' => 'An account with that email address already exists.',
            'password.required' => 'A password is required. Set CMS_ADMIN_PASSWORD in the environment for this command, then remove it. Do not put the password in source code.',
        ]);

        if ($validator->fails()) {
            foreach ($validator->errors()->all() as $error) {
                $this->error($error);
            }

            return self::FAILURE;
        }

        User::query()->create($validator->validated() + ['role' => Role::Administrator]);

        $this->info('Administrator account created for username "'.$data['username'].'".');
        $this->line('Remove CMS_ADMIN_PASSWORD from the environment now. The password was not written to a file or log.');

        return self::SUCCESS;
    }

    private function stringOption(string $option, string $configKey): string
    {
        $value = $this->option($option);

        if (is_string($value) && trim($value) !== '') {
            return trim($value);
        }

        $configured = config($configKey);

        return is_string($configured) ? trim($configured) : '';
    }
}

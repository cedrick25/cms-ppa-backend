<?php

namespace App\Http\Requests\Concerns;

trait ValidatesNewPassword
{
    /**
     * @return array<string, array<int, mixed>>
     */
    protected function newPasswordRules(): array
    {
        return [
            'password' => ['required', 'string', 'max:255'],
            'password_confirmation' => ['required', 'string', 'same:password'],
        ];
    }

    /**
     * @return array<string, string>
     */
    protected function newPasswordMessages(): array
    {
        return [
            'password.required' => 'Enter a password.',
            'password.max' => 'Password must be 255 characters or fewer.',
            'password_confirmation.required' => 'Re-enter the password to confirm it.',
            'password_confirmation.same' => "Passwords don't match.",
        ];
    }
}

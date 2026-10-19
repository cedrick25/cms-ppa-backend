<?php

namespace App\Http\Requests\Concerns;

use App\Enums\Role;
use App\Models\User;
use Illuminate\Validation\Rule;

trait ValidatesAccountDetails
{
    public const USERNAME_TAKEN = 'This username is already in use. Choose a different username.';

    public const EMAIL_TAKEN = 'This email address is already registered to another account.';

    /**
     * In the order they appear in the composed full name.
     */
    public const NAME_PARTS = ['first_name', 'middle_name', 'last_name', 'suffix'];

    /**
     * Unique indexes can still reject a value that another request claimed after validation.
     *
     * @return array<string, string>
     */
    public static function duplicateAccountErrors(string $username, string $email, ?int $ignoreUserId = null): array
    {
        $taken = fn (string $column, string $value): bool => User::query()
            ->where($column, $value)
            ->when($ignoreUserId !== null, fn ($query) => $query->whereKeyNot($ignoreUserId))
            ->exists();

        $errors = [];

        if ($taken('username', $username)) {
            $errors['username'] = self::USERNAME_TAKEN;
        }

        if ($taken('email', $email)) {
            $errors['email'] = self::EMAIL_TAKEN;
        }

        return $errors !== [] ? $errors : ['username' => self::USERNAME_TAKEN];
    }

    /**
     * The stored name is always composed from the name parts; a submitted `name` is ignored.
     */
    protected function normalizeAccountDetails(): void
    {
        $normalized = [];

        foreach (['username', 'email'] as $field) {
            $value = $this->input($field);

            if (is_string($value)) {
                $normalized[$field] = strtolower(trim($value));
            }
        }

        foreach (self::NAME_PARTS as $field) {
            $value = $this->input($field);

            if (is_string($value)) {
                $value = preg_replace('/\s+/u', ' ', trim($value)) ?? $value;
                $normalized[$field] = $value !== '' ? $value : null;
            } elseif ($value === null) {
                $normalized[$field] = null;
            }
        }

        $parts = array_filter(
            array_intersect_key($normalized, array_flip(self::NAME_PARTS)),
            fn (?string $part): bool => $part !== null,
        );

        $this->merge($normalized + ['name' => implode(' ', $parts)]);
    }

    /**
     * @return array<string, array<int, mixed>>
     */
    protected function accountDetailRules(?int $ignoreUserId = null): array
    {
        return [
            'first_name' => ['required', 'string', 'max:50'],
            'middle_name' => ['nullable', 'string', 'max:50'],
            'last_name' => ['required', 'string', 'max:50'],
            'suffix' => ['nullable', 'string', 'max:10'],
            'name' => ['string', 'max:100'],
            'email' => ['required', 'string', 'email', 'max:255', Rule::unique('users', 'email')->ignore($ignoreUserId)],
            'username' => ['required', 'string', 'min:3', 'max:50', 'regex:/^[a-z0-9._-]+$/', Rule::unique('users', 'username')->ignore($ignoreUserId)],
            'role' => ['required', Rule::enum(Role::class)],
        ];
    }

    /**
     * @return array<string, string>
     */
    protected function accountDetailMessages(): array
    {
        return [
            'first_name.required' => "Enter the user's first name.",
            'first_name.max' => 'First name must be 50 characters or fewer.',
            'middle_name.max' => 'Middle name must be 50 characters or fewer.',
            'last_name.required' => "Enter the user's last name.",
            'last_name.max' => 'Last name must be 50 characters or fewer.',
            'suffix.max' => 'Suffix must be 10 characters or fewer.',
            'name.max' => 'The full name (first, middle, and last name plus suffix) must be 100 characters or fewer in total.',
            'email.required' => "Enter the user's email address.",
            'email.email' => 'Enter a valid email address, like name@example.com.',
            'email.max' => 'Email address must be 255 characters or fewer.',
            'email.unique' => self::EMAIL_TAKEN,
            'username.required' => 'Choose a username.',
            'username.min' => 'Username must be at least 3 characters.',
            'username.max' => 'Username must be 50 characters or fewer.',
            'username.regex' => 'Use only letters, numbers, dots, underscores, and hyphens. No spaces.',
            'username.unique' => self::USERNAME_TAKEN,
            'role.required' => 'Choose a role.',
            'role.enum' => 'Choose Administrator or User.',
        ];
    }

    /**
     * @return array<int, string>
     */
    public static function accountDetailFields(): array
    {
        return [...self::NAME_PARTS, 'name', 'username', 'email', 'role'];
    }

    /**
     * Human label for each account detail field. Every name part is reported as the name.
     *
     * @return array<string, string>
     */
    public static function accountDetailLabels(): array
    {
        return [
            ...array_fill_keys([...self::NAME_PARTS, 'name'], 'Name'),
            'username' => 'Username',
            'email' => 'Email address',
            'role' => 'Role',
        ];
    }
}

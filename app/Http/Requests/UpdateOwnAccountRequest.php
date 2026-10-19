<?php

namespace App\Http\Requests;

use App\Http\Requests\Concerns\ValidatesAccountDetails;
use App\Http\Requests\Concerns\ValidatesNewPassword;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Support\Arr;

/**
 * The signed-in user updating their own details. The role can't be changed here, and the
 * current password is always required. A new password is optional.
 */
class UpdateOwnAccountRequest extends FormRequest
{
    use ValidatesAccountDetails;
    use ValidatesNewPassword;

    public function authorize(): bool
    {
        return true;
    }

    protected function prepareForValidation(): void
    {
        $this->normalizeAccountDetails();
    }

    /**
     * @return array<string, array<int, mixed>>
     */
    public function rules(): array
    {
        return [
            ...Arr::except($this->accountDetailRules($this->user()?->id), ['role']),
            'current_password' => ['required', 'string', 'current_password'],
            'password' => ['nullable', 'string', 'max:255', 'different:current_password'],
            'password_confirmation' => ['nullable', 'required_with:password', 'string', 'same:password'],
        ];
    }

    /**
     * @return array<string, string>
     */
    public function messages(): array
    {
        return [
            'first_name.required' => 'Enter your first name.',
            'last_name.required' => 'Enter your last name.',
            'email.required' => 'Enter your email address.',
            'current_password.required' => 'Enter your current password to save these changes.',
            'current_password.current_password' => 'Your current password is incorrect.',
            'password.different' => 'Choose a new password that is different from your current one.',
            'password_confirmation.required_with' => 'Re-enter the new password to confirm it.',
        ] + $this->accountDetailMessages() + $this->newPasswordMessages();
    }

    /**
     * @return array<int, string>
     */
    public static function ownAccountFields(): array
    {
        return [...self::NAME_PARTS, 'name', 'username', 'email'];
    }

    public function wantsNewPassword(): bool
    {
        return $this->filled('password');
    }
}

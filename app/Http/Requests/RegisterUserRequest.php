<?php

namespace App\Http\Requests;

use App\Http\Requests\Concerns\ValidatesAccountDetails;
use App\Http\Requests\Concerns\ValidatesNewPassword;
use Illuminate\Foundation\Http\FormRequest;

class RegisterUserRequest extends FormRequest
{
    use ValidatesAccountDetails, ValidatesNewPassword;

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
        return $this->accountDetailRules() + $this->newPasswordRules();
    }

    /**
     * @return array<string, string>
     */
    public function messages(): array
    {
        return $this->accountDetailMessages() + $this->newPasswordMessages();
    }
}

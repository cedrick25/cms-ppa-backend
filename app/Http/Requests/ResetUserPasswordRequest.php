<?php

namespace App\Http\Requests;

use App\Http\Requests\Concerns\ValidatesNewPassword;
use Illuminate\Auth\Access\Response;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Support\Facades\Gate;

class ResetUserPasswordRequest extends FormRequest
{
    use ValidatesNewPassword;

    public function authorize(): Response
    {
        return Gate::inspect('reset-password', $this->route('user'));
    }

    /**
     * @return array<string, array<int, mixed>>
     */
    public function rules(): array
    {
        return $this->newPasswordRules();
    }

    /**
     * @return array<string, string>
     */
    public function messages(): array
    {
        return $this->newPasswordMessages();
    }
}

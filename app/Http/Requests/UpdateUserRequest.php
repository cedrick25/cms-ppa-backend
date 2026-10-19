<?php

namespace App\Http\Requests;

use App\Http\Requests\Concerns\ValidatesAccountDetails;
use App\Models\User;
use Illuminate\Foundation\Http\FormRequest;

class UpdateUserRequest extends FormRequest
{
    use ValidatesAccountDetails;

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
        $user = $this->route('user');

        return $this->accountDetailRules($user instanceof User ? $user->id : null);
    }

    /**
     * @return array<string, string>
     */
    public function messages(): array
    {
        return $this->accountDetailMessages();
    }
}

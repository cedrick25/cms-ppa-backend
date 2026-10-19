<?php

namespace App\Http\Requests;

use App\Http\Requests\Concerns\ValidatesSystemDetails;
use Illuminate\Foundation\Http\FormRequest;

class StoreSystemRequest extends FormRequest
{
    use ValidatesSystemDetails;

    public function authorize(): bool
    {
        return true;
    }

    /**
     * @return array<string, array<int, mixed>>
     */
    public function rules(): array
    {
        return $this->systemDetailRules();
    }

    /**
     * @return array<string, string>
     */
    public function messages(): array
    {
        return $this->systemDetailMessages();
    }
}

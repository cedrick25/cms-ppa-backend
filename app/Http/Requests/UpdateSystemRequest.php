<?php

namespace App\Http\Requests;

use App\Http\Requests\Concerns\ValidatesSystemDetails;
use App\Models\System;
use Illuminate\Foundation\Http\FormRequest;

class UpdateSystemRequest extends FormRequest
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
        $system = $this->route('system');

        return $this->systemDetailRules($system instanceof System ? $system->id : null);
    }

    /**
     * @return array<string, string>
     */
    public function messages(): array
    {
        return $this->systemDetailMessages();
    }
}

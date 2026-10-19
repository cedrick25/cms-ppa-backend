<?php

namespace App\Http\Resources;

use App\Models\SignInEvent;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * One recent sign-in: who and when. No token, session, or network details.
 *
 * @mixin SignInEvent
 */
class SignInEventResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'name' => $this->user->name,
            'signed_in_at' => $this->signed_in_at->toIso8601String(),
        ];
    }
}

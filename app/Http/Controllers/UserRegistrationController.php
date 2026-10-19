<?php

namespace App\Http\Controllers;

use App\Enums\CmsAuditAction;
use App\Http\Requests\RegisterUserRequest;
use App\Models\CmsAuditEvent;
use App\Models\User;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\Log;
use Illuminate\Validation\ValidationException;

class UserRegistrationController extends Controller
{
    public function store(RegisterUserRequest $request): JsonResponse
    {
        $data = $request->safe()->only([...RegisterUserRequest::accountDetailFields(), 'password']);

        try {
            $user = User::query()->create($data);
        } catch (UniqueConstraintViolationException) {
            throw ValidationException::withMessages(RegisterUserRequest::duplicateAccountErrors($data['username'], $data['email']));
        }

        Log::info('User account registered.', [
            'user_id' => $user->id,
            'username' => $user->username,
            'registered_by' => $request->user()?->username,
        ]);

        /** @var User $actor */
        $actor = $request->user();
        CmsAuditEvent::record(
            $actor,
            CmsAuditAction::UserRegistered,
            "Registered the account {$user->auditLabel()}.",
            $user->auditLabel(),
            $user,
        );

        $account = [
            'name' => $user->name,
            'first_name' => $user->first_name,
            'middle_name' => $user->middle_name,
            'last_name' => $user->last_name,
            'suffix' => $user->suffix,
            'username' => $user->username,
            'email' => $user->email,
            'role' => $user->role,
        ];

        return response()->json([
            'message' => 'Account created.',
            'user' => $account,
        ], 201);
    }
}

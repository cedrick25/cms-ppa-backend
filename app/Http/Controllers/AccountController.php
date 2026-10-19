<?php

namespace App\Http\Controllers;

use App\Http\Requests\UpdateOwnAccountRequest;
use App\Models\ApiToken;
use App\Models\User;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Gate;
use Illuminate\Support\Facades\Log;
use Illuminate\Validation\ValidationException;

class AccountController extends Controller
{
    public function show(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $request->user();

        return response()->json($this->accountDetails($user));
    }

    public function update(UpdateOwnAccountRequest $request): JsonResponse
    {
        /** @var User $user */
        $user = $request->user();
        $currentToken = $request->attributes->get('api_token');
        $changesPassword = $request->wantsNewPassword();

        $user->fill($request->safe()->only(UpdateOwnAccountRequest::ownAccountFields()));
        $changed = array_keys($user->getDirty());

        if ($changesPassword) {
            $user->forceFill(['password' => $request->validated('password')]);
        }

        try {
            $user->save();
        } catch (UniqueConstraintViolationException) {
            throw ValidationException::withMessages(
                UpdateOwnAccountRequest::duplicateAccountErrors($user->username, $user->email, $user->id),
            );
        }

        if ($changesPassword) {
            $user->endSessions($currentToken instanceof ApiToken ? $currentToken : null);
        }

        if ($changed !== [] || $changesPassword) {
            Log::info('User updated their own account.', [
                'user_id' => $user->id,
                'username' => $user->username,
                'changed' => $changed,
                'password_changed' => $changesPassword,
            ]);
        }

        return response()->json([
            'message' => $this->updateMessage($changed !== [], $changesPassword),
            'user' => $this->accountDetails($user),
        ]);
    }

    /**
     * @return array<string, mixed>
     */
    private function accountDetails(User $user): array
    {
        return [
            'id' => $user->id,
            'name' => $user->name,
            'first_name' => $user->first_name,
            'middle_name' => $user->middle_name,
            'last_name' => $user->last_name,
            'suffix' => $user->suffix,
            'username' => $user->username,
            'email' => $user->email,
            'role' => $user->role,
            'can' => [
                'manage_users' => Gate::forUser($user)->allows('manage-users'),
                'manage_systems' => Gate::forUser($user)->allows('manage-systems'),
                'view_audit_trail' => Gate::forUser($user)->allows('view-audit-trail'),
            ],
        ];
    }

    private function updateMessage(bool $detailsChanged, bool $passwordChanged): string
    {
        return match (true) {
            $detailsChanged && $passwordChanged => 'Your account details and password have been updated. Any other devices signed in to your account have been signed out.',
            $passwordChanged => 'Your password has been changed. Any other devices signed in to your account have been signed out.',
            $detailsChanged => 'Your account details have been updated.',
            default => 'No changes were needed.',
        };
    }
}

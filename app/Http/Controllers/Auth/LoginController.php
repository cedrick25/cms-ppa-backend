<?php

namespace App\Http\Controllers\Auth;

use App\Enums\CmsAuditAction;
use App\Http\Controllers\Controller;
use App\Http\Middleware\EnsureAccountIsActive;
use App\Http\Requests\LoginRequest;
use App\Models\ApiToken;
use App\Models\CmsAuditEvent;
use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\Gate;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\RateLimiter;

class LoginController extends Controller
{
    /**
     * Valid bcrypt hash used only so a missing account takes about as long
     * to reject as a wrong password. This is not a credential.
     */
    private const DUMMY_PASSWORD_HASH = '$2y$12$nnQ3Ny8bMZCwsNpwtzykdOZ1F9.3SOrNfBgdzHqSNlsxcjLdE6OOK';

    private const MAX_ATTEMPTS = 5;

    public function store(LoginRequest $request): JsonResponse
    {
        $username = $request->string('username')->toString();
        $password = $request->string('password')->toString();
        $key = $this->throttleKey($username, $request->ip());

        if (RateLimiter::tooManyAttempts($key, self::MAX_ATTEMPTS)) {
            return $this->failure($key);
        }

        $user = User::query()->where('username', $username)->first();
        $hash = $user->password ?? self::DUMMY_PASSWORD_HASH;
        $passwordMatches = Hash::check($password, $hash);

        if ($user === null || ! $passwordMatches) {
            RateLimiter::hit($key, 60);

            return response()->json(['message' => 'Incorrect username or password.'], 422);
        }

        if ($user->isDisabled()) {
            return response()->json(['message' => EnsureAccountIsActive::MESSAGE], 403);
        }

        RateLimiter::clear($key);

        $token = $user->createApiToken();
        $user->signInEvents()->create(['signed_in_at' => now()]);
        CmsAuditEvent::record($user, CmsAuditAction::SignedIn, 'Signed in.');

        return response()->json([
            'token' => $token,
            'token_type' => 'Bearer',
            'user' => $this->account($user),
        ]);
    }

    public function destroy(Request $request): JsonResponse
    {
        $user = $request->user();

        if ($user instanceof User) {
            CmsAuditEvent::record($user, CmsAuditAction::SignedOut, 'Signed out.');
        }

        $token = $request->attributes->get('api_token');

        if ($token instanceof ApiToken) {
            $token->delete();
        }

        Auth::guard('web')->logout();

        return response()->json(['message' => 'Signed out.']);
    }

    /**
     * @return array<string, mixed>
     */
    private function account(User $user): array
    {
        return [
            'id' => $user->id,
            'name' => $user->name,
            'username' => $user->username,
            'email' => $user->email,
            'role' => $user->role,
            'can' => [
                'manage_users' => Gate::forUser($user)->allows('manage-users'),
            ],
        ];
    }

    private function throttleKey(string $username, ?string $ip): string
    {
        return $username.'|'.($ip ?? 'unknown');
    }

    private function failure(string $key): JsonResponse
    {
        $seconds = max(1, RateLimiter::availableIn($key));

        return response()->json([
            'message' => "Too many sign-in attempts. Try again in {$seconds} seconds.",
        ], 429);
    }
}

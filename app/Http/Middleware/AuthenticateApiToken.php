<?php

namespace App\Http\Middleware;

use App\Models\ApiToken;
use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Symfony\Component\HttpFoundation\Response;

class AuthenticateApiToken
{
    public function handle(Request $request, Closure $next): Response
    {
        $plain = $request->bearerToken();

        if (is_string($plain) && $plain !== '') {
            $token = ApiToken::query()->where('token', hash('sha256', $plain))->first();
            $user = $token?->user;

            if ($token === null || $user === null) {
                return response()->json(['message' => 'Unauthenticated.'], 401);
            }

            if ($token->last_used_at === null || $token->last_used_at->lt(now()->subMinute())) {
                $token->forceFill(['last_used_at' => now()])->save();
            }

            Auth::setUser($user);
            $request->attributes->set('api_token', $token);

            return $next($request);
        }

        if ($request->user() !== null) {
            return $next($request);
        }

        return response()->json(['message' => 'Unauthenticated.'], 401);
    }
}

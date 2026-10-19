<?php

namespace App\Http\Middleware;

use App\Models\ApiToken;
use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Symfony\Component\HttpFoundation\Response;

class EnsureAccountIsActive
{
    public const MESSAGE = 'This account is disabled. Contact a CMS-PPA administrator.';

    public function handle(Request $request, Closure $next): Response
    {
        $user = $request->user();

        if ($user === null || ! $user->isDisabled()) {
            return $next($request);
        }

        $token = $request->attributes->get('api_token');

        if ($token instanceof ApiToken) {
            $token->delete();
        } else {
            $user->tokens()->delete();
        }

        Auth::guard('web')->logout();

        if ($request->hasSession()) {
            $request->session()->invalidate();
            $request->session()->regenerateToken();
        }

        return response()->json(['message' => self::MESSAGE], 401);
    }
}

<?php

use App\Http\Middleware\AuthenticateApiToken;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;
use Illuminate\Routing\Middleware\ThrottleRequests;
use Illuminate\Support\Env;

/*
 * Apache on Windows runs PHP in one process with many threads. putenv() is
 * shared by every thread, so a refresh that fires several API calls at once
 * can wipe APP_KEY or DB_* for some of them and those requests return 500.
 * Keep .env values in the request-local $_ENV and $_SERVER arrays instead.
 */
Env::disablePutenv();

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        web: __DIR__.'/../routes/web.php',
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware): void {
        $middleware->alias([
            'auth.api' => AuthenticateApiToken::class,
        ]);

        $middleware->prependToPriorityList(before: ThrottleRequests::class, prepend: AuthenticateApiToken::class);
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        $exceptions->shouldRenderJsonWhen(
            fn (Request $request) => $request->is('api/*') || $request->expectsJson(),
        );
    })->create();

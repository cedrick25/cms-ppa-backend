<?php

namespace App\Providers;

use App\Contracts\AuditTrailReader;
use App\Models\User;
use App\Services\DatabaseAuditTrailReader;
use Illuminate\Auth\Access\Response;
use Illuminate\Support\Facades\Gate;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    /**
     * Register any application services.
     */
    public function register(): void
    {
        $this->app->bind(AuditTrailReader::class, DatabaseAuditTrailReader::class);
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        Gate::define('manage-users', fn (User $user): bool => ! $user->isDisabled() && $user->isAdministrator());

        Gate::define('manage-systems', fn (User $user): bool => ! $user->isDisabled());

        Gate::define('view-system-status', fn (User $user): bool => ! $user->isDisabled());

        Gate::define('view-private-addresses', fn (User $user): bool => Gate::forUser($user)->allows('manage-systems'));

        Gate::define('view-sign-in-activity', fn (User $user): bool => ! $user->isDisabled() && $user->isAdministrator());

        Gate::define('view-audit-trail', fn (User $user): bool => ! $user->isDisabled() && $user->isAdministrator());

        $this->registerAuditConnections();

        Gate::define('reset-password', function (User $user, User $target): Response {
            if (Gate::forUser($user)->denies('manage-users')) {
                return Response::deny("Your account isn't allowed to set passwords for other users.");
            }

            if ($user->is($target)) {
                return Response::deny('To change your own password, use Change password and enter your current password.');
            }

            if ($target->isAdministrator()) {
                return Response::deny("Administrators manage their own passwords. You can't set a password for another administrator.");
            }

            return Response::allow();
        });
    }

    /**
     * Separate connections so a read of UAMS, CMIS, or PPIS never uses the CMS database by accident.
     */
    private function registerAuditConnections(): void
    {
        /** @var array<string, mixed> $mysql */
        $mysql = config('database.connections.mysql');
        /** @var array<string, array<string, mixed>> $sources */
        $sources = config('cms.audit_trail.sources', []);

        foreach ($sources as $source) {
            $database = trim((string) ($source['database'] ?? ''));
            $connection = (string) ($source['connection'] ?? '');

            if ($database === '' || $connection === '') {
                continue;
            }

            config([
                "database.connections.{$connection}" => [
                    ...$mysql,
                    'url' => null,
                    'host' => $source['host'] ?? $mysql['host'],
                    'port' => $source['port'] ?? $mysql['port'],
                    'database' => $database,
                    'username' => $source['username'] ?? $mysql['username'],
                    'password' => $source['password'] ?? $mysql['password'],
                ],
            ]);
        }
    }
}

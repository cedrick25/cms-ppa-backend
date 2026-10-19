<?php

namespace App\Models;

// use Illuminate\Contracts\Auth\MustVerifyEmail;
use App\Enums\Role;
use Database\Factories\UserFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Attributes\Hidden;
use Illuminate\Database\Eloquent\Collection;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Gate;
use Illuminate\Support\Str;

#[Fillable(['name', 'first_name', 'middle_name', 'last_name', 'suffix', 'username', 'email', 'role', 'password'])]
#[Hidden(['password', 'remember_token'])]
class User extends Authenticatable
{
    /** @use HasFactory<UserFactory> */
    use HasFactory, Notifiable;

    /**
     * Get the attributes that should be cast.
     *
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'email_verified_at' => 'datetime',
            'disabled_at' => 'datetime',
            'password' => 'hashed',
            'role' => Role::class,
        ];
    }

    public function isDisabled(): bool
    {
        return $this->disabled_at !== null;
    }

    public function isAdministrator(): bool
    {
        return $this->role === Role::Administrator;
    }

    /**
     * Name and username kept on the CMS audit trail after this account is removed.
     */
    public function auditLabel(): string
    {
        return "{$this->name} ({$this->username})";
    }

    /**
     * Active accounts the given gate allows, so a notification only reaches people who may see what it describes.
     *
     * @return Collection<int, User>
     */
    public static function activeAccountsAllowedTo(string $ability, ?User $except = null): Collection
    {
        return static::query()
            ->whereNull('disabled_at')
            ->when($except !== null, fn ($query) => $query->whereKeyNot($except->getKey()))
            ->get()
            ->filter(fn (User $user): bool => Gate::forUser($user)->allows($ability))
            ->values();
    }

    /**
     * Sign the account out everywhere, optionally keeping the token making the current request.
     */
    public function endSessions(?ApiToken $except = null): void
    {
        $this->tokens()
            ->when($except !== null, fn ($query) => $query->whereKeyNot($except->getKey()))
            ->delete();

        if (config('session.driver') === 'database') {
            DB::table(config('session.table', 'sessions'))->where('user_id', $this->id)->delete();
        }
    }

    /**
     * @return HasMany<ApiToken, $this>
     */
    public function tokens(): HasMany
    {
        return $this->hasMany(ApiToken::class);
    }

    /**
     * @return HasMany<SignInEvent, $this>
     */
    public function signInEvents(): HasMany
    {
        return $this->hasMany(SignInEvent::class);
    }

    public function createApiToken(): string
    {
        $plain = Str::random(80);

        $this->tokens()->create([
            'name' => 'cms-ppa',
            'token' => hash('sha256', $plain),
        ]);

        return $plain;
    }
}

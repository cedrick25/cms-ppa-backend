<?php

namespace App\Http\Controllers;

use App\Enums\CmsAuditAction;
use App\Http\Requests\ResetUserPasswordRequest;
use App\Http\Requests\UpdateUserRequest;
use App\Http\Resources\UserResource;
use App\Models\CmsAuditEvent;
use App\Models\User;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;
use Illuminate\Validation\Rule;
use Illuminate\Validation\ValidationException;

class UserController extends Controller
{
    private const PER_PAGE = 20;

    public function index(Request $request): JsonResponse
    {
        $filters = $request->validate([
            'search' => ['nullable', 'string', 'max:100'],
            'status' => ['nullable', Rule::in(['all', 'active', 'disabled'])],
            'page' => ['nullable', 'integer', 'min:1'],
        ]);

        $search = trim($filters['search'] ?? '');
        $status = $filters['status'] ?? 'all';

        $matching = fn (): Builder => User::query()->when($search !== '', function (Builder $query) use ($search) {
            $query->where(function (Builder $query) use ($search) {
                $query->where('name', 'like', "%{$search}%")
                    ->orWhere('username', 'like', "%{$search}%")
                    ->orWhere('email', 'like', "%{$search}%");
            });
        });

        $page = $matching()
            ->when($status === 'active', fn (Builder $query) => $query->whereNull('disabled_at'))
            ->when($status === 'disabled', fn (Builder $query) => $query->whereNotNull('disabled_at'))
            ->orderByDesc('created_at')
            ->orderByDesc('id')
            ->paginate(self::PER_PAGE);

        return response()->json([
            'data' => UserResource::collection($page->getCollection())->resolve($request),
            'meta' => [
                'current_page' => $page->currentPage(),
                'last_page' => $page->lastPage(),
                'per_page' => $page->perPage(),
                'total' => $page->total(),
                'from' => $page->firstItem(),
                'to' => $page->lastItem(),
                'total_registered' => User::query()->count(),
            ],
            'counts' => [
                'all' => $matching()->count(),
                'active' => $matching()->whereNull('disabled_at')->count(),
                'disabled' => $matching()->whereNotNull('disabled_at')->count(),
            ],
        ]);
    }

    public function update(UpdateUserRequest $request, User $user): JsonResponse
    {
        $user->fill($request->safe()->only(UpdateUserRequest::accountDetailFields()));
        $changed = array_keys($user->getDirty());

        try {
            $user->save();
        } catch (UniqueConstraintViolationException) {
            throw ValidationException::withMessages(
                UpdateUserRequest::duplicateAccountErrors($user->username, $user->email, $user->id),
            );
        }

        if ($changed !== []) {
            $this->audit('User account updated.', $request, $user, ['changed' => $changed]);
            $this->recordCmsAudit($request, CmsAuditAction::UserUpdated, "Updated the account {$user->auditLabel()}.", $user, ['changed' => $changed]);
        }

        $name = rtrim($user->name, '.');

        return $this->accountResponse($request, $user, $changed === []
            ? "No changes were needed for {$name}."
            : "Changes saved for {$name}.");
    }

    public function disable(Request $request, User $user): JsonResponse
    {
        $this->guardAgainstSelf($request, $user, "You can't disable your own account.");

        if (! $user->isDisabled()) {
            $user->forceFill(['disabled_at' => now()])->save();
            $user->endSessions();
            $this->audit('User account disabled.', $request, $user);
            $this->recordCmsAudit($request, CmsAuditAction::UserDisabled, "Disabled the account {$user->auditLabel()}.", $user);
        }

        return $this->accountResponse($request, $user, "{$user->name}'s account is disabled. They can no longer sign in.");
    }

    public function resetPassword(ResetUserPasswordRequest $request, User $user): JsonResponse
    {
        DB::transaction(function () use ($request, $user) {
            $user->forceFill(['password' => $request->validated('password')])->save();
            $user->endSessions();
            DB::table('password_reset_tokens')->where('email', $user->email)->delete();
        });

        $this->audit('User password reset by administrator.', $request, $user);

        return $this->accountResponse($request, $user, "New password set for {$user->name}. They have been signed out and must sign in with the new password.");
    }

    public function enable(Request $request, User $user): JsonResponse
    {
        if ($user->isDisabled()) {
            $user->forceFill(['disabled_at' => null])->save();
            $this->audit('User account enabled.', $request, $user);
        }

        return $this->accountResponse($request, $user, "{$user->name}'s account is active again. They can sign in with their existing password.");
    }

    public function destroy(Request $request, User $user): JsonResponse
    {
        $this->guardAgainstSelf($request, $user, "You can't remove your own account.");

        $request->validate(
            ['confirm_username' => ['required', 'string']],
            ['confirm_username.required' => 'Type the username to confirm removal.'],
        );

        if (strtolower(trim($request->string('confirm_username')->toString())) !== $user->username) {
            throw ValidationException::withMessages([
                'confirm_username' => 'The username you typed does not match this account.',
            ]);
        }

        $this->audit('User account removed.', $request, $user, ['email' => $user->email]);
        $this->recordCmsAudit($request, CmsAuditAction::UserRemoved, "Removed the account {$user->auditLabel()}.", $user);

        DB::transaction(function () use ($user) {
            $user->endSessions();
            DB::table('password_reset_tokens')->where('email', $user->email)->delete();
            $user->notifications()->delete();
            $user->delete();
        });

        return response()->json([
            'message' => "Account removed: {$user->name} ({$user->username}).",
        ]);
    }

    private function guardAgainstSelf(Request $request, User $user, string $message): void
    {
        if ($request->user()?->is($user)) {
            throw ValidationException::withMessages(['account' => $message]);
        }
    }

    /**
     * @param  array<string, mixed>  $context
     */
    private function audit(string $event, Request $request, User $user, array $context = []): void
    {
        Log::info($event, [
            'user_id' => $user->id,
            'username' => $user->username,
            'performed_by' => $request->user()?->username,
        ] + $context);
    }

    /**
     * @param  array<string, mixed>  $details
     */
    private function recordCmsAudit(Request $request, CmsAuditAction $action, string $summary, User $user, array $details = []): void
    {
        $actor = $request->user();

        if (! $actor instanceof User) {
            return;
        }

        CmsAuditEvent::record($actor, $action, $summary, $user->auditLabel(), $user, $details);
    }

    private function accountResponse(Request $request, User $user, string $message): JsonResponse
    {
        return response()->json([
            'message' => $message,
            'user' => (new UserResource($user))->resolve($request),
        ]);
    }
}

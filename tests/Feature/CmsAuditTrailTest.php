<?php

namespace Tests\Feature;

use App\Enums\CmsAuditAction;
use App\Enums\CmsAuditModule;
use App\Models\CmsAuditEvent;
use App\Models\System;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Tests\TestCase;

class CmsAuditTrailTest extends TestCase
{
    use RefreshDatabase;

    private const PASSWORD = 'Harbor-Lights-2026';

    private const SECRET = 'AnyDesk-Secret-9';

    public function test_guests_and_other_roles_cannot_read_the_cms_audit_trail(): void
    {
        $this->getJson('/api/cms-audit-trail')->assertUnauthorized();

        $this->actingAs(User::factory()->create())
            ->getJson('/api/cms-audit-trail')
            ->assertForbidden();
    }

    public function test_an_empty_trail_returns_an_empty_list(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/cms-audit-trail')
            ->assertOk()
            ->assertJsonPath('meta.total_recorded', 0)
            ->assertJsonCount(0, 'data')
            ->assertJsonPath('counts', ['all' => 0, 'session' => 0, 'credentials' => 0, 'users' => 0]);
    }

    public function test_a_successful_sign_in_and_sign_out_are_recorded_and_a_failed_sign_in_is_not(): void
    {
        $user = User::factory()->create([
            'name' => 'Ada Lovelace',
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ]);

        $this->postJson('/api/sign-in', [
            'username' => 'ada',
            'password' => 'wrong-password',
        ])->assertUnprocessable();

        $this->assertDatabaseCount('cms_audit_events', 0);

        $token = $this->postJson('/api/sign-in', [
            'username' => 'ada',
            'password' => 'Correct-Horse-12',
        ])->assertOk()->json('token');

        $this->assertDatabaseHas('cms_audit_events', [
            'actor_id' => $user->id,
            'actor_name' => 'Ada Lovelace',
            'actor_username' => 'ada',
            'module' => CmsAuditModule::Session->value,
            'action' => CmsAuditAction::SignedIn->value,
            'summary' => 'Signed in.',
        ]);

        $this->withToken($token)->postJson('/api/sign-out')
            ->assertOk()
            ->assertJsonPath('message', 'Signed out.');

        $this->assertDatabaseHas('cms_audit_events', [
            'actor_id' => $user->id,
            'action' => CmsAuditAction::SignedOut->value,
            'summary' => 'Signed out.',
        ]);
        $this->assertDatabaseCount('cms_audit_events', 2);
    }

    public function test_credential_changes_and_remote_desk_opens_are_recorded_without_secrets(): void
    {
        $existing = System::factory()->create(['anydesk_id' => '111222333']);
        $this->postJson("/api/systems/{$existing->id}/remote-desks", ['type' => 'anydesk'])
            ->assertUnauthorized();
        $this->actingAs(User::factory()->disabled()->create())
            ->postJson("/api/systems/{$existing->id}/remote-desks", ['type' => 'anydesk'])
            ->assertUnauthorized()
            ->assertJsonPath('message', 'This account is disabled. Contact a CMS-PPA administrator.');

        $admin = User::factory()->administrator()->create([
            'name' => 'Grace Hopper',
            'username' => 'grace',
        ]);

        $created = $this->actingAs($admin)->postJson('/api/systems', [
            'name' => 'PPIS PROD',
            'anydesk_id' => '123456789',
            'anydesk_password' => self::SECRET,
        ])->assertCreated();

        $systemId = $created->json('system.id');
        $this->assertIsInt($systemId);

        $beforeView = CmsAuditEvent::query()->count();
        $this->actingAs($admin)->getJson("/api/systems/{$systemId}")->assertOk();
        $this->assertSame($beforeView, CmsAuditEvent::query()->count());

        $this->actingAs($admin)->patchJson("/api/systems/{$systemId}", [
            'name' => 'PPIS PROD',
            'description' => 'Production probation system',
        ])->assertOk();

        $this->actingAs($admin)->postJson("/api/systems/{$systemId}/remote-desks", [])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['type' => 'Choose a remote desk to open.']);

        $this->actingAs($admin)->postJson("/api/systems/{$systemId}/remote-desks", ['type' => 'payroll'])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['type' => 'Choose AnyDesk, RustDesk, RDP, or SSH.']);

        $this->actingAs($admin)->postJson("/api/systems/{$systemId}/remote-desks", ['type' => 'ssh'])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['type' => 'This system has no SSH remote desk.']);

        $reader = User::factory()->create(['name' => 'Ben Cruz', 'username' => 'bcruz']);
        $this->actingAs($reader)->postJson("/api/systems/{$systemId}/remote-desks", ['type' => 'anydesk'])
            ->assertOk()
            ->assertJsonPath('message', 'Opened the AnyDesk remote desk for PPIS PROD.');

        $this->actingAs($admin)->deleteJson("/api/systems/{$systemId}")->assertOk();

        $this->assertDatabaseHas('cms_audit_events', [
            'actor_username' => 'grace',
            'action' => CmsAuditAction::CredentialsRegistered->value,
            'subject_type' => 'system',
            'subject_id' => $systemId,
            'subject_label' => 'PPIS PROD',
            'summary' => 'Registered credentials for PPIS PROD.',
        ]);
        $this->assertDatabaseHas('cms_audit_events', [
            'action' => CmsAuditAction::CredentialsUpdated->value,
            'summary' => 'Updated credentials for PPIS PROD.',
        ]);
        $this->assertDatabaseHas('cms_audit_events', [
            'actor_username' => 'bcruz',
            'action' => CmsAuditAction::RemoteDeskOpened->value,
            'summary' => 'Opened the AnyDesk remote desk for PPIS PROD.',
        ]);
        $this->assertDatabaseHas('cms_audit_events', [
            'action' => CmsAuditAction::CredentialsRemoved->value,
            'summary' => 'Removed credentials for PPIS PROD.',
        ]);

        $updated = CmsAuditEvent::query()->where('action', CmsAuditAction::CredentialsUpdated)->firstOrFail();
        $this->assertSame(['description'], $updated->details['changed']);

        $opened = CmsAuditEvent::query()->where('action', CmsAuditAction::RemoteDeskOpened)->firstOrFail();
        $this->assertSame('AnyDesk', $opened->details['remote_desk']);

        $stored = CmsAuditEvent::query()->get()->toJson();
        $this->assertStringNotContainsString(self::SECRET, $stored);
        $this->assertStringNotContainsString('123456789', $stored);
    }

    public function test_extra_detail_values_are_not_stored(): void
    {
        $event = CmsAuditEvent::record(
            User::factory()->create(),
            CmsAuditAction::CredentialsUpdated,
            'Updated credentials for PPIS PROD.',
            'PPIS PROD',
            null,
            [
                'changed' => ['description', 'anydesk_password'],
                'anydesk_password' => self::SECRET,
                'anydesk_id' => '123456789',
            ],
        );

        $this->assertSame(['changed' => ['description', 'anydesk_password']], $event->details);
    }

    public function test_user_account_changes_stay_in_the_trail_after_the_account_is_removed(): void
    {
        $admin = User::factory()->administrator()->create([
            'name' => 'Grace Hopper',
            'username' => 'grace',
        ]);

        $this->actingAs($admin)->postJson('/api/users', [
            'first_name' => 'Maria',
            'last_name' => 'Santos',
            'email' => 'maria.santos@example.com',
            'username' => 'msantos',
            'role' => 'user',
            'password' => self::PASSWORD,
            'password_confirmation' => self::PASSWORD,
        ])->assertCreated();

        $maria = User::query()->where('username', 'msantos')->firstOrFail();
        $this->postJson('/api/sign-in', [
            'username' => 'msantos',
            'password' => self::PASSWORD,
        ])->assertOk();

        $this->actingAs($admin)->patchJson("/api/users/{$maria->id}", [
            'first_name' => 'Marie',
            'last_name' => 'Santos',
            'email' => 'maria.santos@example.com',
            'username' => 'msantos',
            'role' => 'user',
        ])->assertOk();

        $this->actingAs($admin)->postJson("/api/users/{$maria->id}/disable")->assertOk();
        $this->actingAs($admin)->deleteJson("/api/users/{$maria->id}", [
            'confirm_username' => 'msantos',
        ])->assertOk();

        $this->assertDatabaseMissing('users', ['id' => $maria->id]);
        $this->assertDatabaseHas('cms_audit_events', [
            'actor_username' => 'grace',
            'action' => CmsAuditAction::UserRegistered->value,
            'subject_label' => 'Maria Santos (msantos)',
            'summary' => 'Registered the account Maria Santos (msantos).',
        ]);
        $this->assertDatabaseHas('cms_audit_events', [
            'action' => CmsAuditAction::UserUpdated->value,
            'subject_label' => 'Marie Santos (msantos)',
            'summary' => 'Updated the account Marie Santos (msantos).',
        ]);
        $this->assertDatabaseHas('cms_audit_events', [
            'action' => CmsAuditAction::UserDisabled->value,
            'summary' => 'Disabled the account Marie Santos (msantos).',
        ]);
        $this->assertDatabaseHas('cms_audit_events', [
            'action' => CmsAuditAction::UserRemoved->value,
            'summary' => 'Removed the account Marie Santos (msantos).',
        ]);

        $signIn = CmsAuditEvent::query()->where('action', CmsAuditAction::SignedIn)->firstOrFail();
        $this->assertNull($signIn->actor_id);
        $this->assertSame('Maria Santos', $signIn->actor_name);
        $this->assertSame('msantos', $signIn->actor_username);

        $stored = CmsAuditEvent::query()->get()->toJson();
        $this->assertStringNotContainsString(self::PASSWORD, $stored);
    }

    public function test_saved_events_are_newest_first_and_can_be_filtered(): void
    {
        config(['cms.cms_audit_trail.per_page' => 1]);
        $older = CmsAuditEvent::factory()->create([
            'occurred_at' => Carbon::parse('2026-10-15 00:30:00', 'Asia/Manila')->utc(),
            'actor_name' => 'Grace Hopper',
            'module' => CmsAuditModule::Users,
            'action' => CmsAuditAction::UserUpdated,
            'summary' => 'Updated the account 100% done.',
        ]);
        CmsAuditEvent::factory()->create([
            'occurred_at' => Carbon::parse('2026-10-14 23:00:00', 'Asia/Manila')->utc(),
            'module' => CmsAuditModule::Credentials,
            'action' => CmsAuditAction::CredentialsRegistered,
            'summary' => 'Registered credentials for the night shift.',
        ]);
        $newer = CmsAuditEvent::factory()->create([
            'occurred_at' => Carbon::parse('2026-10-15 08:00:00', 'Asia/Manila')->utc(),
            'actor_name' => 'Ada Lovelace',
            'module' => CmsAuditModule::Session,
            'action' => CmsAuditAction::SignedIn,
            'summary' => 'Signed in.',
        ]);

        $administrator = User::factory()->administrator()->create();
        $response = $this->actingAs($administrator)
            ->getJson('/api/cms-audit-trail')
            ->assertOk()
            ->assertJsonPath('data.0.id', $newer->id)
            ->assertJsonPath('data.0.module_label', 'Sign-in')
            ->assertJsonPath('data.0.action_label', 'Signed in')
            ->assertJsonPath('meta.total', 3);

        $this->assertSame(
            ['id', 'occurred_at', 'actor_name', 'actor_username', 'module', 'module_label', 'action', 'action_label', 'subject_label', 'summary', 'remote_desk', 'changed'],
            array_keys($response->json('data.0')),
        );

        $this->actingAs($administrator)
            ->getJson('/api/cms-audit-trail?module=users&from=2026-10-15&to=2026-10-15&page=1')
            ->assertOk()
            ->assertJsonPath('data.0.id', $older->id)
            ->assertJsonPath('counts.all', 2)
            ->assertJsonPath('counts.users', 1)
            ->assertJsonPath('counts.session', 1)
            ->assertJsonPath('counts.credentials', 0);

        $this->actingAs($administrator)
            ->getJson('/api/cms-audit-trail?search='.urlencode('%'))
            ->assertOk()
            ->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.summary', 'Updated the account 100% done.');
    }

    public function test_an_unknown_module_returns_422(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/cms-audit-trail?module=payroll')
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['module' => 'The selected module is invalid.']);
    }

    public function test_a_page_below_1_returns_422(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/cms-audit-trail?page=0')
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['page' => 'The page field must be at least 1.']);
    }

    public function test_an_end_date_before_the_start_date_returns_422(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/cms-audit-trail?from=2026-10-16&to=2026-10-15')
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['to' => 'The to field must be a date after or equal to from.']);
    }
}

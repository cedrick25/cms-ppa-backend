<?php

namespace Tests\Feature;

use App\Enums\CmsAuditAction;
use App\Models\CmsAuditEvent;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Tests\TestCase;

class DashboardActivityTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();

        config(['cms.display_timezone' => 'Asia/Manila']);
    }

    public function test_guests_and_other_roles_cannot_read_dashboard_activity(): void
    {
        $this->getJson('/api/dashboard/activity?series=remote_desks')
            ->assertUnauthorized();

        $this->actingAs(User::factory()->create())
            ->getJson('/api/dashboard/activity?series=remote_desks')
            ->assertForbidden();
    }

    public function test_the_current_week_counts_remote_desk_opens_on_manila_days(): void
    {
        $this->travelTo(Carbon::parse('2026-10-28 04:00:00', 'UTC'));
        $admin = User::factory()->administrator()->create();

        $this->activityAt('2026-10-25 17:30:00', CmsAuditAction::RemoteDeskOpened);
        $this->activityAt('2026-10-28 03:00:00', CmsAuditAction::RemoteDeskOpened);
        $this->activityAt('2026-10-28 03:00:00', CmsAuditAction::CredentialsUpdated);
        $this->activityAt('2026-10-28 03:00:00', CmsAuditAction::SignedIn);
        $this->activityAt('2026-10-21 02:00:00', CmsAuditAction::RemoteDeskOpened);

        $this->actingAs($admin)
            ->getJson('/api/dashboard/activity?series=remote_desks')
            ->assertOk()
            ->assertJsonPath('meta.series', 'remote_desks')
            ->assertJsonPath('meta.series_label', 'Remote desks opened')
            ->assertJsonPath('meta.range', 'current_week')
            ->assertJsonPath('meta.range_label', 'This week')
            ->assertJsonPath('meta.from', '2026-10-26')
            ->assertJsonPath('meta.to', '2026-10-28')
            ->assertJsonPath('meta.total', 2)
            ->assertJsonPath('meta.timezone', 'Asia/Manila')
            ->assertJsonPath('data', [
                ['date' => '2026-10-26', 'label' => 'Oct 26', 'count' => 1],
                ['date' => '2026-10-27', 'label' => 'Oct 27', 'count' => 0],
                ['date' => '2026-10-28', 'label' => 'Oct 28', 'count' => 1],
            ]);
    }

    public function test_credential_and_account_series_count_only_their_own_actions(): void
    {
        $this->travelTo(Carbon::parse('2026-10-28 04:00:00', 'UTC'));
        $admin = User::factory()->administrator()->create();

        $this->activityAt('2026-10-28 01:00:00', CmsAuditAction::CredentialsRegistered);
        $this->activityAt('2026-10-28 02:00:00', CmsAuditAction::CredentialsRemoved);
        $this->activityAt('2026-10-28 02:30:00', CmsAuditAction::RemoteDeskOpened);
        $this->activityAt('2026-10-26 01:00:00', CmsAuditAction::UserDisabled);
        $this->activityAt('2026-10-26 02:00:00', CmsAuditAction::UserRegistered);

        $this->actingAs($admin)
            ->getJson('/api/dashboard/activity?series=credential_changes&range=current_week')
            ->assertOk()
            ->assertJsonPath('meta.series_label', 'Credential changes')
            ->assertJsonPath('meta.total', 2)
            ->assertJsonPath('data.2.count', 2);

        $this->actingAs($admin)
            ->getJson('/api/dashboard/activity?series=account_changes&range=current_week')
            ->assertOk()
            ->assertJsonPath('meta.series_label', 'Account changes')
            ->assertJsonPath('meta.total', 2)
            ->assertJsonPath('data.0.count', 2)
            ->assertJsonPath('data.2.count', 0);
    }

    public function test_last_week_is_the_previous_monday_through_sunday(): void
    {
        $this->travelTo(Carbon::parse('2026-10-28 04:00:00', 'UTC'));
        $admin = User::factory()->administrator()->create();

        $this->activityAt('2026-10-25 02:00:00', CmsAuditAction::RemoteDeskOpened);
        $this->activityAt('2026-10-25 17:30:00', CmsAuditAction::RemoteDeskOpened);
        $this->activityAt('2026-10-18 15:00:00', CmsAuditAction::RemoteDeskOpened);

        $response = $this->actingAs($admin)
            ->getJson('/api/dashboard/activity?series=remote_desks&range=last_week')
            ->assertOk()
            ->assertJsonPath('meta.range', 'last_week')
            ->assertJsonPath('meta.range_label', 'Last week')
            ->assertJsonPath('meta.from', '2026-10-19')
            ->assertJsonPath('meta.to', '2026-10-25')
            ->assertJsonCount(7, 'data')
            ->assertJsonPath('data.0.date', '2026-10-19')
            ->assertJsonPath('data.6.date', '2026-10-25')
            ->assertJsonPath('data.6.count', 1)
            ->assertJsonPath('meta.total', 1);

        $this->assertSame(
            ['date', 'label', 'count'],
            array_keys($response->json('data.0')),
        );
    }

    public function test_the_last_30_days_include_today_and_exclude_the_day_before_the_window(): void
    {
        $this->travelTo(Carbon::parse('2026-10-28 04:00:00', 'UTC'));
        $admin = User::factory()->administrator()->create();

        $this->activityAt('2026-09-28 16:30:00', CmsAuditAction::UserUpdated);
        $this->activityAt('2026-09-28 15:00:00', CmsAuditAction::UserUpdated);

        $this->actingAs($admin)
            ->getJson('/api/dashboard/activity?series=account_changes&range=last_30_days')
            ->assertOk()
            ->assertJsonPath('meta.range', 'last_30_days')
            ->assertJsonPath('meta.from', '2026-09-29')
            ->assertJsonPath('meta.to', '2026-10-28')
            ->assertJsonCount(30, 'data')
            ->assertJsonPath('data.0', ['date' => '2026-09-29', 'label' => 'Sep 29', 'count' => 1])
            ->assertJsonPath('data.29.date', '2026-10-28')
            ->assertJsonPath('meta.total', 1);
    }

    public function test_an_unknown_series_or_range_is_rejected(): void
    {
        $admin = User::factory()->administrator()->create();

        $this->actingAs($admin)
            ->getJson('/api/dashboard/activity')
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['series' => 'The series field is required.']);

        $this->actingAs($admin)
            ->getJson('/api/dashboard/activity?series=sign_ins&range=year')
            ->assertUnprocessable()
            ->assertJsonValidationErrors([
                'series' => 'The selected series is invalid.',
                'range' => 'The selected range is invalid.',
            ]);
    }

    private function activityAt(string $utc, CmsAuditAction $action): void
    {
        CmsAuditEvent::factory()->create([
            'occurred_at' => Carbon::parse($utc, 'UTC'),
            'action' => $action,
        ]);
    }
}

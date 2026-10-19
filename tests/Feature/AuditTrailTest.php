<?php

namespace Tests\Feature;

use App\Contracts\AuditTrailReader;
use App\Enums\AuditSource;
use App\Models\AuditRecord;
use App\Models\User;
use App\Services\AuditSnapshot;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\Gate;
use Mockery;
use Tests\TestCase;

class AuditTrailTest extends TestCase
{
    use RefreshDatabase;

    public function test_guests_receive_401_and_other_roles_receive_403(): void
    {
        $this->getJson('/api/audit-trail')->assertUnauthorized();
        $this->postJson('/api/audit-trail/refresh')->assertUnauthorized();

        $user = User::factory()->create();

        $this->actingAs($user)->getJson('/api/audit-trail')->assertForbidden();
        $this->actingAs($user)->postJson('/api/audit-trail/refresh')->assertForbidden();
    }

    public function test_only_active_administrators_may_view_the_audit_trail(): void
    {
        $administrator = User::factory()->administrator()->create();
        $disabledAdministrator = User::factory()->administrator()->disabled()->create();
        $user = User::factory()->create();

        $this->assertTrue(Gate::forUser($administrator)->allows('view-audit-trail'));
        $this->assertFalse(Gate::forUser($disabledAdministrator)->allows('view-audit-trail'));
        $this->assertFalse(Gate::forUser($user)->allows('view-audit-trail'));

        $this->actingAs($administrator)->getJson('/api/user')->assertJsonPath('can.view_audit_trail', true);
        $this->actingAs($user)->getJson('/api/user')->assertJsonPath('can.view_audit_trail', false);
    }

    public function test_an_empty_copy_returns_an_empty_list_without_reading_source_systems(): void
    {
        $reader = Mockery::mock(AuditTrailReader::class);
        $reader->shouldNotReceive('read');
        $this->app->instance(AuditTrailReader::class, $reader);

        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/audit-trail')
            ->assertOk()
            ->assertJsonPath('meta.reads', 'saved_records')
            ->assertJsonPath('meta.total_saved', 0)
            ->assertJsonCount(0, 'data')
            ->assertJsonPath('counts', ['all' => 0, 'uams' => 0, 'cmis' => 0, 'ppis' => 0]);
    }

    public function test_saved_records_are_newest_first_and_expose_only_the_audit_fields(): void
    {
        $older = AuditRecord::factory()->create([
            'source' => AuditSource::Cmis,
            'external_id' => 4,
            'occurred_at' => Carbon::parse('2026-10-15 09:00:00', 'UTC'),
            'actor_name' => 'Grace Hopper',
            'action' => 'Closed a docket',
        ]);
        $newer = AuditRecord::factory()->fromSource(AuditSource::Uams)->create([
            'external_id' => 9,
            'occurred_at' => Carbon::parse('2026-10-16 09:00:00', 'UTC'),
            'actor_name' => 'Ada Lovelace',
            'action' => 'Updated a user',
        ]);

        $response = $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/audit-trail')
            ->assertOk()
            ->assertJsonPath('data.*.id', [$newer->id, $older->id])
            ->assertJsonPath('data.0.source_label', 'UAMS')
            ->assertJsonPath('counts.uams', 1)
            ->assertJsonPath('counts.cmis', 1)
            ->assertJsonPath('counts.all', 2);

        $this->assertSame(
            ['id', 'source', 'source_label', 'external_id', 'occurred_at', 'actor_name', 'user_level', 'field_office', 'module', 'action'],
            array_keys($response->json('data.0')),
        );
        $this->assertStringNotContainsString('ip', strtolower(implode(',', array_keys($response->json('data.0')))));
    }

    public function test_filters_limit_the_page_and_a_percent_sign_matches_only_that_character(): void
    {
        config(['cms.audit_trail.per_page' => 1]);
        AuditRecord::factory()->create([
            'source' => AuditSource::Uams,
            'occurred_at' => Carbon::parse('2026-10-15 00:30:00', 'Asia/Manila')->utc(),
            'action' => '100% done',
        ]);
        AuditRecord::factory()->fromSource(AuditSource::Cmis)->create([
            'occurred_at' => Carbon::parse('2026-10-14 23:00:00', 'Asia/Manila')->utc(),
            'action' => 'Closed the night',
        ]);
        AuditRecord::factory()->create([
            'source' => AuditSource::Uams,
            'occurred_at' => Carbon::parse('2026-10-15 08:00:00', 'Asia/Manila')->utc(),
            'action' => 'Opened the queue',
        ]);

        $administrator = User::factory()->administrator()->create();

        $this->actingAs($administrator)
            ->getJson('/api/audit-trail?source=uams&from=2026-10-15&to=2026-10-15&page=2')
            ->assertOk()
            ->assertJsonPath('data.0.action', '100% done')
            ->assertJsonPath('meta.current_page', 2)
            ->assertJsonPath('counts.all', 2)
            ->assertJsonPath('counts.uams', 2)
            ->assertJsonPath('counts.cmis', 0);

        $this->actingAs($administrator)
            ->getJson('/api/audit-trail?search='.urlencode('%'))
            ->assertOk()
            ->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.action', '100% done');
    }

    public function test_an_unknown_source_returns_422(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/audit-trail?source=payroll')
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['source' => 'The selected source is invalid.']);
    }

    public function test_a_page_below_1_returns_422(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/audit-trail?page=0')
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['page' => 'The page field must be at least 1.']);
    }

    public function test_an_end_date_before_the_start_date_returns_422(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/audit-trail?from=2026-10-16&to=2026-10-15')
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['to' => 'The to field must be a date after or equal to from.']);
    }

    public function test_a_search_over_100_characters_returns_422(): void
    {
        $this->actingAs(User::factory()->administrator()->create())
            ->getJson('/api/audit-trail?search='.str_repeat('a', 101))
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['search' => 'The search field must not be greater than 100 characters.']);
    }

    public function test_refresh_copies_reader_rows_and_ignores_extra_request_fields(): void
    {
        $this->app->instance(AuditTrailReader::class, new class implements AuditTrailReader
        {
            public function read(AuditSource $source): AuditSnapshot
            {
                if ($source !== AuditSource::Uams) {
                    return AuditSnapshot::unavailable("{$source->label()} has no audit trail to read.");
                }

                return new AuditSnapshot(true, null, [[
                    'external_id' => 15,
                    'occurred_at' => '2026-10-15 09:00:00',
                    'actor_name' => 'Ada Lovelace',
                    'user_level' => 'Administrator',
                    'field_office' => 'Manila',
                    'module' => 'Users',
                    'action' => 'Updated a user',
                ]]);
            }
        });

        $response = $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/audit-trail/refresh', [
                'action' => 'Deleted everything',
                'source' => 'uams',
                'external_id' => 99,
            ])
            ->assertOk()
            ->assertJsonPath('message', 'Audit records refreshed with problems: CMIS has no audit trail to read. PPIS has no audit trail to read.')
            ->assertJsonPath('data.0.external_id', 15)
            ->assertJsonPath('data.0.action', 'Updated a user')
            ->assertJsonPath('data.0.occurred_at', '2026-10-15T01:00:00+00:00')
            ->assertJsonCount(1, 'data');

        $this->assertSame(1, AuditRecord::query()->count());
        $this->assertNotSame('Deleted everything', $response->json('data.0.action'));
    }

    public function test_a_failed_refresh_keeps_records_already_saved(): void
    {
        $existing = AuditRecord::factory()->create(['action' => 'Already saved']);
        $this->app->instance(AuditTrailReader::class, new class implements AuditTrailReader
        {
            public function read(AuditSource $source): AuditSnapshot
            {
                return AuditSnapshot::unavailable("{$source->label()} could not be read. Records already saved are unchanged.");
            }
        });

        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/audit-trail/refresh')
            ->assertOk()
            ->assertJsonPath('data.0.id', $existing->id)
            ->assertJsonPath('data.0.action', 'Already saved');

        $this->assertModelExists($existing);
        $this->assertSame(1, AuditRecord::query()->count());
    }

    public function test_an_invalid_refresh_does_not_read_source_systems(): void
    {
        $reader = Mockery::mock(AuditTrailReader::class);
        $reader->shouldNotReceive('read');
        $this->app->instance(AuditTrailReader::class, $reader);

        $this->actingAs(User::factory()->administrator()->create())
            ->postJson('/api/audit-trail/refresh', ['source' => 'payroll'])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['source' => 'The selected source is invalid.']);
    }

    public function test_the_sync_command_reports_each_source(): void
    {
        $this->app->instance(AuditTrailReader::class, new class implements AuditTrailReader
        {
            public function read(AuditSource $source): AuditSnapshot
            {
                if ($source !== AuditSource::Cmis) {
                    return AuditSnapshot::unavailable("{$source->label()} is not configured for audit reading.");
                }

                return new AuditSnapshot(true, null, [[
                    'external_id' => 3,
                    'occurred_at' => '2026-10-16 08:00:00',
                    'actor_name' => 'Grace Hopper',
                    'user_level' => null,
                    'field_office' => null,
                    'module' => 'Dockets',
                    'action' => 'Opened a docket',
                ]]);
            }
        });

        $this->artisan('audit:sync')
            ->expectsOutputToContain('CMIS: saved 1 record(s).')
            ->expectsOutputToContain('UAMS: UAMS is not configured for audit reading.')
            ->assertSuccessful();

        $this->assertDatabaseHas('audit_records', [
            'source' => 'cmis',
            'external_id' => 3,
            'action' => 'Opened a docket',
        ]);
    }
}

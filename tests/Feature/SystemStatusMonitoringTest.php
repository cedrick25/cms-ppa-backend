<?php

namespace Tests\Feature;

use App\Enums\SystemEnvironment;
use App\Enums\SystemStatus;
use App\Models\System;
use App\Models\SystemStatusCheck;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use PHPUnit\Framework\Attributes\TestWith;
use Tests\TestCase;

class SystemStatusMonitoringTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();

        config([
            'cms.system_status.interval_minutes' => 5,
            'cms.system_status.max_backoff_minutes' => 60,
            'cms.system_status.stale_after_minutes' => 10,
        ]);
    }

    public function test_a_system_that_answers_is_up_and_rechecked_after_the_interval(): void
    {
        $this->freezeSecond();
        Http::preventStrayRequests();
        Http::fake(['https://uams.probation.gov.ph/ppa-uams' => Http::response('', 200)]);
        $system = System::factory()->create(['domain' => 'uams.probation.gov.ph/ppa-uams?key=super.admin']);

        $this->artisan('systems:check-status')->assertSuccessful();

        Http::assertSentCount(1);
        Http::assertSent(fn (Request $request) => $request->url() === 'https://uams.probation.gov.ph/ppa-uams');
        $check = $system->statusCheck()->firstOrFail();
        $this->assertSame(SystemStatus::Up, $check->status);
        $this->assertSame(200, $check->http_status);
        $this->assertSame('Responded with HTTP 200 over HTTPS.', $check->reason);
        $this->assertTrue($check->checked_at->equalTo(now()));
        $this->assertTrue($check->next_check_at->equalTo(now()->addMinutes(5)));
    }

    #[TestWith([404, 'up'])]
    #[TestWith([302, 'up'])]
    #[TestWith([503, 'down'])]
    public function test_any_answer_below_500_counts_as_reachable(int $code, string $expected): void
    {
        Http::preventStrayRequests();
        Http::fake(['https://rcts.probation.gov.ph/login' => Http::response('', $code)]);
        $system = System::factory()->create(['domain' => 'https://rcts.probation.gov.ph/login']);

        $this->artisan('systems:check-status')->assertSuccessful();

        $actualStatus = $system->statusCheck()->firstOrFail()->status->value;
        $this->assertSame($expected, $actualStatus);
    }

    public function test_a_domain_without_a_scheme_falls_back_to_http_when_https_cannot_connect(): void
    {
        Http::preventStrayRequests();
        Http::fake([
            'https://pmeis.probation.gov.ph:3000' => Http::failedConnection('cURL error 35: SSL connect error'),
            'http://pmeis.probation.gov.ph:3000' => Http::response('', 200),
        ]);
        $system = System::factory()->create(['domain' => 'pmeis.probation.gov.ph:3000']);

        $this->artisan('systems:check-status')->assertSuccessful();

        Http::assertSent(fn (Request $request) => $request->url() === 'http://pmeis.probation.gov.ph:3000');
        $check = $system->statusCheck()->firstOrFail();
        $this->assertSame(SystemStatus::Up, $check->status);
        $this->assertSame('Responded with HTTP 200 over HTTP.', $check->reason);
    }

    public function test_an_unreachable_system_is_down_and_an_explicit_scheme_is_not_retried(): void
    {
        Http::preventStrayRequests();
        Http::fake([
            'https://mail.probation.gov.ph/zimbra' => Http::failedConnection(),
            'http://mail.probation.gov.ph/zimbra' => Http::response('', 200),
        ]);
        $system = System::factory()->create(['domain' => 'https://mail.probation.gov.ph/zimbra']);

        $this->artisan('systems:check-status')->assertSuccessful();

        Http::assertNotSent(fn (Request $request) => str_starts_with($request->url(), 'http://'));
        $check = $system->statusCheck()->firstOrFail();
        $this->assertSame(SystemStatus::Down, $check->status);
        $this->assertSame("The domain name couldn't be resolved.", $check->reason);
        $this->assertSame(1, $check->consecutive_failures);
    }

    #[TestWith([0, 5])]
    #[TestWith([1, 10])]
    #[TestWith([3, 40])]
    #[TestWith([6, 60])]
    public function test_each_consecutive_failure_doubles_the_wait_up_to_the_maximum(int $previousFailures, int $expectedMinutes): void
    {
        $this->freezeSecond();
        Http::preventStrayRequests();
        Http::fake(['https://brs.probation.gov.ph' => Http::failedConnection()]);
        $system = System::factory()->create(['domain' => 'https://brs.probation.gov.ph']);

        if ($previousFailures > 0) {
            SystemStatusCheck::factory()->down($previousFailures)->for($system)->create(['next_check_at' => now()]);
        }

        $this->artisan('systems:check-status')->assertSuccessful();

        $check = $system->statusCheck()->firstOrFail();
        $this->assertSame($previousFailures + 1, $check->consecutive_failures);
        $this->assertTrue($check->next_check_at->equalTo(now()->addMinutes($expectedMinutes)));
    }

    public function test_only_production_systems_with_a_domain_that_are_due_get_requested(): void
    {
        Http::preventStrayRequests();
        Http::fake(['https://ppis.probation.gov.ph/pis' => Http::response('', 200)]);
        $notDue = System::factory()->create(['domain' => 'https://ppis.probation.gov.ph/pis']);
        SystemStatusCheck::factory()->for($notDue)->create(['next_check_at' => now()->addMinutes(3)]);
        System::factory()->create(['domain' => 'https://ppis-stg.probation.gov.ph', 'environment' => SystemEnvironment::Test]);
        System::factory()->create(['domain' => null]);

        $this->artisan('systems:check-status')->assertSuccessful();
        Http::assertNothingSent();

        $this->artisan('systems:check-status', ['--all' => true])->assertSuccessful();
        Http::assertSentCount(1);
    }

    #[TestWith(['ftp://files.probation.gov.ph'])]
    #[TestWith(['not a web address'])]
    public function test_a_domain_that_is_not_a_web_address_is_unknown_without_any_request(string $domain): void
    {
        Http::preventStrayRequests();
        Http::fake();
        $system = System::factory()->create(['domain' => $domain]);

        $this->artisan('systems:check-status')->assertSuccessful();

        Http::assertNothingSent();
        $check = $system->statusCheck()->firstOrFail();
        $this->assertSame(SystemStatus::Unknown, $check->status);
        $this->assertSame("The domain name isn't a web address that can be checked.", $check->reason);
    }

    public function test_changing_the_domain_discards_the_previous_result(): void
    {
        $system = System::factory()->create(['domain' => 'old.probation.gov.ph']);
        SystemStatusCheck::factory()->for($system)->create();

        $system->update(['domain' => 'new.probation.gov.ph']);

        $this->assertDatabaseMissing('system_status_checks', ['system_id' => $system->id]);
    }

    public function test_guests_and_disabled_accounts_cannot_read_system_status(): void
    {
        $this->getJson('/api/dashboard/systems')->assertUnauthorized();

        $this->actingAs(User::factory()->disabled()->create())
            ->getJson('/api/dashboard/systems')
            ->assertUnauthorized();
    }

    public function test_dashboard_returns_saved_results_for_production_systems_without_contacting_them(): void
    {
        $this->freezeSecond();
        Http::preventStrayRequests();
        Http::fake();
        $admin = User::factory()->administrator()->create();
        $up = System::factory()->withAllCredentials()->create([
            'name' => 'EPPCMIS PROD',
            'domain' => 'https://eppcmis.probation.gov.ph/ppa-cmis_origin',
            'local_ip' => '192.168.1.33/ppa-cmis_origin',
        ]);
        SystemStatusCheck::factory()->for($up)->create(['response_time_ms' => 120]);
        $stale = System::factory()->create(['name' => 'RCTS PROD', 'domain' => 'rcts.probation.gov.ph']);
        SystemStatusCheck::factory()->down(2)->stale()->for($stale)->create();
        System::factory()->create(['name' => 'UAMS PROD', 'domain' => 'uams.probation.gov.ph']);
        System::factory()->create(['name' => 'BRS PROD', 'domain' => null]);
        System::factory()->create(['name' => 'UAMS TEST', 'environment' => SystemEnvironment::Test]);

        $response = $this->actingAs($admin)->getJson('/api/dashboard/systems')->assertOk();

        Http::assertNothingSent();
        $response->assertJsonPath('meta.total', 4)
            ->assertJsonPath('meta.check_interval_minutes', 5)
            ->assertJsonPath('data.*.name', ['BRS PROD', 'EPPCMIS PROD', 'RCTS PROD', 'UAMS PROD'])
            ->assertJsonPath('data.0.status.state', 'unknown')
            ->assertJsonPath('data.0.status.reason', 'No domain name is recorded for this system.')
            ->assertJsonPath('data.1.domain', 'eppcmis.probation.gov.ph')
            ->assertJsonPath('data.1.ip_address', '192.168.1.33')
            ->assertJsonPath('data.1.status.state', 'up')
            ->assertJsonPath('data.1.status.response_time_ms', 120)
            ->assertJsonPath('data.1.status.checked_at', now()->toIso8601String())
            ->assertJsonPath('data.1.status.is_stale', false)
            ->assertJsonPath('data.2.status.state', 'down')
            ->assertJsonPath('data.2.status.is_stale', true)
            ->assertJsonPath('data.2.status.checked_at', now()->subHours(3)->toIso8601String())
            ->assertJsonPath('data.3.status.state', 'unknown')
            ->assertJsonPath('data.3.status.reason', 'Not checked yet.')
            ->assertJsonPath('data.3.status.checked_at', null);

        $this->assertSame(
            ['id', 'name', 'domain', 'domain_hidden', 'ip_address', 'ip_address_hidden', 'status'],
            array_keys($response->json('data.1')),
        );
    }

    public function test_private_addresses_are_shown_to_regular_users_who_can_manage_systems(): void
    {
        $system = System::factory()->create([
            'name' => 'Server Monitoring',
            'domain' => '192.168.1.171',
            'local_ip' => '192.168.1.171',
        ]);

        $this->actingAs(User::factory()->create())->getJson('/api/dashboard/systems')
            ->assertOk()
            ->assertJsonPath('data.0.id', $system->id)
            ->assertJsonPath('data.0.domain', '192.168.1.171')
            ->assertJsonPath('data.0.domain_hidden', false)
            ->assertJsonPath('data.0.ip_address', '192.168.1.171')
            ->assertJsonPath('data.0.ip_address_hidden', false);
    }
}

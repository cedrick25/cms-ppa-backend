<?php

namespace Tests\Feature;

use App\Models\System;
use App\Models\SystemStatusCheck;
use App\Models\User;
use App\Models\UserNotification;
use App\Notifications\SystemStatusChanged;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Notifications\SendQueuedNotifications;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Queue;
use Illuminate\Support\Str;
use PHPUnit\Framework\Attributes\TestWith;
use Tests\TestCase;

class NotificationsTest extends TestCase
{
    use RefreshDatabase;

    private const PASSWORD = 'Harbor-Lights-2026';

    private function admin(string $name, string $username): User
    {
        return User::factory()->administrator()->create(['name' => $name, 'username' => $username]);
    }

    private function notifyDown(User $user, string $systemName): void
    {
        $user->notify(new SystemStatusChanged($systemName, SystemStatusChanged::DOWN, 2, 'The connection was refused.'));
    }

    public function test_guests_and_disabled_accounts_receive_401_from_every_notification_endpoint(): void
    {
        $id = (string) Str::uuid();

        $this->getJson('/api/notifications')->assertUnauthorized();
        $this->patchJson("/api/notifications/{$id}/read")->assertUnauthorized();
        $this->postJson('/api/notifications/read-all')->assertUnauthorized();

        $this->actingAs(User::factory()->disabled()->create())
            ->getJson('/api/notifications')
            ->assertUnauthorized();
    }

    public function test_list_returns_only_the_accounts_own_notifications_newest_first_with_the_unread_count(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create();
        $this->travelTo(now()->subMinutes(10));
        $this->notifyDown($user, 'UAMS PROD');
        $this->travelBack();
        $this->notifyDown($user, 'RCTS PROD');
        $this->notifyDown($other, 'BRS PROD');
        $user->notifications()->reorder('created_at')->firstOrFail()->markAsRead();

        $this->actingAs($user)->getJson('/api/notifications')
            ->assertOk()
            ->assertJsonPath('meta.unread_count', 1)
            ->assertJsonPath('meta.timezone', 'Asia/Manila')
            ->assertJsonPath('data.*.title', ['RCTS PROD is down', 'UAMS PROD is down'])
            ->assertJsonPath('data.*.is_read', [false, true])
            ->assertJsonPath('data.0.category', 'system_status')
            ->assertJsonPath('data.0.message', 'Failed 2 checks in a row. The connection was refused.')
            ->assertJsonPath('data.0.link', ['page' => 'dashboard', 'search' => null])
            ->assertJsonMissing(['title' => 'BRS PROD is down']);
    }

    public function test_polling_with_the_current_etag_returns_304_until_the_list_changes(): void
    {
        $user = User::factory()->create();
        $this->notifyDown($user, 'UAMS PROD');

        $etag = $this->actingAs($user)->getJson('/api/notifications')->assertOk()->headers->get('ETag');
        $this->assertNotNull($etag);

        $this->actingAs($user)->getJson('/api/notifications', ['If-None-Match' => $etag])
            ->assertStatus(304)
            ->assertContent('');

        $this->travel(1)->minute();
        $this->notifyDown($user, 'RCTS PROD');
        $afterArrival = $this->actingAs($user)->getJson('/api/notifications', ['If-None-Match' => $etag])
            ->assertOk()
            ->assertJsonPath('meta.unread_count', 2)
            ->headers->get('ETag');
        $this->assertNotSame($etag, $afterArrival);

        $this->travel(1)->minute();
        $user->notifications()->firstOrFail()->markAsRead();
        $this->actingAs($user)->getJson('/api/notifications', ['If-None-Match' => $afterArrival])
            ->assertOk()
            ->assertJsonPath('meta.unread_count', 1);
    }

    public function test_read_notifications_are_pruned_sooner_than_unread_ones(): void
    {
        config(['cms.notifications.read_retention_days' => 30, 'cms.notifications.retention_days' => 90]);
        $user = User::factory()->create();
        foreach (['read long ago', 'read recently', 'unread and old', 'unread'] as $name) {
            $this->notifyDown($user, $name);
        }
        $notification = fn (string $name) => $user->notifications()->where('data->title', "{$name} is down")->firstOrFail();
        $notification('read long ago')->forceFill(['created_at' => now()->subDays(40), 'read_at' => now()->subDays(31)])->save();
        $notification('read recently')->forceFill(['created_at' => now()->subDays(40), 'read_at' => now()->subDays(29)])->save();
        $notification('unread and old')->forceFill(['created_at' => now()->subDays(91)])->save();
        $notification('unread')->forceFill(['created_at' => now()->subDays(89)])->save();

        $this->artisan('model:prune', ['--model' => [UserNotification::class]])->assertSuccessful();

        $this->assertEqualsCanonicalizing(
            ['read recently is down', 'unread is down'],
            $user->notifications()->pluck('data')->pluck('title')->all(),
        );
    }

    public function test_website_status_notifications_are_queued_instead_of_written_during_the_check(): void
    {
        Queue::fake();
        config(['cms.notifications.down_after_failures' => 2]);
        Http::preventStrayRequests();
        Http::fake(['https://rcts.probation.gov.ph' => Http::response('', 503)]);
        $system = System::factory()->create(['name' => 'RCTS PROD', 'domain' => 'https://rcts.probation.gov.ph']);
        SystemStatusCheck::factory()->down(1)->for($system)->create(['next_check_at' => now()]);
        $user = User::factory()->create();

        $this->artisan('systems:check-status')->assertSuccessful();

        Queue::assertPushed(SendQueuedNotifications::class, fn (SendQueuedNotifications $job): bool => $job->notification instanceof SystemStatusChanged
            && $job->notifiables->contains($user));
        $this->assertSame(0, $user->notifications()->count());
    }

    public function test_opening_a_notification_marks_it_read_and_the_status_persists(): void
    {
        $this->freezeSecond();
        $user = User::factory()->create();
        $this->notifyDown($user, 'UAMS PROD');
        $this->notifyDown($user, 'RCTS PROD');
        $notification = $user->notifications()->where('data->title', 'UAMS PROD is down')->firstOrFail();

        $this->actingAs($user)->patchJson("/api/notifications/{$notification->id}/read")
            ->assertOk()
            ->assertJsonPath('notification.id', $notification->id)
            ->assertJsonPath('notification.is_read', true)
            ->assertJsonPath('notification.read_at', now()->toIso8601String())
            ->assertJsonPath('meta.unread_count', 1);

        $this->assertNotNull($notification->fresh()->read_at);
        $this->actingAs($user)->getJson('/api/notifications')->assertJsonPath('meta.unread_count', 1);
    }

    public function test_marking_another_accounts_notification_returns_404_and_leaves_it_unread(): void
    {
        $owner = User::factory()->create();
        $this->notifyDown($owner, 'UAMS PROD');
        $notification = $owner->notifications()->firstOrFail();

        $this->actingAs(User::factory()->administrator()->create())
            ->patchJson("/api/notifications/{$notification->id}/read")
            ->assertNotFound();

        $this->assertNull($notification->fresh()->read_at);
    }

    public function test_marking_all_as_read_only_affects_the_signed_in_account(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create();
        $this->notifyDown($user, 'UAMS PROD');
        $this->notifyDown($user, 'RCTS PROD');
        $this->notifyDown($other, 'BRS PROD');

        $this->actingAs($user)->postJson('/api/notifications/read-all')
            ->assertOk()
            ->assertJsonPath('message', '2 notifications marked as read.')
            ->assertJsonPath('meta.unread_count', 0);

        $this->assertSame(0, $user->unreadNotifications()->count());
        $this->assertSame(1, $other->unreadNotifications()->count());
    }

    public function test_a_system_failing_the_configured_number_of_checks_in_a_row_notifies_every_active_account(): void
    {
        config(['cms.notifications.down_after_failures' => 2]);
        Http::preventStrayRequests();
        Http::fake(['https://rcts.probation.gov.ph' => Http::response('', 503)]);
        $system = System::factory()->create(['name' => 'RCTS PROD', 'domain' => 'https://rcts.probation.gov.ph']);
        SystemStatusCheck::factory()->down(1)->for($system)->create(['next_check_at' => now()]);
        $user = User::factory()->create();
        $admin = User::factory()->administrator()->create();
        $disabled = User::factory()->disabled()->create();

        $this->artisan('systems:check-status')->assertSuccessful();

        foreach ([$user, $admin] as $recipient) {
            $data = $recipient->notifications()->sole()->data;
            $this->assertSame('down', $data['event']);
            $this->assertSame('RCTS PROD is down', $data['title']);
            $this->assertSame('Failed 2 checks in a row. Responded with server error HTTP 503 over HTTPS.', $data['message']);
        }
        $this->assertSame(0, $disabled->notifications()->count());
    }

    public function test_a_system_answering_again_after_being_reported_down_notifies_that_it_is_back_up(): void
    {
        config(['cms.notifications.down_after_failures' => 2]);
        Http::preventStrayRequests();
        Http::fake(['https://rcts.probation.gov.ph' => Http::response('', 200)]);
        $system = System::factory()->create(['name' => 'RCTS PROD', 'domain' => 'https://rcts.probation.gov.ph']);
        SystemStatusCheck::factory()->down(3)->for($system)->create(['next_check_at' => now()]);
        $user = User::factory()->create();

        $this->artisan('systems:check-status')->assertSuccessful();

        $data = $user->notifications()->sole()->data;
        $this->assertSame('recovered', $data['event']);
        $this->assertSame('RCTS PROD is back up', $data['title']);
        $this->assertSame('Answering again after 3 failed checks. Responded with HTTP 200 over HTTPS.', $data['message']);
    }

    /**
     * @param  array{status: string, consecutive_failures: int}|null  $previous
     */
    #[TestWith([null, 'https://rcts.probation.gov.ph', 503], 'first check')]
    #[TestWith([['status' => 'up', 'consecutive_failures' => 0], 'https://rcts.probation.gov.ph', 200], 'still up')]
    #[TestWith([['status' => 'up', 'consecutive_failures' => 0], 'https://rcts.probation.gov.ph', 503], 'first failure')]
    #[TestWith([['status' => 'down', 'consecutive_failures' => 2], 'https://rcts.probation.gov.ph', 503], 'already reported down')]
    #[TestWith([['status' => 'down', 'consecutive_failures' => 1], 'https://rcts.probation.gov.ph', 200], 'recovered before being reported')]
    #[TestWith([['status' => 'down', 'consecutive_failures' => 2], 'ftp://rcts.probation.gov.ph', 200], 'down to unknown')]
    public function test_checks_below_the_failure_threshold_or_without_a_reportable_change_do_not_notify(?array $previous, string $domain, int $code): void
    {
        config(['cms.notifications.down_after_failures' => 2]);
        Http::preventStrayRequests();
        Http::fake(['*' => Http::response('', $code)]);
        $system = System::factory()->create(['domain' => $domain]);
        if ($previous !== null) {
            SystemStatusCheck::factory()->for($system)->create([...$previous, 'next_check_at' => now()]);
        }
        $user = User::factory()->create();

        $this->artisan('systems:check-status')->assertSuccessful();

        $this->assertSame(0, $user->notifications()->count());
    }

    public function test_the_dashboard_lists_only_website_status_changes(): void
    {
        $user = User::factory()->create();
        $this->notifyDown($user, 'UAMS PROD');
        $accountNotice = $user->notifications()->create([
            'id' => (string) Str::uuid(),
            'type' => 'App\\Notifications\\UserAccountChanged',
            'data' => [
                'category' => 'user_account',
                'title' => 'Account updated: Carla Diaz',
                'message' => 'Details changed.',
            ],
        ]);

        $this->actingAs($user)->getJson('/api/notifications')
            ->assertOk()
            ->assertJsonPath('meta.unread_count', 1)
            ->assertJsonPath('data.*.title', ['UAMS PROD is down'])
            ->assertJsonMissing(['title' => 'Account updated: Carla Diaz']);

        $this->actingAs($user)->patchJson("/api/notifications/{$accountNotice->id}/read")->assertNotFound();
        $this->assertNull($accountNotice->fresh()->read_at);

        $this->actingAs($user)->postJson('/api/notifications/read-all')
            ->assertOk()
            ->assertJsonPath('message', '1 notification marked as read.')
            ->assertJsonPath('meta.unread_count', 0);

        $this->assertNull($accountNotice->fresh()->read_at);
        $this->assertNotNull($user->notifications()->where('data->title', 'UAMS PROD is down')->firstOrFail()->read_at);
    }

    public function test_changing_saved_credentials_does_not_notify_anyone(): void
    {
        $actor = $this->admin('Ana Reyes', 'areyes');
        $otherAdmin = $this->admin('Ben Cruz', 'bcruz');
        $user = User::factory()->create();
        $disabled = User::factory()->disabled()->create();
        $system = System::factory()->create(['name' => 'RCTS PROD']);

        $this->actingAs($actor)->patchJson("/api/systems/{$system->id}", [
            'name' => 'RCTS PROD',
            'ssh_host' => '10.0.0.15',
            'rdp_password' => 'Sup3r-Secret!',
        ])->assertOk();

        $this->assertSame(0, $otherAdmin->notifications()->count());
        $this->assertSame(0, $user->notifications()->count());
        $this->assertSame(0, $actor->notifications()->count());
        $this->assertSame(0, $disabled->notifications()->count());
    }

    public function test_registering_and_removing_a_system_does_not_notify_anyone(): void
    {
        $actor = $this->admin('Ana Reyes', 'areyes');
        $otherAdmin = $this->admin('Ben Cruz', 'bcruz');

        $systemId = $this->actingAs($actor)->postJson('/api/systems', ['name' => 'PMEIS PROD', 'environment' => 'production'])
            ->assertCreated()
            ->json('system.id');
        $this->actingAs($actor)->deleteJson("/api/systems/{$systemId}")->assertOk();

        $this->assertSame(0, $otherAdmin->notifications()->count());
        $this->assertSame(0, $actor->notifications()->count());
    }

    public function test_account_changes_do_not_notify_administrators_or_the_account_holder(): void
    {
        $actor = $this->admin('Ana Reyes', 'areyes');
        $otherAdmin = $this->admin('Ben Cruz', 'bcruz');
        $holder = User::factory()->create([
            'first_name' => 'Carla', 'last_name' => 'Diaz', 'name' => 'Carla Diaz',
            'username' => 'cdiaz', 'email' => 'carla@example.com',
        ]);

        $this->actingAs($actor)->patchJson("/api/users/{$holder->id}", [
            'first_name' => 'Carla',
            'last_name' => 'Diaz',
            'username' => 'cdiaz',
            'email' => 'carla.diaz@example.com',
            'role' => 'user',
        ])->assertOk();
        $this->actingAs($actor)->postJson("/api/users/{$holder->id}/disable")->assertOk();
        $this->actingAs($actor)->postJson("/api/users/{$holder->id}/enable")->assertOk();
        $this->actingAs($actor)->putJson("/api/users/{$holder->id}/password", [
            'password' => self::PASSWORD,
            'password_confirmation' => self::PASSWORD,
        ])->assertOk();
        $this->actingAs($actor)->postJson('/api/users', [
            'first_name' => 'Maria',
            'last_name' => 'Santos',
            'email' => 'maria.santos@example.com',
            'username' => 'msantos',
            'role' => 'administrator',
            'password' => self::PASSWORD,
            'password_confirmation' => self::PASSWORD,
        ])->assertCreated();

        $this->assertSame(0, $otherAdmin->notifications()->count());
        $this->assertSame(0, $holder->notifications()->count());
        $this->assertSame(0, $actor->notifications()->count());
        $this->assertSame(0, User::query()->where('username', 'msantos')->firstOrFail()->notifications()->count());
    }

    public function test_removing_an_account_discards_its_website_status_notifications(): void
    {
        $actor = $this->admin('Ana Reyes', 'areyes');
        $otherAdmin = $this->admin('Ben Cruz', 'bcruz');
        $holder = User::factory()->create(['name' => 'Carla Diaz', 'username' => 'cdiaz']);
        $this->notifyDown($holder, 'UAMS PROD');

        $this->actingAs($actor)->deleteJson("/api/users/{$holder->id}", ['confirm_username' => 'cdiaz'])->assertOk();

        $this->assertSame(0, $otherAdmin->notifications()->count());
        $this->assertDatabaseMissing('notifications', ['notifiable_id' => $holder->id, 'notifiable_type' => User::class]);
    }
}

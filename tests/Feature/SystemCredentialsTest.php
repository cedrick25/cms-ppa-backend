<?php

namespace Tests\Feature;

use App\Enums\SystemEnvironment;
use App\Models\System;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Gate;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

class SystemCredentialsTest extends TestCase
{
    use RefreshDatabase;

    private function admin(): User
    {
        return User::factory()->administrator()->create(['username' => 'admin']);
    }

    public function test_guests_cannot_reach_any_system_endpoint(): void
    {
        $system = System::factory()->create();

        $this->getJson('/api/systems')->assertUnauthorized();
        $this->getJson("/api/systems/{$system->id}")->assertUnauthorized();
        $this->postJson('/api/systems', ['name' => 'X'])->assertUnauthorized();
        $this->patchJson("/api/systems/{$system->id}", ['name' => 'X'])->assertUnauthorized();
        $this->deleteJson("/api/systems/{$system->id}")->assertUnauthorized();

        $this->assertNotSoftDeleted($system);
    }

    public function test_regular_users_can_see_and_change_system_credentials(): void
    {
        $user = User::factory()->create();
        $system = System::factory()->create(['name' => 'UAMS PROD', 'mysql_password' => 'P@ssw0rd-mysql']);

        $this->actingAs($user)->getJson('/api/user')->assertJsonPath('can.manage_systems', true);
        $this->actingAs($user)->getJson('/api/systems')->assertOk()->assertJsonPath('data.0.name', 'UAMS PROD');
        $this->actingAs($user)->getJson("/api/systems/{$system->id}")->assertOk()->assertJsonPath('system.mysql_password', 'P@ssw0rd-mysql');
        $created = $this->actingAs($user)->postJson('/api/systems', ['name' => 'New'])->assertCreated()->json('system.id');
        $this->actingAs($user)->patchJson("/api/systems/{$system->id}", ['name' => 'Changed'])->assertOk();
        $this->actingAs($user)->deleteJson("/api/systems/{$created}")->assertOk();

        $this->assertSame('Changed', $system->fresh()->name);
        $this->assertSoftDeleted('systems', ['id' => $created]);
    }

    public function test_only_active_accounts_may_manage_systems(): void
    {
        $this->assertTrue(Gate::forUser($this->admin())->allows('manage-systems'));
        $this->assertTrue(Gate::forUser(User::factory()->create())->allows('manage-systems'));
        $this->assertTrue(Gate::forUser(User::factory()->administrator()->disabled()->create())->denies('manage-systems'));
        $this->assertTrue(Gate::forUser(User::factory()->disabled()->create())->denies('manage-systems'));
    }

    public function test_administrators_are_told_they_can_manage_systems(): void
    {
        $this->actingAs($this->admin())->getJson('/api/user')->assertJsonPath('can.manage_systems', true);
    }

    public function test_list_shows_remote_access_and_addresses_but_never_secrets(): void
    {
        $admin = $this->admin();
        $system = System::factory()->withAllCredentials()->create([
            'name' => 'EPPCMIS PROD',
            'anydesk_id' => '1718960340',
            'rustdesk_id' => '377865106',
            'rdp_host' => '192.168.1.33',
            'ssh_host' => '192.168.1.36',
            'ssh_username' => 'cmis',
            'local_ip' => '192.168.1.33/ppa-cmis',
            'mysql_host' => '192.168.1.33/phpmyadmin',
            'domain' => 'eppcmis.probation.gov.ph',
            'vm_host' => '192.168.1.33',
        ]);
        System::factory()->create(['name' => 'BRS TEST', 'environment' => SystemEnvironment::Test]);

        $response = $this->actingAs($admin)->getJson('/api/systems')->assertOk();

        $response->assertJsonCount(2, 'data')
            ->assertJsonPath('meta.total_registered', 2)
            ->assertJsonPath('counts.all', 2)
            ->assertJsonPath('counts.production', 1)
            ->assertJsonPath('counts.test', 1)
            ->assertJsonPath('counts.legacy', 0)
            ->assertJsonPath('data.0.name', 'BRS TEST')
            ->assertJsonPath('data.0.remote_access', [])
            ->assertJsonPath('data.1.name', 'EPPCMIS PROD')
            ->assertJsonPath('data.1.environment', 'production')
            ->assertJsonPath('data.1.remote_access', [
                ['type' => 'anydesk', 'label' => 'AnyDesk', 'value' => '1718960340', 'address' => '1718960340', 'username' => null],
                ['type' => 'rustdesk', 'label' => 'RustDesk', 'value' => '377865106', 'address' => '377865106', 'username' => null],
                ['type' => 'rdp', 'label' => 'RDP', 'value' => '192.168.1.33', 'address' => '192.168.1.33', 'username' => 'Administrator'],
                ['type' => 'ssh', 'label' => 'SSH', 'value' => 'cmis@192.168.1.36', 'address' => '192.168.1.36', 'username' => 'cmis'],
            ])
            ->assertJsonPath('data.1.local_ip', '192.168.1.33/ppa-cmis')
            ->assertJsonPath('data.1.mysql_host', '192.168.1.33/phpmyadmin')
            ->assertJsonPath('data.1.domain', 'eppcmis.probation.gov.ph')
            ->assertJsonPath('data.1.vm_host', '192.168.1.33');

        foreach (System::SECRETS as $secret) {
            $response->assertJsonMissingPath("data.1.{$secret}");
            $this->assertStringNotContainsString($system->{$secret}, $response->getContent());
        }
    }

    public function test_list_can_be_searched_and_filtered_by_environment(): void
    {
        $admin = $this->admin();
        System::factory()->create(['name' => 'UAMS PROD', 'local_ip' => '192.168.1.36/ppa-uams']);
        System::factory()->create(['name' => 'UAMS TEST', 'environment' => SystemEnvironment::Test, 'local_ip' => '192.168.1.40']);
        System::factory()->create(['name' => 'RCTS PROD', 'domain' => 'rcts.probation.gov.ph', 'local_ip' => '192.168.1.35']);

        $this->actingAs($admin)->getJson('/api/systems?search=uams')
            ->assertOk()
            ->assertJsonCount(2, 'data')
            ->assertJsonPath('counts.all', 2)
            ->assertJsonPath('counts.production', 1)
            ->assertJsonPath('counts.test', 1);

        $this->actingAs($admin)->getJson('/api/systems?search=rcts.probation')
            ->assertOk()
            ->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.name', 'RCTS PROD');

        $this->actingAs($admin)->getJson('/api/systems?search=192.168.1&environment=test')
            ->assertOk()
            ->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.name', 'UAMS TEST');

        $this->actingAs($admin)->getJson('/api/systems?search=nothing-matches')
            ->assertOk()
            ->assertJsonCount(0, 'data')
            ->assertJsonPath('meta.total', 0)
            ->assertJsonPath('meta.total_registered', 3);

        $this->actingAs($admin)->getJson('/api/systems?environment=staging')
            ->assertStatus(422)
            ->assertJsonValidationErrors(['environment']);
    }

    public function test_list_is_paginated(): void
    {
        System::factory()->count(23)->create();

        $this->actingAs($this->admin())->getJson('/api/systems?page=2')
            ->assertOk()
            ->assertJsonCount(3, 'data')
            ->assertJsonPath('meta.current_page', 2)
            ->assertJsonPath('meta.last_page', 2)
            ->assertJsonPath('meta.from', 21);
    }

    public function test_details_return_decrypted_credentials_that_are_encrypted_at_rest(): void
    {
        Log::spy();
        $system = System::factory()->create([
            'name' => 'UAMS PROD',
            'mysql_username' => 'ppa_uams',
            'mysql_password' => 'P@ssw0rd-mysql',
            'notes' => 'root / NewStrongPassword!',
        ]);

        $this->actingAs($this->admin())->getJson("/api/systems/{$system->id}")
            ->assertOk()
            ->assertJsonPath('system.name', 'UAMS PROD')
            ->assertJsonPath('system.mysql_username', 'ppa_uams')
            ->assertJsonPath('system.mysql_password', 'P@ssw0rd-mysql')
            ->assertJsonPath('system.notes', 'root / NewStrongPassword!')
            ->assertJsonPath('system.anydesk_password', null);

        $stored = DB::table('systems')->where('id', $system->id)->first();
        $this->assertNotSame('P@ssw0rd-mysql', $stored->mysql_password);
        $this->assertStringNotContainsString('NewStrongPassword', $stored->notes);

        Log::shouldHaveReceived('info')->withArgs(fn (string $message, array $context) => $message === 'System credentials viewed.'
            && $context['system_id'] === $system->id
            && $context['performed_by'] === 'admin')->once();
    }

    public function test_administrator_can_register_a_system_with_only_a_name(): void
    {
        $this->actingAs($this->admin())->postJson('/api/systems', ['name' => '  PPIS PROD  ', 'environment' => ''])
            ->assertCreated()
            ->assertJsonPath('message', 'System registered: PPIS PROD.')
            ->assertJsonPath('system.name', 'PPIS PROD')
            ->assertJsonPath('system.environment', null)
            ->assertJsonPath('system.local_ip', null);

        $this->assertDatabaseHas('systems', ['name' => 'PPIS PROD', 'environment' => null, 'local_ip' => null]);
    }

    public function test_administrator_can_register_a_system_with_every_credential(): void
    {
        Log::spy();
        $details = System::factory()->withAllCredentials()->raw([
            'name' => 'EPPCMIS PROD',
            'environment' => 'production',
            'vm_password' => 'Vm-Secret-123',
        ]);

        $response = $this->actingAs($this->admin())->postJson('/api/systems', [...$details, 'id' => 999, 'deleted_at' => now()->toIso8601String()])
            ->assertCreated()
            ->assertJsonPath('system.vm_password', 'Vm-Secret-123');

        $system = System::query()->findOrFail($response->json('system.id'));
        $this->assertNotSame(999, $system->id);
        $this->assertNull($system->deleted_at);

        foreach ($details as $field => $value) {
            $this->assertSame($value, $field === 'environment' ? $system->environment->value : $system->{$field}, $field);
        }

        Log::shouldHaveReceived('info')->withArgs(fn (string $message, array $context) => $message === 'System credentials registered.'
            && $context['system_name'] === 'EPPCMIS PROD'
            && ! str_contains(json_encode($context), 'Vm-Secret-123'))->once();
    }

    public function test_registration_requires_a_name_and_reports_it(): void
    {
        $this->actingAs($this->admin())->postJson('/api/systems', ['name' => '   ', 'local_ip' => '192.168.1.36'])
            ->assertStatus(422)
            ->assertJsonValidationErrors(['name' => 'Enter the system name.']);

        $this->assertDatabaseCount('systems', 0);
    }

    public function test_registration_validates_optional_fields(): void
    {
        $this->actingAs($this->admin())->postJson('/api/systems', [
            'name' => str_repeat('a', 101),
            'environment' => 'staging',
            'anydesk_id' => str_repeat('1', 101),
            'mysql_password' => str_repeat('p', 256),
            'notes' => str_repeat('n', 2001),
        ])->assertStatus(422)
            ->assertJsonValidationErrors([
                'name' => 'System name must be 100 characters or fewer.',
                'environment' => 'Choose Production, Test, or Legacy.',
                'anydesk_id' => 'AnyDesk ID must be 100 characters or fewer.',
                'mysql_password' => 'MySQL password must be 255 characters or fewer.',
                'notes' => 'Notes must be 2,000 characters or fewer.',
            ]);
    }

    public function test_names_must_be_unique_among_systems_that_have_not_been_removed(): void
    {
        $admin = $this->admin();
        System::factory()->create(['name' => 'UAMS PROD']);
        System::factory()->create(['name' => 'BRS PROD'])->delete();

        $this->actingAs($admin)->postJson('/api/systems', ['name' => 'UAMS PROD'])
            ->assertStatus(422)
            ->assertJsonValidationErrors(['name' => 'Another registered system already uses this name. Choose a different name.']);

        $this->actingAs($admin)->postJson('/api/systems', ['name' => 'BRS PROD'])->assertCreated();
    }

    public function test_administrator_can_update_and_clear_fields(): void
    {
        Log::spy();
        $system = System::factory()->withAllCredentials()->create(['name' => 'RCTS PROD']);
        $unchangedVmPassword = $system->vm_password;

        $this->actingAs($this->admin())->patchJson("/api/systems/{$system->id}", [
            'name' => 'RCTS PROD (Ubuntu)',
            'environment' => 'legacy',
            'rdp_host' => '',
            'rdp_username' => null,
            'rdp_password' => '',
            'mysql_password' => 'Changed-Secret-1',
        ])->assertOk()
            ->assertJsonPath('message', 'Changes saved for RCTS PROD (Ubuntu).')
            ->assertJsonPath('system.environment', 'legacy')
            ->assertJsonPath('system.rdp_host', null)
            ->assertJsonPath('system.mysql_password', 'Changed-Secret-1');

        $system->refresh();
        $this->assertSame('RCTS PROD (Ubuntu)', $system->name);
        $this->assertSame(SystemEnvironment::Legacy, $system->environment);
        $this->assertNull($system->rdp_host);
        $this->assertNull($system->rdp_password);
        $this->assertSame('Changed-Secret-1', $system->mysql_password);
        $this->assertSame($unchangedVmPassword, $system->vm_password);

        Log::shouldHaveReceived('info')->withArgs(fn (string $message, array $context) => $message === 'System credentials updated.'
            && $context['performed_by'] === 'admin'
            && in_array('mysql_password', $context['changed'], true)
            && ! in_array('vm_password', $context['changed'], true)
            && ! str_contains(json_encode($context), 'Changed-Secret-1'))->once();
    }

    public function test_saving_unchanged_details_is_allowed(): void
    {
        $system = System::factory()->withAllCredentials()->create(['name' => 'BRS PROD']);
        $details = [...$system->only($system->getFillable()), 'environment' => $system->environment->value];

        $this->actingAs($this->admin())->patchJson("/api/systems/{$system->id}", $details)
            ->assertOk()
            ->assertJsonPath('message', 'No changes were needed for BRS PROD.');
    }

    public function test_update_still_requires_a_name_and_a_unique_one(): void
    {
        $admin = $this->admin();
        System::factory()->create(['name' => 'UAMS PROD']);
        $system = System::factory()->create(['name' => 'UAMS TEST']);

        $this->actingAs($admin)->patchJson("/api/systems/{$system->id}", ['name' => ''])
            ->assertStatus(422)
            ->assertJsonValidationErrors(['name' => 'Enter the system name.']);

        $this->actingAs($admin)->patchJson("/api/systems/{$system->id}", ['name' => 'UAMS PROD'])
            ->assertStatus(422)
            ->assertJsonValidationErrors(['name']);

        $this->assertSame('UAMS TEST', $system->fresh()->name);
    }

    public function test_removal_soft_deletes_the_system_and_it_can_no_longer_be_viewed_or_updated(): void
    {
        Log::spy();
        $admin = $this->admin();
        $system = System::factory()->create(['name' => 'PPIS OLD']);
        System::factory()->create(['name' => 'PPIS PROD']);

        $this->actingAs($admin)->deleteJson("/api/systems/{$system->id}")
            ->assertOk()
            ->assertJsonPath('message', 'System removed: PPIS OLD.');

        $this->assertSoftDeleted($system);

        $this->actingAs($admin)->getJson('/api/systems')
            ->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.name', 'PPIS PROD')
            ->assertJsonPath('meta.total_registered', 1);

        $this->actingAs($admin)->getJson("/api/systems/{$system->id}")->assertNotFound();
        $this->actingAs($admin)->patchJson("/api/systems/{$system->id}", ['name' => 'Revived'])->assertNotFound();
        $this->actingAs($admin)->deleteJson("/api/systems/{$system->id}")->assertNotFound();

        Log::shouldHaveReceived('info')->withArgs(fn (string $message, array $context) => $message === 'System credentials removed.'
            && $context['system_name'] === 'PPIS OLD'
            && $context['performed_by'] === 'admin')->once();
    }
}

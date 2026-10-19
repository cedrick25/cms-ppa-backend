<?php

namespace Tests\Feature;

use App\Enums\SystemEnvironment;
use App\Models\System;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\DB;
use Tests\TestCase;

class ImportSystemsCommandTest extends TestCase
{
    use RefreshDatabase;

    private const HEADER = "System,Anydesk,RustDesk,Server Cred,IP,MySql,Domain,VM,Notes,,\n";

    public function test_spreadsheet_blocks_are_imported_into_encrypted_system_fields(): void
    {
        $path = $this->csv(self::HEADER
            ."ALPHA PROD ubuntu - Alpha Records System,,,ssh deploy@10.0.0.5,10.0.0.5/alpha,10.0.0.5/pma,alpha.example.test,10.0.0.5 (SSH),VPN,,\n"
            .",,,deploy,app-user,db-user,web-user,vm-user,vpn-user,,\n"
            .",,,Ssh-Secret-1,App-Secret-1,Db-Secret-1,Web-Secret-1,Vm-Secret-1,Vpn-Secret-1,,\n"
            ."BETA OLD windows,123 456 789,987654321,10.0.0.9 (RDP),,,,,,,\n"
            .",Any-Secret-2,Rust-Secret-2,Administrator,,,,,,,\n"
            .",,,Rdp-Secret-2,,,,,,,\n"
            ."GAMMA windows(stg),,,gamma-admin,,,,,,,\n"
            .",,,Rdp-Secret-3,,,,,,,\n"
            ."DELTA TEST SERVER,,,,,,,,,,\n"
            .",,,,,,,,,,\n");

        $exit = Artisan::call('cms:import-systems', ['path' => $path]);
        $output = Artisan::output();

        $this->assertSame(0, $exit);
        $this->assertStringContainsString('4 system(s) imported.', $output);
        $this->assertStringNotContainsString('Secret', $output);

        $alpha = System::query()->where('name', 'ALPHA PROD ubuntu')->firstOrFail();
        $this->assertSame('Alpha Records System', $alpha->description);
        $this->assertSame(SystemEnvironment::Production, $alpha->environment);
        $this->assertSame(['10.0.0.5', 'deploy', 'Ssh-Secret-1'], [$alpha->ssh_host, $alpha->ssh_username, $alpha->ssh_password]);
        $this->assertSame(['10.0.0.5/alpha', 'app-user', 'App-Secret-1'], [$alpha->local_ip, $alpha->local_username, $alpha->local_password]);
        $this->assertSame(['10.0.0.5/pma', 'db-user', 'Db-Secret-1'], [$alpha->mysql_host, $alpha->mysql_username, $alpha->mysql_password]);
        $this->assertSame(['alpha.example.test', 'web-user', 'Web-Secret-1'], [$alpha->domain, $alpha->domain_username, $alpha->domain_password]);
        $this->assertSame(['10.0.0.5 (SSH)', 'vm-user', 'Vm-Secret-1'], [$alpha->vm_host, $alpha->vm_username, $alpha->vm_password]);
        $this->assertSame("VPN\nvpn-user\nVpn-Secret-1", $alpha->notes);

        $beta = System::query()->where('name', 'BETA OLD windows')->firstOrFail();
        $this->assertSame(SystemEnvironment::Legacy, $beta->environment);
        $this->assertSame(['123 456 789', 'Any-Secret-2'], [$beta->anydesk_id, $beta->anydesk_password]);
        $this->assertSame(['987654321', 'Rust-Secret-2'], [$beta->rustdesk_id, $beta->rustdesk_password]);
        $this->assertSame(['10.0.0.9', 'Administrator', 'Rdp-Secret-2'], [$beta->rdp_host, $beta->rdp_username, $beta->rdp_password]);

        $gamma = System::query()->where('name', 'GAMMA windows(stg)')->firstOrFail();
        $this->assertSame(SystemEnvironment::Test, $gamma->environment);
        $this->assertSame(['gamma-admin', 'Rdp-Secret-3'], [$gamma->rdp_username, $gamma->rdp_password]);
        $this->assertNull($gamma->ssh_username);

        $delta = System::query()->where('name', 'DELTA TEST SERVER')->firstOrFail();
        $this->assertSame(SystemEnvironment::Test, $delta->environment);
        $this->assertNull($delta->local_ip);
        $this->assertNull($delta->notes);

        $this->assertStringNotContainsString('Ssh-Secret-1', (string) DB::table('systems')->where('id', $alpha->id)->value('ssh_password'));
    }

    public function test_values_that_do_not_fit_a_field_are_kept_in_the_notes(): void
    {
        $path = $this->csv(self::HEADER
            ."VPN (PPA),vpn-login,,,,Vpn-Secret,,,,,\n"
            .",Vpn-Secret-Again,,,,,,,,,\n"
            ."REPORTS PROD,,,,,mysql -u root -p,,,,,\n"
            .",,,,,root,,,,,\n");

        $this->assertSame(0, Artisan::call('cms:import-systems', ['path' => $path]));

        $vpn = System::query()->where('name', 'VPN (PPA)')->firstOrFail();
        $this->assertNull($vpn->anydesk_id);
        $this->assertNull($vpn->mysql_host);
        $this->assertSame(
            "Imported from the spreadsheet as written:\nAnyDesk: vpn-login / Vpn-Secret-Again\nMySQL: Vpn-Secret",
            $vpn->notes,
        );

        $reports = System::query()->where('name', 'REPORTS PROD')->firstOrFail();
        $this->assertNull($reports->mysql_username);
        $this->assertStringContainsString('MySQL: mysql -u root -p / root', $reports->notes);
    }

    public function test_dry_run_saves_nothing_and_existing_systems_are_skipped(): void
    {
        System::factory()->create(['name' => 'ALPHA PROD']);
        $path = $this->csv(self::HEADER."ALPHA PROD,,,,,,,,,,\nBETA PROD,,,,,,,,,,\n");

        $this->assertSame(0, Artisan::call('cms:import-systems', ['path' => $path, '--dry-run' => true]));
        $output = Artisan::output();
        $this->assertStringContainsString('skipped "ALPHA PROD"', $output);
        $this->assertStringContainsString('Dry run: 1 system(s) would be imported.', $output);
        $this->assertDatabaseCount('systems', 1);

        $this->assertSame(0, Artisan::call('cms:import-systems', ['path' => $path]));
        $this->assertDatabaseCount('systems', 2);
        $this->assertSame(1, System::query()->where('name', 'ALPHA PROD')->count());
    }

    public function test_an_invalid_row_stops_the_whole_import(): void
    {
        $path = $this->csv(self::HEADER."ALPHA PROD,,,,,,,,,,\n".str_repeat('X', 101).",,,,,,,,,,\n");

        $this->assertSame(1, Artisan::call('cms:import-systems', ['path' => $path]));
        $output = Artisan::output();
        $this->assertStringContainsString('Row 3', $output);
        $this->assertStringContainsString('System name must be 100 characters or fewer.', $output);
        $this->assertDatabaseCount('systems', 0);
    }

    public function test_a_file_without_the_spreadsheet_header_is_rejected(): void
    {
        $path = $this->csv("Name,Host\nALPHA PROD,10.0.0.5\n");

        $this->assertSame(1, Artisan::call('cms:import-systems', ['path' => $path]));
        $this->assertStringContainsString('The first column of the header row must be "System".', Artisan::output());
        $this->assertDatabaseCount('systems', 0);
    }

    private function csv(string $contents): string
    {
        $path = tempnam(sys_get_temp_dir(), 'systems');
        file_put_contents($path, $contents);
        $this->beforeApplicationDestroyed(fn () => @unlink($path));

        return $path;
    }
}

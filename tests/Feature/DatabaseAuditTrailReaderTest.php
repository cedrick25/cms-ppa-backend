<?php

namespace Tests\Feature;

use App\Enums\AuditSource;
use App\Models\AuditRecord;
use App\Services\DatabaseAuditTrailReader;
use App\Services\SyncAuditTrail;
use Illuminate\Database\QueryException;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;
use Tests\TestCase;

class DatabaseAuditTrailReaderTest extends TestCase
{
    use RefreshDatabase;

    public function test_uams_names_are_joined_and_the_source_stays_read_only(): void
    {
        $connection = $this->sourceDatabase(AuditSource::Uams, withFullName: false);
        DB::connection($connection)->table('USERS')->insert([
            'USER_ID' => 7,
            'USER_FNAME' => 'Ada',
            'USER_MNAME' => null,
            'USER_LNAME' => 'Lovelace',
            'USER_EMAIL' => 'ada@example.test',
            'USER_LEVEL_ID' => 2,
            'FIELD_OFFICE' => 'Manila',
        ]);
        DB::connection($connection)->table('USER_LEVEL')->insert([
            'USER_LEVEL_ID' => 2,
            'USER_LEVEL_NAME' => 'Administrator',
        ]);
        DB::connection($connection)->table('audit_trail')->insert([
            'action' => 'Updated a user',
            'created_date' => '2026-10-15 09:00:00',
            'created_by' => 7,
            'module' => 'Users',
            'IP_ADDRESS' => '203.0.113.5',
        ]);

        $snapshot = (new DatabaseAuditTrailReader)->read(AuditSource::Uams);

        $this->assertTrue($snapshot->available);
        $this->assertSame('Ada Lovelace', $snapshot->records[0]['actor_name']);
        $this->assertSame('Administrator', $snapshot->records[0]['user_level']);
        $this->assertSame('Manila', $snapshot->records[0]['field_office']);
        $this->assertSame('Updated a user', $snapshot->records[0]['action']);
        $this->assertArrayNotHasKey('IP_ADDRESS', $snapshot->records[0]);
        $this->assertSame(1, DB::connection($connection)->table('audit_trail')->count());

        $copied = (new SyncAuditTrail(new DatabaseAuditTrailReader))->sync();
        $this->assertTrue($copied[0]['available']);
        $this->assertStringNotContainsString('203.0.113.5', (string) json_encode(AuditRecord::query()->first()));

        try {
            DB::connection($connection)->table('audit_trail')->insert([
                'action' => 'This write must fail',
                'created_date' => '2026-10-15 10:00:00',
                'created_by' => 7,
                'module' => 'Users',
                'IP_ADDRESS' => '203.0.113.9',
            ]);
            $this->fail('The source connection accepted a write.');
        } catch (QueryException) {
            $this->assertSame(1, DB::connection($connection)->table('audit_trail')->count());
        }
    }

    public function test_cmis_uses_the_full_name_when_that_column_exists(): void
    {
        $connection = $this->sourceDatabase(AuditSource::Cmis, withFullName: true);
        DB::connection($connection)->table('USERS')->insert([
            'USER_ID' => 4,
            'USER_FULLNAME' => 'Grace Hopper',
            'USER_FNAME' => 'Grace',
            'USER_LNAME' => 'Hopper',
            'USER_LEVEL_ID' => 1,
            'FIELD_OFFICE' => 'Quezon City',
        ]);
        DB::connection($connection)->table('USER_LEVEL')->insert([
            'USER_LEVEL_ID' => 1,
            'USER_LEVEL_NAME' => 'Encoder',
        ]);
        DB::connection($connection)->table('audit_trail')->insert([
            'action' => 'Opened a docket',
            'created_date' => '2026-10-16 08:15:00',
            'created_by' => 4,
            'module' => 'Dockets',
            'IP_ADDRESS' => '203.0.113.8',
        ]);

        $snapshot = (new DatabaseAuditTrailReader)->read(AuditSource::Cmis);

        $this->assertTrue($snapshot->available);
        $this->assertSame('Grace Hopper', $snapshot->records[0]['actor_name']);
        $this->assertSame('Encoder', $snapshot->records[0]['user_level']);
    }

    public function test_a_source_without_an_audit_table_is_unavailable_and_does_not_change_saved_rows(): void
    {
        $existing = AuditRecord::factory()->create(['action' => 'Kept']);
        $this->sourceDatabase(AuditSource::Ppis, withFullName: false, createAuditTable: false);

        $snapshot = (new DatabaseAuditTrailReader)->read(AuditSource::Ppis);

        $this->assertFalse($snapshot->available);
        $this->assertSame('PPIS has no audit trail to read.', $snapshot->error);
        $this->assertModelExists($existing);
    }

    public function test_an_unreadable_source_is_reported_without_throwing(): void
    {
        config([
            'cms.audit_trail.sources.uams.database' => 'configured',
            'cms.audit_trail.sources.uams.connection' => 'audit_uams',
            'database.connections.audit_uams' => [
                'driver' => 'sqlite',
                'database' => storage_path('framework/missing-audit-directory/audit.sqlite'),
                'prefix' => '',
            ],
        ]);
        DB::purge('audit_uams');

        $snapshot = (new DatabaseAuditTrailReader)->read(AuditSource::Uams);

        $this->assertFalse($snapshot->available);
        $this->assertSame('UAMS could not be read. Records already saved are unchanged.', $snapshot->error);
    }

    private function sourceDatabase(AuditSource $source, bool $withFullName, bool $createAuditTable = true): string
    {
        $connection = 'audit_'.$source->value;
        $path = storage_path("framework/testing-{$connection}.sqlite");

        if (is_file($path)) {
            unlink($path);
        }

        file_put_contents($path, '');

        config([
            "cms.audit_trail.sources.{$source->value}.database" => 'configured',
            "cms.audit_trail.sources.{$source->value}.connection" => $connection,
            "database.connections.{$connection}" => [
                'driver' => 'sqlite',
                'database' => $path,
                'prefix' => '',
                'foreign_key_constraints' => true,
            ],
        ]);
        DB::purge($connection);

        Schema::connection($connection)->create('USERS', function (Blueprint $table) use ($withFullName): void {
            $table->integer('USER_ID')->primary();
            if ($withFullName) {
                $table->text('USER_FULLNAME')->nullable();
            }
            $table->text('USER_FNAME')->nullable();
            $table->text('USER_MNAME')->nullable();
            $table->text('USER_LNAME')->nullable();
            $table->text('USER_EMAIL')->nullable();
            $table->integer('USER_LEVEL_ID')->nullable();
            $table->text('FIELD_OFFICE')->nullable();
        });
        Schema::connection($connection)->create('USER_LEVEL', function (Blueprint $table): void {
            $table->integer('USER_LEVEL_ID')->primary();
            $table->text('USER_LEVEL_NAME')->nullable();
        });

        if ($createAuditTable) {
            Schema::connection($connection)->create('audit_trail', function (Blueprint $table): void {
                $table->increments('audit_trail_id');
                $table->text('action')->nullable();
                $table->dateTime('created_date')->nullable();
                $table->integer('created_by')->nullable();
                $table->text('module')->nullable();
                $table->text('IP_ADDRESS')->nullable();
            });
        }

        return $connection;
    }
}

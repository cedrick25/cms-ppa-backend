<?php

namespace App\Console\Commands;

use App\Services\SyncAuditTrail;
use Illuminate\Console\Command;

class SyncAuditTrailCommand extends Command
{
    protected $signature = 'audit:sync';

    protected $description = 'Copy recent audit records from UAMS, CMIS, and PPIS. Those systems are only read.';

    public function handle(SyncAuditTrail $sync): int
    {
        foreach ($sync->sync() as $source) {
            if ($source['available']) {
                $this->info("{$source['label']}: saved {$source['saved']} record(s).");

                continue;
            }

            $this->warn("{$source['label']}: {$source['last_error']}");
        }

        return self::SUCCESS;
    }
}

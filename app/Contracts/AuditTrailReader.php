<?php

namespace App\Contracts;

use App\Enums\AuditSource;
use App\Services\AuditSnapshot;

interface AuditTrailReader
{
    public function read(AuditSource $source): AuditSnapshot;
}

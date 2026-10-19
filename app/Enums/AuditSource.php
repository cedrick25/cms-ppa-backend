<?php

namespace App\Enums;

enum AuditSource: string
{
    case Uams = 'uams';
    case Cmis = 'cmis';
    case Ppis = 'ppis';

    public function label(): string
    {
        return match ($this) {
            self::Uams => 'UAMS',
            self::Cmis => 'CMIS',
            self::Ppis => 'PPIS',
        };
    }
}

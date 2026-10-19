<?php

namespace App\Enums;

enum SystemEnvironment: string
{
    case Production = 'production';
    case Test = 'test';
    case Legacy = 'legacy';

    public function label(): string
    {
        return match ($this) {
            self::Production => 'Production',
            self::Test => 'Test',
            self::Legacy => 'Legacy',
        };
    }
}

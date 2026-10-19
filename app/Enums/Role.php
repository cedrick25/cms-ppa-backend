<?php

namespace App\Enums;

enum Role: string
{
    case Administrator = 'administrator';
    case User = 'user';

    public function label(): string
    {
        return match ($this) {
            self::Administrator => 'Administrator',
            self::User => 'User',
        };
    }
}

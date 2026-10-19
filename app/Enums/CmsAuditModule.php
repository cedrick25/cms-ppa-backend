<?php

namespace App\Enums;

enum CmsAuditModule: string
{
    case Session = 'session';
    case Credentials = 'credentials';
    case Users = 'users';

    public function label(): string
    {
        return match ($this) {
            self::Session => 'Sign-in',
            self::Credentials => 'Credentials',
            self::Users => 'Users',
        };
    }
}

<?php

namespace App\Enums;

/**
 * Whether a system's web address answered the last reachability check.
 * This is not a health check: "up" only means the web server responded.
 */
enum SystemStatus: string
{
    case Up = 'up';
    case Down = 'down';
    case Unknown = 'unknown';

    public function label(): string
    {
        return match ($this) {
            self::Up => 'Up',
            self::Down => 'Down',
            self::Unknown => 'Unknown',
        };
    }
}

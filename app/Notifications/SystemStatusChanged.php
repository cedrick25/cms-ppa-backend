<?php

namespace App\Notifications;

use App\Enums\SystemStatus;
use App\Models\System;
use App\Models\SystemStatusCheck;
use Illuminate\Bus\Queueable;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Notifications\Notification;
use Illuminate\Queue\Attributes\DeleteWhenMissingModels;

/**
 * A production system failed several checks in a row, or answered again after that.
 * Only the name and the checker's reason are stored, so no private address reaches accounts that can't see it.
 */
#[DeleteWhenMissingModels]
class SystemStatusChanged extends Notification implements ShouldQueue
{
    use Queueable;

    public const DOWN = 'down';

    public const RECOVERED = 'recovered';

    public function __construct(
        public string $systemName,
        public string $event,
        public int $failedChecks,
        public string $reason,
    ) {}

    /**
     * Returns the notification a new check result calls for, if any. A system is reported down only once
     * it fails `down_after_failures` checks in a row, and back up only if it was reported down, so a single
     * dropped request doesn't interrupt anyone. The first check of a system is never reported.
     */
    public static function forCheck(System $system, ?SystemStatusCheck $previous, SystemStatus $status, int $failures, string $reason): ?self
    {
        $threshold = (int) config('cms.notifications.down_after_failures');

        return match (true) {
            $previous === null => null,
            $status === SystemStatus::Down && $failures === $threshold => new self($system->name, self::DOWN, $failures, $reason),
            $status === SystemStatus::Up
                && $previous->status === SystemStatus::Down
                && $previous->consecutive_failures >= $threshold => new self($system->name, self::RECOVERED, $previous->consecutive_failures, $reason),
            default => null,
        };
    }

    /**
     * @return array<int, string>
     */
    public function via(object $notifiable): array
    {
        return ['database'];
    }

    /**
     * @return array{category: string, event: string, title: string, message: string, changed: list<string>, performed_by: null, link: array{page: string, search: null}}
     */
    public function toArray(object $notifiable): array
    {
        $failed = $this->failedChecks === 1 ? '1 failed check' : "{$this->failedChecks} failed checks";

        [$title, $message] = $this->event === self::DOWN
            ? ["{$this->systemName} is down", ($this->failedChecks === 1 ? 'The latest check failed.' : "Failed {$this->failedChecks} checks in a row.")." {$this->reason}"]
            : ["{$this->systemName} is back up", "Answering again after {$failed}. {$this->reason}"];

        return [
            'category' => 'system_status',
            'event' => $this->event,
            'title' => $title,
            'message' => $message,
            'changed' => [],
            'performed_by' => null,
            'link' => ['page' => 'dashboard', 'search' => null],
        ];
    }
}

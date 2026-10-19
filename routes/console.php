<?php

use App\Models\SignInEvent;
use App\Models\UserNotification;
use Illuminate\Foundation\Inspiring;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Schedule;

Artisan::command('inspire', function () {
    $this->comment(Inspiring::quote());
})->purpose('Display an inspiring quote');

Schedule::command('systems:check-status')
    ->everyMinute()
    ->withoutOverlapping(10);

Schedule::command('audit:sync')
    ->everyFifteenMinutes()
    ->withoutOverlapping(10);

Schedule::command('model:prune', ['--model' => [SignInEvent::class, UserNotification::class]])->daily();

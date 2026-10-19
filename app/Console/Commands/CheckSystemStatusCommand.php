<?php

namespace App\Console\Commands;

use App\Enums\SystemEnvironment;
use App\Enums\SystemStatus;
use App\Models\System;
use App\Models\User;
use App\Notifications\SystemStatusChanged;
use Illuminate\Console\Command;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Collection;
use Illuminate\Http\Client\ConnectionException;
use Illuminate\Http\Client\Pool;
use Illuminate\Http\Client\Response;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Notification;
use Throwable;

class CheckSystemStatusCommand extends Command
{
    protected $signature = 'systems:check-status
        {--all : Check every production system now, even if its next check is not due yet}';

    protected $description = 'Check whether production systems answer at their domain name and save the latest result';

    /**
     * Keeps the backoff calculation from overflowing; the delay is capped by max_backoff_minutes long before this.
     */
    private const MAX_BACKOFF_EXPONENT = 16;

    public function handle(): int
    {
        $systems = System::query()
            ->where('environment', SystemEnvironment::Production)
            ->whereNotNull('domain')
            ->unless($this->option('all'), fn (Builder $query) => $query->where(fn (Builder $query) => $query
                ->whereDoesntHave('statusCheck')
                ->orWhereHas('statusCheck', fn (Builder $query) => $query->where('next_check_at', '<=', now()))))
            ->with('statusCheck')
            ->orderBy('id')
            ->limit(max(1, (int) config('cms.system_status.batch_size')))
            ->get();

        if ($systems->isEmpty()) {
            $this->info('No production system is due for a check.');

            return self::SUCCESS;
        }

        $results = $this->check($systems);
        $counts = array_fill_keys(array_column(SystemStatus::cases(), 'value'), 0);

        foreach ($systems as $system) {
            $result = $results[$system->id];
            $this->record($system, $result);
            $counts[$result['status']->value]++;
        }

        $this->info("Checked {$systems->count()} system(s): {$counts['up']} up, {$counts['down']} down, {$counts['unknown']} unknown.");

        return self::SUCCESS;
    }

    /**
     * Requests every system concurrently, at most `concurrency` at a time. A domain without a scheme gets one
     * HTTP retry only if HTTPS couldn't connect, so a system receives no more than two requests per check.
     *
     * @param  Collection<int, System>  $systems
     * @return array<int, array{status: SystemStatus, reason: string, http_status: int|null, response_time_ms: int|null}>
     */
    private function check(Collection $systems): array
    {
        $results = [];
        $pending = [];

        foreach ($systems as $system) {
            $urls = $system->statusCheckUrls();

            if ($urls === []) {
                $results[$system->id] = $this->result(SystemStatus::Unknown, "The domain name isn't a web address that can be checked.");
            } else {
                $pending[$system->id] = $urls;
            }
        }

        while ($pending !== []) {
            $responses = Http::pool(function (Pool $pool) use ($pending): void {
                foreach ($pending as $systemId => $urls) {
                    $pool->as((string) $systemId)
                        ->connectTimeout(max(1, (int) config('cms.system_status.connect_timeout_seconds')))
                        ->timeout(max(1, (int) config('cms.system_status.timeout_seconds')))
                        ->withoutRedirecting()
                        ->withUserAgent('CMS-PPA reachability check')
                        ->withOptions(['verify' => (bool) config('cms.system_status.verify_tls')])
                        ->get($urls[0]);
                }
            }, max(1, (int) config('cms.system_status.concurrency')));

            $retry = [];

            foreach ($pending as $systemId => $urls) {
                $outcome = $responses[(string) $systemId] ?? null;

                if ($outcome instanceof Response) {
                    $results[$systemId] = $this->fromResponse($outcome, $urls[0]);
                } elseif ($outcome instanceof ConnectionException && count($urls) > 1) {
                    $retry[$systemId] = array_slice($urls, 1);
                } elseif ($outcome instanceof ConnectionException) {
                    $results[$systemId] = $this->result(SystemStatus::Down, $this->describeConnectionFailure($outcome));
                } else {
                    Log::warning('System status check could not be completed.', [
                        'system_id' => $systemId,
                        'error' => $outcome instanceof Throwable ? $outcome::class : 'no result',
                    ]);
                    $results[$systemId] = $this->result(SystemStatus::Unknown, "The check couldn't be completed.");
                }
            }

            $pending = $retry;
        }

        return $results;
    }

    /**
     * Any answer below 500, including redirects and "not found", proves the web server is reachable.
     *
     * @return array{status: SystemStatus, reason: string, http_status: int, response_time_ms: int|null}
     */
    private function fromResponse(Response $response, string $url): array
    {
        $code = $response->status();
        $seconds = $response->handlerStats()['total_time'] ?? null;
        $protocol = str_starts_with($url, 'https://') ? 'HTTPS' : 'HTTP';

        return [
            'status' => $code < 500 ? SystemStatus::Up : SystemStatus::Down,
            'reason' => $code < 500
                ? "Responded with HTTP {$code} over {$protocol}."
                : "Responded with server error HTTP {$code} over {$protocol}.",
            'http_status' => $code,
            'response_time_ms' => is_numeric($seconds) ? (int) round($seconds * 1000) : null,
        ];
    }

    private function describeConnectionFailure(ConnectionException $exception): string
    {
        preg_match('/cURL error (\d+)/', $exception->getMessage(), $match);
        $timeout = (int) config('cms.system_status.timeout_seconds');

        return match ((int) ($match[1] ?? 0)) {
            6 => "The domain name couldn't be resolved.",
            7 => 'The connection was refused.',
            28 => "No response within {$timeout} seconds.",
            35, 51, 53, 58, 59, 60 => "A secure (HTTPS) connection couldn't be established.",
            default => "Couldn't connect.",
        };
    }

    /**
     * @return array{status: SystemStatus, reason: string, http_status: null, response_time_ms: null}
     */
    private function result(SystemStatus $status, string $reason): array
    {
        return ['status' => $status, 'reason' => $reason, 'http_status' => null, 'response_time_ms' => null];
    }

    /**
     * Saves the result and schedules the next check. Each consecutive failure doubles the wait, so an
     * unreachable system is not retried continuously.
     *
     * @param  array{status: SystemStatus, reason: string, http_status: int|null, response_time_ms: int|null}  $result
     */
    private function record(System $system, array $result): void
    {
        $previous = $system->statusCheck;
        $interval = max(1, (int) config('cms.system_status.interval_minutes'));
        $maxBackoff = max($interval, (int) config('cms.system_status.max_backoff_minutes'));
        $failures = $result['status'] === SystemStatus::Down ? ($previous->consecutive_failures ?? 0) + 1 : 0;

        $delay = match (true) {
            $result['status'] === SystemStatus::Unknown => $maxBackoff,
            $failures === 0 => $interval,
            default => (int) min($maxBackoff, $interval * 2 ** min($failures - 1, self::MAX_BACKOFF_EXPONENT)),
        };

        $now = now();

        $system->statusCheck()->updateOrCreate([], [
            ...$result,
            'consecutive_failures' => $failures,
            'checked_at' => $now,
            'next_check_at' => $now->copy()->addMinutes($delay),
        ]);

        if ($previous !== null && $previous->status !== $result['status']) {
            Log::info('System reachability changed.', [
                'system_id' => $system->id,
                'system_name' => $system->name,
                'from' => $previous->status->value,
                'to' => $result['status']->value,
            ]);
        }

        $notification = SystemStatusChanged::forCheck($system, $previous, $result['status'], $failures, $result['reason']);

        if ($notification !== null) {
            Notification::send(User::activeAccountsAllowedTo('view-system-status'), $notification);
        }
    }
}

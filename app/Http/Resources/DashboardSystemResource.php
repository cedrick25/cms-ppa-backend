<?php

namespace App\Http\Resources;

use App\Enums\SystemStatus;
use App\Models\System;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Support\Facades\Gate;

/**
 * A production system's cached reachability for the dashboard. Never includes credentials, and private
 * addresses are withheld from accounts that aren't allowed to see them.
 *
 * @mixin System
 */
class DashboardSystemResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        $canSeePrivate = Gate::forUser($request->user())->allows('view-private-addresses');
        $domain = $this->domainHost();
        $domainHidden = $domain !== null && ! $canSeePrivate && System::isPrivateAddress(explode(':', $domain)[0]);
        $ip = $this->ipAddress();
        $check = $this->statusCheck;

        return [
            'id' => $this->id,
            'name' => $this->name,
            'domain' => $domainHidden ? null : $domain,
            'domain_hidden' => $domainHidden,
            'ip_address' => $canSeePrivate ? $ip : null,
            'ip_address_hidden' => ! $canSeePrivate && $ip !== null,
            'status' => $check === null
                ? [
                    'state' => SystemStatus::Unknown,
                    'reason' => $domain === null ? 'No domain name is recorded for this system.' : 'Not checked yet.',
                    'http_status' => null,
                    'response_time_ms' => null,
                    'checked_at' => null,
                    'next_check_at' => null,
                    'is_stale' => false,
                ]
                : [
                    'state' => $check->status,
                    'reason' => $check->reason,
                    'http_status' => $check->http_status,
                    'response_time_ms' => $check->response_time_ms,
                    'checked_at' => $check->checked_at->toIso8601String(),
                    'next_check_at' => $check->next_check_at->toIso8601String(),
                    'is_stale' => $check->isStale(),
                ],
        ];
    }
}

<?php

namespace App\Models;

use App\Enums\SystemEnvironment;
use Database\Factories\SystemFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Attributes\Hidden;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasOne;
use Illuminate\Database\Eloquent\SoftDeletes;

#[Fillable([
    'name', 'description', 'environment',
    'anydesk_id', 'anydesk_password', 'rustdesk_id', 'rustdesk_password',
    'rdp_host', 'rdp_username', 'rdp_password', 'ssh_host', 'ssh_username', 'ssh_password',
    'local_ip', 'local_username', 'local_password', 'mysql_host', 'mysql_username', 'mysql_password',
    'domain', 'domain_username', 'domain_password', 'vm_host', 'vm_username', 'vm_password',
    'notes',
])]
#[Hidden(System::SECRETS)]
class System extends Model
{
    /** @use HasFactory<SystemFactory> */
    use HasFactory, SoftDeletes;

    /**
     * Encrypted at rest and only returned by the single-system detail endpoint.
     */
    public const SECRETS = [
        'anydesk_password', 'rustdesk_password', 'rdp_password', 'ssh_password',
        'local_password', 'mysql_password', 'domain_password', 'vm_password', 'notes',
    ];

    /**
     * Columns an administrator can find a system by. Secrets are encrypted, so they can't be searched.
     */
    public const SEARCHABLE = [
        'name', 'description', 'anydesk_id', 'rustdesk_id', 'rdp_host', 'ssh_host',
        'local_ip', 'mysql_host', 'domain', 'vm_host',
    ];

    /**
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'environment' => SystemEnvironment::class,
            ...array_fill_keys(self::SECRETS, 'encrypted'),
        ];
    }

    protected static function booted(): void
    {
        static::updated(function (System $system): void {
            if ($system->wasChanged('domain')) {
                $system->statusCheck()->delete();
            }
        });
    }

    /**
     * The latest reachability result. Null until the scheduled checker has run for this system.
     *
     * @return HasOne<SystemStatusCheck, $this>
     */
    public function statusCheck(): HasOne
    {
        return $this->hasOne(SystemStatusCheck::class);
    }

    /**
     * The addresses the status checker may request, in order. A domain without a scheme is tried over
     * HTTPS first, then HTTP. Query strings and fragments are dropped because some entries contain keys.
     *
     * @return list<string>
     */
    public function statusCheckUrls(): array
    {
        $address = self::parseAddress($this->domain);

        if ($address === null) {
            return [];
        }

        $schemes = $address['scheme'] === null ? ['https', 'http'] : [$address['scheme']];

        if (array_diff($schemes, ['http', 'https']) !== []) {
            return [];
        }

        $authority = $address['host'].($address['port'] !== null ? ":{$address['port']}" : '');

        return array_map(fn (string $scheme): string => "{$scheme}://{$authority}{$address['path']}", $schemes);
    }

    /**
     * The domain's host and port only, for display.
     */
    public function domainHost(): ?string
    {
        $address = self::parseAddress($this->domain);

        if ($address === null) {
            return null;
        }

        return $address['host'].($address['port'] !== null ? ":{$address['port']}" : '');
    }

    /**
     * The host part of the recorded local IP, without scheme, port, or path.
     */
    public function ipAddress(): ?string
    {
        return self::parseAddress($this->local_ip)['host'] ?? null;
    }

    public static function isPrivateAddress(string $host): bool
    {
        return filter_var($host, FILTER_VALIDATE_IP) !== false
            && filter_var($host, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE) === false;
    }

    /**
     * Splits a free-form entry such as "https://host:8080/path?x=1" or "host/path" into its parts.
     *
     * @return array{scheme: string|null, host: string, port: int|null, path: string}|null
     */
    private static function parseAddress(?string $value): ?array
    {
        $value = trim((string) $value);

        if ($value === '') {
            return null;
        }

        $hasScheme = preg_match('#^[a-z][a-z0-9+.-]*://#i', $value) === 1;
        $parts = parse_url($hasScheme ? $value : "http://{$value}");

        if ($parts === false) {
            return null;
        }

        $host = strtolower($parts['host'] ?? '');
        $isValidHost = filter_var($host, FILTER_VALIDATE_IP) !== false
            || filter_var($host, FILTER_VALIDATE_DOMAIN, FILTER_FLAG_HOSTNAME) !== false;

        if ($host === '' || ! $isValidHost) {
            return null;
        }

        return [
            'scheme' => $hasScheme ? strtolower($parts['scheme'] ?? '') : null,
            'host' => $host,
            'port' => $parts['port'] ?? null,
            'path' => $parts['path'] ?? '',
        ];
    }

    /**
     * Every remote desktop or shell entry point that has an address, in display order.
     * The address and username let the browser launch the matching app; passwords are never included.
     *
     * @return list<array{type: string, label: string, value: string, address: string, username: string|null}>
     */
    public function remoteAccess(): array
    {
        $sshTarget = $this->ssh_host !== null && $this->ssh_username !== null
            ? "{$this->ssh_username}@{$this->ssh_host}"
            : $this->ssh_host;

        $entries = [
            ['type' => 'anydesk', 'label' => 'AnyDesk', 'value' => $this->anydesk_id, 'address' => $this->anydesk_id, 'username' => null],
            ['type' => 'rustdesk', 'label' => 'RustDesk', 'value' => $this->rustdesk_id, 'address' => $this->rustdesk_id, 'username' => null],
            ['type' => 'rdp', 'label' => 'RDP', 'value' => $this->rdp_host, 'address' => $this->rdp_host, 'username' => $this->rdp_username],
            ['type' => 'ssh', 'label' => 'SSH', 'value' => $sshTarget, 'address' => $this->ssh_host, 'username' => $this->ssh_username],
        ];

        return array_values(array_filter($entries, fn (array $entry): bool => $entry['address'] !== null));
    }
}

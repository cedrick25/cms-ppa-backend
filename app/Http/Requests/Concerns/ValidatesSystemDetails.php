<?php

namespace App\Http\Requests\Concerns;

use App\Enums\SystemEnvironment;
use Illuminate\Validation\Rule;

trait ValidatesSystemDetails
{
    public const NAME_TAKEN = 'Another registered system already uses this name. Choose a different name.';

    /**
     * Field => human label used in validation messages.
     *
     * @var array<string, string>
     */
    private const OPTIONAL_TEXT_FIELDS = [
        'anydesk_id' => 'AnyDesk ID',
        'anydesk_password' => 'AnyDesk password',
        'rustdesk_id' => 'RustDesk ID',
        'rustdesk_password' => 'RustDesk password',
        'rdp_host' => 'RDP host',
        'rdp_username' => 'RDP username',
        'rdp_password' => 'RDP password',
        'ssh_host' => 'SSH host',
        'ssh_username' => 'SSH username',
        'ssh_password' => 'SSH password',
        'local_ip' => 'Local IP',
        'local_username' => 'Local IP username',
        'local_password' => 'Local IP password',
        'mysql_host' => 'MySQL IP address',
        'mysql_username' => 'MySQL username',
        'mysql_password' => 'MySQL password',
        'domain' => 'Domain name',
        'domain_username' => 'Domain username',
        'domain_password' => 'Domain password',
        'vm_host' => 'VM host',
        'vm_username' => 'VM username',
        'vm_password' => 'VM password',
    ];

    private const ID_MAX = 100;

    private const TEXT_MAX = 255;

    private const NOTES_MAX = 2000;

    /**
     * Every editable field's human label, for describing what changed without revealing values.
     *
     * @return array<string, string>
     */
    public static function fieldLabels(): array
    {
        return [
            'name' => 'System name',
            'description' => 'Description',
            'environment' => 'Environment',
            ...self::OPTIONAL_TEXT_FIELDS,
            'notes' => 'Notes',
        ];
    }

    /**
     * @return array<string, array<int, mixed>>
     */
    protected function systemDetailRules(?int $ignoreSystemId = null): array
    {
        $rules = [
            'name' => ['required', 'string', 'max:100', Rule::unique('systems', 'name')->ignore($ignoreSystemId)->withoutTrashed()],
            'description' => ['nullable', 'string', 'max:'.self::TEXT_MAX],
            'environment' => ['nullable', Rule::enum(SystemEnvironment::class)],
            'notes' => ['nullable', 'string', 'max:'.self::NOTES_MAX],
        ];

        foreach (array_keys(self::OPTIONAL_TEXT_FIELDS) as $field) {
            $rules[$field] = ['nullable', 'string', 'max:'.self::maxLengthOf($field)];
        }

        return $rules;
    }

    /**
     * @return array<string, string>
     */
    protected function systemDetailMessages(): array
    {
        $messages = [
            'name.required' => 'Enter the system name.',
            'name.max' => 'System name must be 100 characters or fewer.',
            'name.unique' => self::NAME_TAKEN,
            'description.max' => 'Description must be 255 characters or fewer.',
            'environment.enum' => 'Choose Production, Test, or Legacy.',
            'notes.max' => 'Notes must be 2,000 characters or fewer.',
        ];

        foreach (self::OPTIONAL_TEXT_FIELDS as $field => $label) {
            $messages["{$field}.string"] = "{$label} must be text.";
            $messages["{$field}.max"] = "{$label} must be ".self::maxLengthOf($field).' characters or fewer.';
        }

        return $messages;
    }

    private static function maxLengthOf(string $field): int
    {
        return str_ends_with($field, '_id') ? self::ID_MAX : self::TEXT_MAX;
    }
}

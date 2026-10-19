<?php

return [

    /*
    |--------------------------------------------------------------------------
    | Initial account provisioning
    |--------------------------------------------------------------------------
    |
    | These values are read only by `php artisan cms:create-user`. Set them in
    | the server environment for that command, then remove the password.
    | They are not application credentials and must not be committed.
    |
    */

    'admin' => [
        'name' => env('CMS_ADMIN_NAME'),
        'username' => env('CMS_ADMIN_USERNAME'),
        'email' => env('CMS_ADMIN_EMAIL'),
        'password' => env('CMS_ADMIN_PASSWORD'),
    ],

    /*
    |--------------------------------------------------------------------------
    | Dashboard display timezone
    |--------------------------------------------------------------------------
    |
    | Timestamps are stored in UTC. The dashboard shows them in this zone so
    | every viewer sees the same time regardless of their computer's clock.
    |
    */

    'display_timezone' => env('CMS_DISPLAY_TIMEZONE', 'Asia/Manila'),

    /*
    |--------------------------------------------------------------------------
    | Production system reachability checks
    |--------------------------------------------------------------------------
    |
    | `php artisan systems:check-status` runs every minute from the scheduler
    | and only checks systems whose next check is due. A system that answers
    | is re-checked after `interval_minutes`. One that doesn't is re-checked
    | after an interval that doubles with each consecutive failure, up to
    | `max_backoff_minutes`, so an unreachable system is never retried in a
    | tight loop. A result is flagged as stale when its re-check is more than
    | `stale_after_minutes` overdue, which means the scheduler isn't running.
    |
    */

    'system_status' => [
        'interval_minutes' => (int) env('SYSTEM_STATUS_INTERVAL_MINUTES', 5),
        'timeout_seconds' => (int) env('SYSTEM_STATUS_TIMEOUT_SECONDS', 5),
        'connect_timeout_seconds' => (int) env('SYSTEM_STATUS_CONNECT_TIMEOUT_SECONDS', 3),
        'concurrency' => (int) env('SYSTEM_STATUS_CONCURRENCY', 5),
        'batch_size' => (int) env('SYSTEM_STATUS_BATCH_SIZE', 50),
        'max_backoff_minutes' => (int) env('SYSTEM_STATUS_MAX_BACKOFF_MINUTES', 60),
        'stale_after_minutes' => (int) env('SYSTEM_STATUS_STALE_AFTER_MINUTES', 10),
        'verify_tls' => (bool) env('SYSTEM_STATUS_VERIFY_TLS', true),
    ],

    /*
    |--------------------------------------------------------------------------
    | Sign-in history
    |--------------------------------------------------------------------------
    |
    | The dashboard lists the most recent successful sign-ins. Older events
    | are removed daily by `php artisan model:prune`.
    |
    */

    'sign_ins' => [
        'dashboard_limit' => (int) env('CMS_SIGN_INS_DASHBOARD_LIMIT', 8),
        'retention_days' => (int) env('CMS_SIGN_INS_RETENTION_DAYS', 90),
    ],

    /*
    |--------------------------------------------------------------------------
    | Audit trail copy
    |--------------------------------------------------------------------------
    |
    | The audit page reads this application's copy. `php artisan audit:sync`
    | refreshes that copy with a read-only query against each source, limited
    | to the most recent `sync_limit` rows. A source that cannot be read does
    | not change the rows already saved. Leave a database name empty to skip
    | that source. IP addresses from the source tables are not copied.
    |
    */

    'audit_trail' => [
        'per_page' => (int) env('CMS_AUDIT_PER_PAGE', 20),
        'sync_limit' => (int) env('CMS_AUDIT_SYNC_LIMIT', 2000),
        'statement_timeout_ms' => (int) env('CMS_AUDIT_STATEMENT_TIMEOUT_MS', 8000),
        'sources' => [
            'uams' => [
                'connection' => 'audit_uams',
                'host' => env('AUDIT_UAMS_HOST', env('DB_HOST', '127.0.0.1')),
                'port' => env('AUDIT_UAMS_PORT', env('DB_PORT', '3306')),
                'database' => env('AUDIT_UAMS_DATABASE', ''),
                'username' => env('AUDIT_UAMS_USERNAME', env('DB_USERNAME', 'root')),
                'password' => env('AUDIT_UAMS_PASSWORD', env('DB_PASSWORD', '')),
            ],
            'cmis' => [
                'connection' => 'audit_cmis',
                'host' => env('AUDIT_CMIS_HOST', env('DB_HOST', '127.0.0.1')),
                'port' => env('AUDIT_CMIS_PORT', env('DB_PORT', '3306')),
                'database' => env('AUDIT_CMIS_DATABASE', ''),
                'username' => env('AUDIT_CMIS_USERNAME', env('DB_USERNAME', 'root')),
                'password' => env('AUDIT_CMIS_PASSWORD', env('DB_PASSWORD', '')),
            ],
            'ppis' => [
                'connection' => 'audit_ppis',
                'host' => env('AUDIT_PPIS_HOST', env('DB_HOST', '127.0.0.1')),
                'port' => env('AUDIT_PPIS_PORT', env('DB_PORT', '3306')),
                'database' => env('AUDIT_PPIS_DATABASE', ''),
                'username' => env('AUDIT_PPIS_USERNAME', env('DB_USERNAME', 'root')),
                'password' => env('AUDIT_PPIS_PASSWORD', env('DB_PASSWORD', '')),
            ],
        ],
    ],

    /*
    |--------------------------------------------------------------------------
    | CMS audit trail
    |--------------------------------------------------------------------------
    |
    | Activity recorded inside this application: sign-ins, sign-outs, credential
    | changes, remote desk opens, and user account changes. This is separate
    | from the systems audit trail copied from UAMS, CMIS, and PPIS.
    |
    */

    'cms_audit_trail' => [
        'per_page' => (int) env('CMS_CMS_AUDIT_PER_PAGE', 20),
    ],

    /*
    |--------------------------------------------------------------------------
    | Dashboard notifications
    |--------------------------------------------------------------------------
    |
    | Each account is notified when a production website goes down or comes
    | back up. Credential and account changes stay on the audit trail and are
    | not sent here. The dashboard polls for the most recent `dashboard_limit`
    | notifications; read status is saved per account.
    |
    | A production system is reported down only after `down_after_failures`
    | consecutive failed checks, so a single dropped request doesn't alert
    | everyone. `php artisan model:prune` removes notifications daily once
    | they have been read for `read_retention_days`, and any notification
    | older than `retention_days`.
    |
    */

    'notifications' => [
        'dashboard_limit' => (int) env('CMS_NOTIFICATIONS_DASHBOARD_LIMIT', 20),
        'down_after_failures' => max(1, (int) env('CMS_NOTIFICATIONS_DOWN_AFTER_FAILURES', 2)),
        'read_retention_days' => (int) env('CMS_NOTIFICATIONS_READ_RETENTION_DAYS', 30),
        'retention_days' => (int) env('CMS_NOTIFICATIONS_RETENTION_DAYS', 90),
    ],

];

<?php

$origins = env('CORS_ALLOWED_ORIGINS');

return [

    /*
    |--------------------------------------------------------------------------
    | Cross-Origin Resource Sharing (CORS) Configuration
    |--------------------------------------------------------------------------
    |
    | The CMS-PPA frontend runs on its own address and sends a bearer token
    | on each request. Credentials are not used, so a browser on another
    | origin can call /api/*. Set CORS_ALLOWED_ORIGINS to a comma-separated
    | list to limit which sites may call the API.
    |
    */

    'paths' => ['api/*'],

    'allowed_methods' => ['*'],

    'allowed_origins' => is_string($origins) && $origins !== ''
        ? array_values(array_filter(array_map('trim', explode(',', $origins))))
        : ['*'],

    'allowed_origins_patterns' => [],

    'allowed_headers' => ['*'],

    'exposed_headers' => ['ETag'],

    'max_age' => 0,

    'supports_credentials' => false,

];

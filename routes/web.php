<?php

use Illuminate\Support\Facades\Route;

Route::get('/', function () {
    return response()->json([
        'name' => 'CMS-PPA',
        'message' => 'This service is a JSON API. Open the CMS-PPA frontend to sign in.',
    ]);
});

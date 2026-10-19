<?php

use App\Http\Controllers\AccountController;
use App\Http\Controllers\AuditTrailController;
use App\Http\Controllers\Auth\LoginController;
use App\Http\Controllers\CmsAuditTrailController;
use App\Http\Controllers\DashboardController;
use App\Http\Controllers\NotificationController;
use App\Http\Controllers\SystemController;
use App\Http\Controllers\UserController;
use App\Http\Controllers\UserRegistrationController;
use App\Http\Middleware\EnsureAccountIsActive;
use Illuminate\Support\Facades\Route;

Route::post('/sign-in', [LoginController::class, 'store'])->name('sign-in');

Route::middleware(['auth.api', EnsureAccountIsActive::class])->group(function () {
    Route::post('/sign-out', [LoginController::class, 'destroy'])->name('sign-out');
    Route::get('/user', [AccountController::class, 'show'])->name('user');
    Route::patch('/user', [AccountController::class, 'update'])->middleware('throttle:6,1,account')->name('user.update');

    Route::middleware('throttle:60,1,dashboard')->group(function () {
        Route::get('/dashboard/systems', [DashboardController::class, 'systems'])->middleware('can:view-system-status')->name('dashboard.systems');
        Route::get('/dashboard/sign-ins', [DashboardController::class, 'signIns'])->middleware('can:view-sign-in-activity')->name('dashboard.sign-ins');
    });

    Route::middleware('throttle:120,1,notifications')->group(function () {
        Route::get('/notifications', [NotificationController::class, 'index'])->name('notifications.index');
        Route::patch('/notifications/{notification}/read', [NotificationController::class, 'markAsRead'])->whereUuid('notification')->name('notifications.read');
        Route::post('/notifications/read-all', [NotificationController::class, 'markAllAsRead'])->name('notifications.read-all');
    });

    Route::middleware('can:view-audit-trail')->group(function () {
        Route::get('/audit-trail', [AuditTrailController::class, 'index'])->middleware('throttle:60,1,audit')->name('audit-trail.index');
        Route::post('/audit-trail/refresh', [AuditTrailController::class, 'refresh'])->middleware('throttle:6,1,audit-refresh')->name('audit-trail.refresh');
        Route::get('/cms-audit-trail', [CmsAuditTrailController::class, 'index'])->middleware('throttle:60,1,cms-audit')->name('cms-audit-trail.index');
    });

    Route::middleware('can:manage-users')->group(function () {
        Route::get('/users', [UserController::class, 'index'])->name('users.index');

        Route::middleware('throttle:30,1,users')->group(function () {
            Route::post('/users', [UserRegistrationController::class, 'store'])->name('users.store');
            Route::patch('/users/{user}', [UserController::class, 'update'])->whereNumber('user')->name('users.update');
            Route::put('/users/{user}/password', [UserController::class, 'resetPassword'])->whereNumber('user')->name('users.password');
            Route::post('/users/{user}/disable', [UserController::class, 'disable'])->whereNumber('user')->name('users.disable');
            Route::post('/users/{user}/enable', [UserController::class, 'enable'])->whereNumber('user')->name('users.enable');
            Route::delete('/users/{user}', [UserController::class, 'destroy'])->whereNumber('user')->name('users.destroy');
        });
    });

    Route::middleware('can:manage-systems')->group(function () {
        Route::get('/systems', [SystemController::class, 'index'])->name('systems.index');

        Route::middleware('throttle:30,1,systems')->group(function () {
            Route::get('/systems/{system}', [SystemController::class, 'show'])->whereNumber('system')->name('systems.show');
            Route::post('/systems/{system}/remote-desks', [SystemController::class, 'openRemoteDesk'])->whereNumber('system')->name('systems.remote-desks');
            Route::post('/systems', [SystemController::class, 'store'])->name('systems.store');
            Route::patch('/systems/{system}', [SystemController::class, 'update'])->whereNumber('system')->name('systems.update');
            Route::delete('/systems/{system}', [SystemController::class, 'destroy'])->whereNumber('system')->name('systems.destroy');
        });
    });
});

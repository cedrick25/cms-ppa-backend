<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * One row per system holding only its latest reachability result, so the dashboard reads it without contacting the system.
     */
    public function up(): void
    {
        Schema::create('system_status_checks', function (Blueprint $table) {
            $table->id();
            $table->foreignId('system_id')->unique()->constrained()->cascadeOnDelete();
            $table->string('status', 20);
            $table->string('reason')->nullable();
            $table->unsignedSmallInteger('http_status')->nullable();
            $table->unsignedInteger('response_time_ms')->nullable();
            $table->unsignedInteger('consecutive_failures')->default(0);
            $table->timestamp('checked_at');
            $table->timestamp('next_check_at')->index();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('system_status_checks');
    }
};

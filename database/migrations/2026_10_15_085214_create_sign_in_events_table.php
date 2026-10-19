<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * A history of successful sign-ins. It deliberately stores no token, IP address, or session data.
     */
    public function up(): void
    {
        Schema::create('sign_in_events', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->timestamp('signed_in_at')->index();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('sign_in_events');
    }
};

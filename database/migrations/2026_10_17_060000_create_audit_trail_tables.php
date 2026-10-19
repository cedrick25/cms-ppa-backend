<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * A local copy of recent activity from UAMS, CMIS, and PPIS.
     * Network addresses from those systems are deliberately not stored.
     */
    public function up(): void
    {
        Schema::create('audit_records', function (Blueprint $table) {
            $table->id();
            $table->string('source', 20);
            $table->unsignedBigInteger('external_id');
            $table->timestamp('occurred_at')->nullable()->index();
            $table->string('actor_name')->nullable();
            $table->string('user_level')->nullable();
            $table->string('field_office')->nullable();
            $table->string('module')->nullable();
            $table->text('action')->nullable();
            $table->timestamps();

            $table->unique(['source', 'external_id']);
        });

        Schema::create('audit_source_statuses', function (Blueprint $table) {
            $table->id();
            $table->string('source', 20)->unique();
            $table->boolean('available')->default(false);
            $table->timestamp('last_synced_at')->nullable();
            $table->string('last_error')->nullable();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('audit_source_statuses');
        Schema::dropIfExists('audit_records');
    }
};

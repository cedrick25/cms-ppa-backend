<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Secrets use TEXT columns because they are stored encrypted and ciphertext length varies.
     */
    public function up(): void
    {
        Schema::create('systems', function (Blueprint $table) {
            $table->id();
            $table->string('name', 100)->index();
            $table->string('description')->nullable();
            $table->string('environment', 20)->nullable()->index();

            $table->string('anydesk_id', 100)->nullable();
            $table->text('anydesk_password')->nullable();
            $table->string('rustdesk_id', 100)->nullable();
            $table->text('rustdesk_password')->nullable();
            $table->string('rdp_host')->nullable();
            $table->string('rdp_username')->nullable();
            $table->text('rdp_password')->nullable();
            $table->string('ssh_host')->nullable();
            $table->string('ssh_username')->nullable();
            $table->text('ssh_password')->nullable();

            $table->string('local_ip')->nullable();
            $table->string('local_username')->nullable();
            $table->text('local_password')->nullable();
            $table->string('mysql_host')->nullable();
            $table->string('mysql_username')->nullable();
            $table->text('mysql_password')->nullable();
            $table->string('domain')->nullable();
            $table->string('domain_username')->nullable();
            $table->text('domain_password')->nullable();
            $table->string('vm_host')->nullable();
            $table->string('vm_username')->nullable();
            $table->text('vm_password')->nullable();

            $table->text('notes')->nullable();
            $table->timestamps();
            $table->softDeletes();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('systems');
    }
};

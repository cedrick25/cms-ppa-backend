<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->string('first_name', 50)->nullable()->after('name');
            $table->string('middle_name', 50)->nullable()->after('first_name');
            $table->string('last_name', 50)->nullable()->after('middle_name');
            $table->string('suffix', 10)->nullable()->after('last_name');
        });

        DB::table('users')->orderBy('id')->select(['id', 'name'])->each(function (object $user) {
            $words = preg_split('/\s+/u', trim($user->name), -1, PREG_SPLIT_NO_EMPTY) ?: [];
            $lastName = count($words) > 1 ? array_pop($words) : null;

            DB::table('users')->where('id', $user->id)->update([
                'first_name' => mb_substr(implode(' ', $words), 0, 50) ?: null,
                'last_name' => $lastName !== null ? mb_substr($lastName, 0, 50) : null,
            ]);
        });
    }

    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn(['first_name', 'middle_name', 'last_name', 'suffix']);
        });
    }
};

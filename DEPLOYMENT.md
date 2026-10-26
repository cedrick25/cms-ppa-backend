# Deploy CMS-PPA

The API is one Java program. Install JDK 25. Do not install PHP or Node.js for this API.

The browser app stays in `cms-ppa`. It calls this API at `http://127.0.0.1:8080/api`.

## 1. Install Java

Install JDK 25 (the current long-term release). Open a new PowerShell window and check:

```powershell
java -version
```

You should see version 25. If another Java appears first, install JDK 25 and put it on PATH.

## 2. Database

Create the MySQL database if it does not exist:

```sql
CREATE DATABASE IF NOT EXISTS cms_ppa CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

If `cms_ppa` already has the CMS-PPA tables, leave them. The API does not rebuild tables that are already there. On a new empty database it creates the tables the first time it starts, and only when the `users` table is missing.

## 3. Settings

From the `spring-api` folder:

```powershell
cd C:\wamp64\www\cms-ppa-backend\spring-api
copy .env.example .env
```

Edit `spring-api\.env`.

- `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, and `DB_PASSWORD` point at MySQL. The example uses database `cms_ppa` on `127.0.0.1` with user `root`.
- `APP_KEY` encrypts system passwords. If this database already has saved passwords, copy the existing Laravel `APP_KEY` (it starts with `base64:`). A new key makes those passwords unreadable. On a brand-new database, generate a key once:

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
"base64:" + [Convert]::ToBase64String($bytes)
```

Paste that line as `APP_KEY=` in `.env`.

- `CMS_DISPLAY_TIMEZONE` defaults to `Asia/Manila`. Times are stored in UTC and shown in that zone.
- `SYSTEM_STATUS_*` controls how often production sites are checked.
- `AUDIT_UAMS_DATABASE`, `AUDIT_CMIS_DATABASE`, and `AUDIT_PPIS_DATABASE` name the read-only source databases. Leave a name blank to skip that source. Host, port, username, and password fall back to `DB_*`. Override one source with `AUDIT_UAMS_HOST` and the matching port, username, and password variables.
- Leave `CORS_ALLOWED_ORIGINS` empty while you are on this PC. For a shared site, set it to that site's address, for example `http://cms.example`.

## 4. Build

From `spring-api`:

```powershell
.\gradlew.bat bootJar
```

The program is `spring-api\build\libs\cms-ppa-api.jar`.

## 5. Run

Stay in `spring-api` so the program reads `.env` from the current folder:

```powershell
java -jar build\libs\cms-ppa-api.jar
```

Health check: open `http://127.0.0.1:8080/up`. The response is `{"status":"ok"}`.

This one process checks production sites, copies audit rows, and deletes old sign-in events and notifications. You do not start a second queue program. Status alerts are rows in the database. Nothing is emailed.

## 6. First administrator

Do this once. Put the password only in the environment for this command, then remove it.

In `spring-api\.env`, set:

```
CMS_ADMIN_NAME=Ada Lovelace
CMS_ADMIN_USERNAME=ada
CMS_ADMIN_EMAIL=ada@example.com
CMS_ADMIN_PASSWORD=
```

The password must be at least 12 characters and include an upper-case letter, a lower-case letter, and a number. Then, from `spring-api`:

```powershell
java -jar build\libs\cms-ppa-api.jar create-user
```

Delete the `CMS_ADMIN_PASSWORD` line from `.env` after the account is created. The password is not written to a file or a log.

## 7. Keep it running on Windows

Use Task Scheduler so the API starts at logon and starts again if it stops.

1. Open Task Scheduler and choose **Create Task**.
2. Name it `CMS-PPA API`.
3. On **Triggers**, add **At log on**.
4. On **Actions**, start a program:
   - Program: the full path to `java.exe` from JDK 25
   - Arguments: `-jar build\libs\cms-ppa-api.jar`
   - Start in: `C:\wamp64\www\cms-ppa-backend\spring-api`
5. On **Settings**, choose **Restart the task if it fails**, every 1 minute, and leave the task running even if the user is not logged on if this PC is the server.

That single task covers status checks, audit copy, and daily cleanup.

## 8. Browser app

`cms-ppa\js\config.js` should contain:

```javascript
window.CMS_API_BASE = "http://127.0.0.1:8080/api";
```

Open the browser app and sign in with the administrator account.

For a shared server, leave the JAR on port 8080 and point the existing web server at it as a reverse proxy. Set `CORS_ALLOWED_ORIGINS` to the public site address, then change `CMS_API_BASE` to that public `/api` address.

## 9. Optional commands

Run these from `spring-api` while the API is stopped, or in another window. They use the same `.env`.

Check every production site now:

```powershell
java -jar build\libs\cms-ppa-api.jar check-status --all
```

Copy audit rows now:

```powershell
java -jar build\libs\cms-ppa-api.jar audit-sync
```

Preview a credentials CSV without saving:

```powershell
java -jar build\libs\cms-ppa-api.jar import-systems C:\path\credentials.csv --dry-run
```

Remove `--dry-run` to save the rows. A name that is already registered is skipped. If a row is invalid, nothing from that file is saved.

## What changed

The API no longer runs on PHP. The old PHP process is not installed or started, which is what the earlier PHP exposure depended on. Accounts, encrypted system passwords, and audit rows stay in the same MySQL database when `APP_KEY` is unchanged.

# CMS-PPA API

JSON API for the Credentials Management System. The browser interface lives in `cms-ppa` and calls this service with a bearer token.

The API stores system credentials (encrypted at rest), checks whether production sites answer, copies recent audit rows from UAMS, CMIS, and PPIS, and records what people do inside CMS-PPA.

## Requirements

- PHP 8.3 or newer, with `openssl`, `pdo_mysql`, `mbstring`, `tokenizer`, `xml`, `ctype`, `json`, `bcmath`, and `fileinfo`
- [Composer](https://getcomposer.org/)
- MySQL

On this machine those come from WAMP.

## Set up locally

From this directory:

```bash
composer install
copy .env.example .env
php artisan key:generate
```

Create a MySQL database and point `.env` at it. The example uses `cms_ppa` on `127.0.0.1` with user `root` and an empty password:

```
DB_CONNECTION=mysql
DB_HOST=127.0.0.1
DB_PORT=3306
DB_DATABASE=cms_ppa
DB_USERNAME=root
DB_PASSWORD=
```

Then create the tables and the first administrator:

```bash
php artisan migrate
```

Set these in `.env` for the next command only. The password must be at least 12 characters and include upper-case, lower-case, and a number.

```
CMS_ADMIN_NAME="Ada Lovelace"
CMS_ADMIN_USERNAME=ada
CMS_ADMIN_EMAIL=ada@example.com
CMS_ADMIN_PASSWORD=
```

```bash
php artisan cms:create-user
```

Remove `CMS_ADMIN_PASSWORD` from `.env` after the account is created. The password is not written to a file or a log.

`APP_KEY` encrypts stored credentials. Generating a new key later makes those values unreadable.

## Run it

The site in `cms-ppa` expects the API at `http://localhost/cms-ppa-backend/public/api`. With WAMP, serve the `public` directory and set:

```
APP_URL=http://localhost/cms-ppa-backend/public
```

`http://localhost/cms-ppa-backend/public` should return a short JSON message. `http://localhost/cms-ppa-backend/public/up` is the health check.

To use PHP's built-in server instead, leave `APP_URL=http://127.0.0.1:8000` and run:

```bash
php artisan serve
```

The API is then at `http://127.0.0.1:8000/api`. Change `CMS_API_BASE` in the frontend's `js/config.js` to match.

Leave `CORS_ALLOWED_ORIGINS` empty while developing so any local origin can call `/api/*`. Set it to a comma-separated list, such as `http://localhost`, when you want to limit which sites may call the API.

## Scheduler and queue

Two processes have to stay running or the dashboard goes stale and status alerts never appear.

```bash
php artisan schedule:work
php artisan queue:listen
```

`schedule:work` runs the tasks in `routes/console.php`:

| Task | When |
| --- | --- |
| `systems:check-status` | Every minute, and only for production systems whose next check is due |
| `audit:sync` | Every 15 minutes |
| `model:prune` | Daily, for old sign-in events and notifications |

`queue:listen` delivers the "site is down" and "site is back up" notifications. Those are stored for each account; nothing is emailed. A site is reported down only after two failed checks in a row.

`composer run dev` starts the built-in server and the queue listener together. It does not start the scheduler, so keep `schedule:work` running in another terminal. On Windows this command needs Node.js.

## Audit sources

The systems audit page reads CMS-PPA's own copy. `audit:sync` refreshes that copy with a read-only query. A source that cannot be reached leaves the rows already saved in place, and IP addresses from the source tables are not copied.

Set a database name to include that source. Leave it blank to skip it.

```
AUDIT_UAMS_DATABASE=ppa_sso
AUDIT_CMIS_DATABASE=ppa_cmis
AUDIT_PPIS_DATABASE=pis
```

Host, port, username, and password fall back to `DB_HOST`, `DB_PORT`, `DB_USERNAME`, and `DB_PASSWORD`. Override a source with its own variables, for example `AUDIT_UAMS_HOST`.

## Other commands

```bash
php artisan systems:check-status --all
php artisan audit:sync
php artisan cms:import-systems path\to\credentials.csv --dry-run
php artisan test
```

`cms:import-systems` loads a CSV export of the credentials spreadsheet. `--dry-run` checks the file and lists what would be saved.

Times are stored in UTC and shown in `CMS_DISPLAY_TIMEZONE` (`Asia/Manila` by default). Other tunables are commented in `.env.example`.

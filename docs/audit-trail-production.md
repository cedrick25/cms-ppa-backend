# Connecting UAMS and CMIS audit trails to CMS-PPA in production

CMS-PPA does not open the MySQL port on the UAMS or CMIS servers. Each of those servers already has an API that reads its own local database. In production, CMS asks each API for the latest audit rows over HTTPS, then stores the answer in the CMS database. The Systems audit trail page keeps reading that stored copy.

This document is the setup instructions. The HTTP sync is not built yet. Today `php artisan audit:sync` still reads the local databases `ppa_sso` and `ppa_cmis` on `127.0.0.1`.

## How the servers stay separate

```text
CMS server                          UAMS server
  audit:sync  -- HTTPS POST ------->  getAuditTrail
  saves rows                           reads local MySQL only

CMS server                          CMIS server
  audit:sync  -- HTTPS POST ------->  getAuditTrail
  saves rows                           reads local MySQL only

Browser  -- GET /api/audit-trail --> CMS database only
```

UAMS staff keep using UAMS. CMIS staff keep using CMIS. Neither application has to call CMS. CMS is the only new caller, and it only reads.

## What already exists

| Piece | Where |
| --- | --- |
| Systems audit trail page | `cms-ppa/pages/audit-trail.html` |
| List API, saved copy only | `GET /api/audit-trail` |
| Refresh, runs a sync then returns the copy | `POST /api/audit-trail/refresh` |
| Scheduled sync | `php artisan audit:sync`, every 15 minutes |
| Stored rows | `audit_records` in the CMS database |
| Per-source result | `audit_source_statuses` |

The page never contacts UAMS or CMIS. Only the sync does.

UAMS already loads its own audit screen with:

`POST /ppa-api-uams/wsv1/api/getAuditTrail`

CMIS already loads its own audit screen with:

`POST /ppa-cmis-api_origin/wsv1/api/getAuditTrail`

On each server that PHP action runs `getAuditTrail()`, which reads that server's local `audit_trail` table and returns at most 2,000 rows. The folder name on a developer machine can differ from the path on the live host. Use the path the live audit page actually requests.

## Confirm the live URLs before changing CMS

On a machine that can open the live sites:

1. Sign in to UAMS and open its Audit Trail page.
2. In the browser network panel, find the `getAuditTrail` request. Copy the full URL, including scheme and host.
3. Do the same on CMIS.
4. From the CMS server, confirm that same URL answers. A successful body has `"status": "SUCCESS"` and a `payload` array. A network error, a login page, or an HTML error page means the CMS server cannot use that URL yet.

Do not point `AUDIT_UAMS_HOST` or `AUDIT_CMIS_HOST` at `uams.probation.gov.ph` or `eppcmis.probation.gov.ph` and expect MySQL. Those names are the websites. MySQL stays on each application server and is not part of this connection.

## What to build in CMS

Replace the production read for UAMS and CMIS inside `App\Services\DatabaseAuditTrailReader`. Keep `App\Services\SyncAuditTrail`, the `audit_records` table, and both HTTP endpoints as they are.

For each source, POST to the confirmed URL with an empty JSON object and `Accept: application/json`. The existing action ignores the body and runs the stored procedure. Set a connect timeout and a response timeout. Do not retry a request that already returned a body.

Read `payload`. Skip the source when `status` is not `SUCCESS` or `payload` is missing. Map each row into the existing upsert. On any failure, leave the rows already saved for that source and store the error on `audit_source_statuses`, which is what the page already shows.

Suggested settings, read with `config()`, not `env()`, outside config files:

```text
AUDIT_UAMS_URL=https://uams.example/ppa-api-uams/wsv1/api/getAuditTrail
AUDIT_CMIS_URL=https://cmis.example/ppa-cmis-api_origin/wsv1/api/getAuditTrail
```

Replace the example hosts with the URLs copied from the live audit pages. Leave a URL empty to skip that source. Local development can keep the current MySQL settings (`AUDIT_UAMS_DATABASE=ppa_sso`, `AUDIT_CMIS_DATABASE=ppa_cmis`) until the HTTP reader is the one deployed to production.

PPIS has no `audit_trail` table. Leave it out of this connection.

## Fields to store

Take these fields from each payload row:

| CMS column | Source field |
| --- | --- |
| `external_id` | `audit_trail_id` |
| `occurred_at` | `created_date`, read as Asia/Manila and stored in UTC |
| `actor_name` | `USER_FULLNAME`. If that is empty, join `USER_FNAME`, `USER_MNAME`, and `USER_LNAME`. If those are empty, use `USER_EMAIL`. |
| `user_level` | `USER_LEVEL_NAME` |
| `field_office` | `FIELD_OFFICE` |
| `module` | `module` |
| `action` | `action` |
| `source` | `uams` or `cmis` |

Do not store `IP_ADDRESS`, passwords, or session fields. The UAMS screen labels one column with the email field; the stored procedure still returns `USER_FULLNAME` where that column exists. Prefer the full name.

Upsert on `source` plus `external_id`. A later sync updates the same row instead of inserting a duplicate. Rows older than the 2,000 returned by the procedure are not copied on the first run. Later runs add rows that enter that latest window.

## Deploy

1. Deploy the CMS build that contains the HTTP reader.
2. Set `AUDIT_UAMS_URL` and `AUDIT_CMIS_URL` in the production environment.
3. Run `php artisan config:cache` if production caches config.
4. Run `php artisan audit:sync` once by hand.
5. Confirm the command prints a saved count for UAMS and for CMIS. A warning for one source must not erase the other source's rows.
6. Sign in to CMS as an administrator, open Systems audit trail, and confirm both systems appear. Use Refresh once and confirm the "last read" time changes.

The scheduler already runs `audit:sync` every 15 minutes. The server that runs `php artisan schedule:run` must be the same server that can reach both URLs.

## Check after go-live

- UAMS and CMIS audit pages still load on their own servers.
- CMS shows new UAMS and CMIS rows after the next sync, without anyone using those systems differently.
- A stopped UAMS API leaves the last UAMS rows in CMS and shows an error for UAMS only.
- The CMS list request does not appear in the UAMS or CMIS access logs. Only the sync request does.
- `audit_records` has no IP address column and the API response has no IP address field.

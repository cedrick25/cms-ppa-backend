# Deploy CMS-PPA with Docker

This folder runs the CMS-PPA API and the browser site with Docker Compose and a `.env` file, the same shape used for the live PPIS Java backends.

On this Ubuntu host, Docker Engine runs the compose file. Docker Desktop is the Windows and desktop app around that same engine. Ubuntu 26.04 on `192.168.1.187` does not ship Docker Desktop, so the install below uses Docker Engine and the Compose plugin. The `.env` names and the compose services are what you change when you deploy again.

The API is the Java program in `cms-ppa-backend`. The site is the static files in `cms-ppa`. MySQL runs as the `db` service because this machine did not already have a database. On a live PPIS host that already has MySQL, remove the `db` service and set `DB_HOST` to that MySQL host (often `host.docker.internal`).

## 1. Install Docker Engine

On Ubuntu, from an account that can use `sudo`:

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
```

Sign out and back in so the `docker` group applies. Check:

```bash
docker version
docker compose version
```

## 2. Create the folder

```bash
mkdir -p ~/cms-docker/api ~/cms-docker/web
git clone https://github.com/cedrick25/cms-ppa.git ~/cms-ppa
```

Copy these files into `~/cms-docker`:

- `docker-compose.yml`
- `.env` (from `.env.example`)
- `api/Dockerfile`
- `api/cms-ppa-api.jar`
- `web/Dockerfile`
- `web/nginx.conf`
- `web/config.js`

Build the JAR on a machine that has JDK 25, from `cms-ppa-backend/spring-api`:

```powershell
.\gradlew.bat bootJar
```

The JAR is `spring-api\build\libs\cms-ppa-api.jar`. Copy it to `cms-docker/api/cms-ppa-api.jar`.

The API container runs `techcedd/cms-ppa-api:2.0.1` from Docker Hub. Build and push that image from a machine logged in as `techcedd`, with the JAR in `cms-docker/api`:

```bash
docker build -t techcedd/cms-ppa-api:2.0.1 cms-docker/api
docker push techcedd/cms-ppa-api:2.0.1
```

## 3. Settings

```bash
cd ~/cms-docker
cp .env.example .env
```

Edit `.env`.

- `APP_KEY` encrypts system passwords. On a new database, generate one and keep it:

```bash
python3 -c "import os,base64; print('base64:'+base64.b64encode(os.urandom(32)).decode())"
```

Paste the line as `APP_KEY=`. If this database already has CMS-PPA data, use the existing key. A new key makes saved system passwords unreadable.

- `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`, and `MYSQL_ROOT_PASSWORD` are the MySQL service in this compose file. Leave `DB_HOST=db` and `DB_PORT=3306`.
- `CORS_ALLOWED_ORIGINS` is the site address browsers use, for example `http://192.168.1.187`.
- Leave `AUDIT_UAMS_DATABASE`, `AUDIT_CMIS_DATABASE`, and `AUDIT_PPIS_DATABASE` blank until those read-only databases are reachable from this host.

`web/config.js` is mounted over the cloned `js/config.js`. It points the browser at this server's API. nginx on port 80 forwards `/api` to the API container:

```javascript
window.CMS_API_BASE = "/api";
```

The GitHub checkout itself stays unchanged.

## 4. Start

From `~/cms-docker`:

```bash
docker compose up -d --build
```

Wait until the database is healthy and the API answers:

```bash
docker compose ps
curl -s http://127.0.0.1/up
```

The response is `{"status":"ok"}`.

The first start creates the CMS-PPA tables when `users` is missing. It does not rebuild tables that are already there.

## 5. First administrator

Do this once. Put the password only in the environment for this command.

The password must be at least 12 characters and include an upper-case letter, a lower-case letter, and a number.

```bash
cd ~/cms-docker
docker compose run --rm \
  -e CMS_ADMIN_NAME="CMS Administrator" \
  -e CMS_ADMIN_USERNAME="admin" \
  -e CMS_ADMIN_EMAIL="admin@cms.local" \
  -e CMS_ADMIN_PASSWORD="replace-this-password" \
  api create-user
```

Do not write `CMS_ADMIN_PASSWORD` into `.env`.

## 6. Open the site

From another computer on the same network, open:

`http://192.168.1.187`

Sign in with the administrator account. The API is also on port 8080: `http://192.168.1.187:8080/up`.

## 7. Update a later build

1. Build a new `cms-ppa-api.jar`, place it in `cms-docker/api`, then build and push `techcedd/cms-ppa-api:2.0.1`.
2. From `~/cms-ppa` run `git pull`.
3. From `~/cms-docker` run `docker compose pull api` and `docker compose up -d`.

The MySQL data stays in the `cms-mysql` volume. Do not delete that volume if you need the accounts and credentials.

## 8. Stop

```bash
cd ~/cms-docker
docker compose down
```

`docker compose down` keeps the database volume. `docker compose down -v` deletes it.

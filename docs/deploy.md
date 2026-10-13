# Deploying ERP-Cafe on a Windows machine with Cloudflare

This guide puts the whole app on an always-on Windows PC and makes it reachable from the internet
through Cloudflare, without opening any port on your router.

```
browser ──HTTPS──▶ Cloudflare ◀──tunnel── cloudflared ─▶ nginx (frontend) ─▶ backend ─▶ postgres
                                          └──────────────── all on the Windows PC, in Docker ─────┘
```

- **Docker** runs each part of the app in its own container. The four containers are described in
  `docker-compose.yml` at the repo root.
- **cloudflared** is Cloudflare's small program. It connects *out* from your PC to Cloudflare, and
  Cloudflare sends visitors back down that connection. Because the connection starts on your side,
  nothing on your router needs to change, and your home IP address stays hidden.
- **Cloudflare provides HTTPS.** The app needs HTTPS: the cookie that keeps people signed in is
  marked `Secure`, and browsers only send it back over HTTPS.

There are two ways to run the tunnel:

| | Quick Tunnel (start here) | Named tunnel (going live) |
|---|---|---|
| Needs | nothing | a Cloudflare account and a domain (about $10 a year) |
| Address | random, such as `https://calm-river-fox.trycloudflare.com` | yours, such as `https://erp.yourcafe.com` |
| Address changes | every time the tunnel container restarts, including after a reboot | never |
| Meant for | demos and testing | real use |

Part 1 sets up a demo on a Quick Tunnel. Part 2 moves to a named tunnel with backups when you use it
for real.

All commands below are for **PowerShell**. Run them from the folder you cloned the code into.

---

## Part 1: a demo on a Quick Tunnel

### 1. Prepare the Windows PC

1. **Install WSL 2.** This is the small Linux system Docker uses on Windows. Open PowerShell as
   Administrator, run the command below, then restart the PC.
   ```powershell
   wsl --install
   ```
2. **Install Docker Desktop** from docker.com. Then, in its Settings:
   - General: tick **Use the WSL 2 based engine**.
   - General: tick **Start Docker Desktop when you sign in to your computer**.
3. **Install Git for Windows** from git-scm.com. The default options are fine.
4. **Stop the PC from sleeping.** Go to Settings → System → Power (or Power & battery) → Screen and
   sleep. Set "When plugged in, put my device to sleep after" to **Never**. The screen may still turn
   off.
5. **Make the app come back after a restart.** Docker Desktop starts only after someone signs in to
   Windows. After a Windows Update restart, the app stays down until you sign in. You can either:
   - sign in yourself after each restart, or
   - turn on automatic sign-in with Microsoft's **Autologon** tool (from Sysinternals). If you do,
     anyone who can touch the PC gets your desktop, so keep it somewhere private.

   Also set Settings → Windows Update → Advanced options → **Active hours** to cover café opening
   times, so updates restart the PC outside them.

### 2. Get the code

```powershell
git clone https://github.com/Baoteiha/ERP-System.git C:\erp-cafe
cd C:\erp-cafe
```

This checks out the `main` branch. To run another branch, for example `dev`, run `git switch dev`.

### 3. Fill in the settings (`.env`)

The app reads its passwords and options from a file named `.env`. **Never commit this file**: the
repository is public. `.gitignore` already keeps it out of git.

```powershell
Copy-Item .env.example .env
notepad .env
```

You need three long random secrets. Run this line three times and paste one result into each of
`POSTGRES_PASSWORD`, `APP_DB_PASSWORD` and `JWT_SECRET`:

```powershell
$b = New-Object byte[] 36; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

Then set:

| Setting | Value |
|---|---|
| `ADMIN_EMAIL` | your email. This becomes the owner account. |
| `ADMIN_PASSWORD` | a strong password. The sign-in page will be on the internet. |
| `REFRESH_COOKIE_SECURE` | `true` |
| `COMPOSE_PROFILES` | `quick-tunnel` |

Keep a copy of `.env` in your password manager. Two things to know:

- **The passwords are fixed after the first start.** `POSTGRES_PASSWORD`, `APP_DB_PASSWORD` and
  `ADMIN_PASSWORD` are used only when the database is created. Changing them in `.env` later does
  not change them in the database, and the backend then fails to connect.
- **Changing `JWT_SECRET` signs everyone out**, and nothing else breaks.

### 4. Start the app

```powershell
docker compose up -d --build
```

The first build takes several minutes, because it downloads Java and Node packages. Later builds are
faster. To check on it:

```powershell
docker compose ps
```

Wait until `backend` shows `healthy`. If it keeps restarting, see [Troubleshooting](#troubleshooting).

### 5. Find your address and sign in

```powershell
docker compose logs tunnel-quick | Select-String trycloudflare
```

Open the `https://….trycloudflare.com` address it prints, and sign in with `ADMIN_EMAIL` and
`ADMIN_PASSWORD`. Then change the password from inside the app.

On the PC itself you can also use `http://localhost:8095` in Edge or Chrome. Other computers on your
network cannot reach that address. That is deliberate: everyone else goes through Cloudflare's
HTTPS.

### 6. Day-to-day commands

| What | Command |
|---|---|
| Update to the latest code | `git pull` then `docker compose up -d --build` (automatic after [Part 3](#part-3-automatic-updates-cicd)) |
| See what is running | `docker compose ps` |
| Follow the backend's log | `docker compose logs -f backend` (Ctrl+C to stop following) |
| Stop everything (data is kept) | `docker compose down` |
| Start again | `docker compose up -d` |

> ⚠ **`docker compose down -v` deletes the database.** Only use it when you mean to wipe all data.

The Quick Tunnel address changes whenever the `tunnel-quick` container starts again, for example
after a reboot or a `down` / `up`. Run step 5 again to read the new one.

---

## Part 2: going live

### 7. Get a domain on Cloudflare

1. Create a free account at cloudflare.com.
2. Buy a domain in the dashboard under **Domain Registration → Register Domains**. A domain bought
   there already has its DNS on Cloudflare. A domain from another seller works too, but you must
   first switch its nameservers to Cloudflare's.

### 8. Create a named tunnel

Cloudflare's dashboard moves menus around. If a name below doesn't match, search the dashboard for
"Tunnels".

1. Open **Zero Trust** → **Networks** → **Tunnels** → **Create a tunnel** → choose **Cloudflared**.
2. Give it a name, for example `erp-cafe`.
3. The next page shows install commands. **Don't run them.** Copy only the token: the long string
   after `--token`.
4. Add a public hostname (also called a *published application route*):
   - Subdomain: `erp` (or anything you like). Domain: yours.
   - Service type: **HTTP**. URL: **`frontend:80`**.
5. Save.

### 9. Switch the PC to the named tunnel and turn on backups

First stop everything **while `.env` still says `quick-tunnel`**, so the Quick Tunnel container is
removed too:

```powershell
docker compose down
```

Then edit `.env`:

```
COMPOSE_PROFILES=tunnel,backup
TUNNEL_TOKEN=<the token from step 8>
APP_PUBLIC_URL=https://erp.yourcafe.com
```

Start again:

```powershell
docker compose up -d
```

The app is now at your own address. It stays the same across reboots.

**Starting with a clean database for real use:** the demo database still holds whatever you tried
out. To start empty, run `docker compose down -v` (this deletes all data) and then
`docker compose up -d`. A fresh owner account is created from `ADMIN_EMAIL` and `ADMIN_PASSWORD`.

### 10. Optional: a Cloudflare sign-in screen in front of the app

Cloudflare Access can ask for an email code before anyone even sees the app's sign-in page. It's
free for up to 50 users.

To set it up: in Zero Trust → **Access** → **Applications** → **Add an application** →
**Self-hosted**, enter your hostname. Then add a policy that allows your staff's email addresses.
Each person types their email and receives a one-time code. The downside is one extra step for staff
each time their Access session expires.

### 11. Backups

With `backup` in `COMPOSE_PROFILES`, a dump of the database lands in `C:\erp-cafe\backups` once a day.
The first one is written when the container starts. Dumps older than `BACKUP_KEEP_DAYS` (default 14)
are deleted. File names use UTC time.

- **Copy them off the PC.** A backup on the same disk is lost with the disk. A OneDrive or Google
  Drive folder that syncs `C:\erp-cafe\backups`, or a regular copy to a USB drive, is enough.
- **Test a restore once.** This test uses a separate scratch database, so live data is not touched.
  Use the name of a real file in `backups`:
  ```powershell
  docker compose exec postgres createdb -U erpcafe restore_test
  docker compose run --rm --entrypoint pg_restore backup --dbname=restore_test --exit-on-error /backups/erpcafe-20261012-0300.dump
  docker compose exec postgres psql -U erpcafe -d restore_test -c "select count(*) from flyway_schema_history"
  docker compose exec postgres dropdb -U erpcafe restore_test
  ```
  A number from the third command means the backup works. (`erpcafe` is the default
  `POSTGRES_USER`. Use yours if you changed it.)

**Restoring for real** replaces the live database with the backup. Everything entered since that
backup is lost.

```powershell
docker compose down -v               # stops everything and DELETES the current database
docker compose up -d postgres        # a new, empty database
docker compose run --rm --entrypoint pg_restore backup --dbname=erpcafe --exit-on-error /backups/<file>.dump
docker compose up -d                 # start the rest
```

Run the restore before starting the backend. If the backend runs first, it creates empty tables and
the restore collides with them.

---

## Part 3: automatic updates (CI/CD)

Once this is set up, you never update the PC by hand for code changes:

```
push to dev  ──▶ GitHub runs the tests (CI)
push to main ──▶ GitHub runs the tests ──▶ passed? builds the two images and stores them, privately,
                                             in GitHub's registry (ghcr.io)
                                                   │
             the PC's Watchtower asks every 5 minutes "anything new?" ──▶ swaps in the new images
```

- **CI** (continuous integration): `.github/workflows/ci.yml` runs the backend tests and the
  frontend lint, tests and build on every push to `dev` or `main` and on every pull request. You see
  the result on GitHub under the **Actions** tab: a green tick means it passed, a red cross means it
  failed.
- **CD** (continuous deployment): only a push to `main` that passes publishes images. So **pushing
  to `main` is deploying.** Keep working on `dev`, and move `main` forward when you want the café to
  get the new version.
- **Each update means about a minute of downtime** while the new backend starts. Database changes
  (Flyway migrations) run by themselves during that minute. So push to `main` outside opening hours,
  and have backups on.

### 12. Make a read-only token for the PC

The images are private, so the PC must sign in to download them.

1. On GitHub: your profile picture → **Settings** → **Developer settings** → **Personal access
   tokens** → **Tokens (classic)** → **Generate new token (classic)**. GitHub's registry needs a
   *classic* token.
2. Note: `erp-cafe server`. Expiration: **1 year**. Put a reminder in your calendar, because when the
   token expires, updates quietly stop.
3. Tick **only `read:packages`**. The token can then download your images and do nothing else.
4. **Generate token** and copy it right away. GitHub shows it only once.

### 13. Switch the PC to the published images

The images exist only after the first push to `main` that passes. Check the **Actions** tab on
GitHub: the "Publish" jobs should be green.

First check that the PC's processor matches the images, which are built for `amd64` (ordinary
Intel and AMD PCs):

```powershell
docker version --format '{{.Server.Arch}}'
```

It should print `amd64`. If it prints anything else, stop and ask, because the images won't run.

Then, in `C:\erp-cafe`:

```powershell
git pull
docker login ghcr.io -u Baoteiha
```

When it asks for a password, paste the token. Then edit `.env`:

```
COMPOSE_PROFILES=tunnel,autoupdate
GHCR_USER=Baoteiha
GHCR_TOKEN=<the token from step 12>
```

Add `backup` to `COMPOSE_PROFILES` as well once you have real data. Then:

```powershell
docker compose pull
docker compose up -d
docker compose logs watchtower
```

`pull` downloads the published images. From then on, `up` uses them and no longer builds anything
on the PC. The Watchtower log should show it starting and scheduling its checks, with no
`unauthorized` errors.

### Day to day with CI/CD

| What | How |
|---|---|
| Test a change | Push to `dev`, then watch the **Actions** tab. |
| Deploy | On your Mac: `git switch main`, `git merge --ff-only dev`, `git push`, `git switch dev`. About 5–10 minutes after the Actions run turns green, the PC runs the new version. |
| Undo a bad deploy | `git revert <the bad commit>` on `main` and push. CI publishes the previous code as the newest version. |
| `docker-compose.yml` or `.env.example` changed | Watchtower only swaps images, so on the PC run `git pull` then `docker compose up -d`. |
| Watch the updates | `docker compose logs watchtower` |

---

## Troubleshooting

**`backend` keeps restarting.** Read its log with `docker compose logs --tail=80 backend`. The cause
is usually near the bottom:
- `UnknownHostException: postgres`: the database container is not running. Run
  `docker compose up -d`.
- `password authentication failed`: a password in `.env` was changed after the database was
  created. Put the old one back. Otherwise, if the data doesn't matter yet, run
  `docker compose down -v` to start fresh.
- A message naming a missing setting: fill it in `.env`.

**`/bin/sh^M: bad interpreter`, or errors mentioning `\r`.** The files were checked out with Windows
line endings. `.gitattributes` prevents this for clones made after it was added. To fix an older
clone, run the commands below. `.env` is safe, because git does not track it.
```powershell
git rm -rq --cached .
git reset --hard
```

**The Quick Tunnel prints no address, or the address never loads.** Some networks block the port
cloudflared prefers (UDP 7844). In `docker-compose.yml`, change the `tunnel-quick` command to
`tunnel --no-autoupdate --protocol http2 --url http://frontend:80` and run `docker compose up -d`.

**Signed out every 15 minutes.** `REFRESH_COOKIE_SECURE` must be `true`, and the site must be opened
through its `https://` address.

**The site stopped updating, or `docker compose pull` says `unauthorized` or `denied`.** The
GitHub token has probably expired, or it lacks `read:packages`. Make a new one (step 12), put it
in `GHCR_TOKEN` in `.env`, run `docker login ghcr.io -u Baoteiha` again, then
`docker compose up -d`.

**The named tunnel shows as down in the dashboard.** Run `docker compose logs tunnel`. An empty or
wrong `TUNNEL_TOKEN` is the usual cause. Check that `.env` has the token and that
`COMPOSE_PROFILES` includes `tunnel`.

# Cloud File Hub — PHP 8.2 Migration

This project is a Node-free PHP 8.2+ migration of the supplied React/Express/TypeScript file server. The runtime frontend is HTML/CSS/vanilla JavaScript and the backend uses PHP, PDO, MySQL/MariaDB, filesystem APIs, ZipArchive, PHP session authentication, public share tokens, thumbnails, and WebDAV.

## Install

For Android/KSWEB video thumbnails, no FFmpeg installation is required; compatible videos are decoded by the browser.

1. Point the web server document root at `public/`. Apache needs `mod_rewrite`; nginx should route unknown paths to `public/index.php`.
2. Copy `.env.example` to `.env` and change the database credentials.
3. Create/import the database with `database/schema.sql`, then create the first
   administrator with `php tools/create-admin.php admin` — the schema seeds no
   account (see **Login**).
4. Ensure the PHP/web-server user can read/write `storage/` and `storage/.thumbnails/`.
5. Large uploads use the resumable chunk API, so `upload_max_filesize` and `post_max_size` only need to exceed `UPLOAD_CHUNK_MB` (8 MB by default). A practical PHP configuration is `upload_max_filesize=16M` and `post_max_size=20M`. The application-level per-file limit defaults to 2 GB.
6. Development: `php -S 127.0.0.1:8000 -t public`.

## Tests

```bash
php tests/run.php
```

The suite is plain PHP check scripts — no framework or Composer install is
needed. A script fails the run if it exits non-zero or emits any
warning/notice. The database-schema checks read `database/migrate.php` rather
than connecting, so no MySQL server is required.

## Required PHP extensions

`pdo`, `pdo_mysql`, `fileinfo`, `json`, `mbstring`; `zip` for multi-file ZIP downloads; `gd` for image thumbnails. OpenSSL is recommended. Remote storage protocols may additionally require `ftp`, `ssh2`, cURL, or an OS SMB client when those adapters are enabled.

## Migration map

- `server/index.ts`, `routes.ts` → `public/index.php` front controller/router.
- `server/config.ts` → `config/config.php` + `.env`.
- `server/auth.ts` → `src/Services/Auth.php` session authentication with CSRF protection.
- `server/fileRoutes.ts` → file API routes in `public/index.php` + `FileService`.
- `server/webdav.ts` → `src/Services/WebDav.php`.
- `server/shares.ts` → MySQL-backed `share_links` routes. Unlike the original in-memory Map, links survive restarts.
- `server/thumbnails.ts` → browser-decoded video frames plus GD/WebP image thumbnails and `.thumbnails` cache.
- `server/storage.ts`, `shared/schema.ts` → PDO repository + `database/schema.sql`.
- `client/src/App.tsx`, `file-manager.tsx`, `upload-dialog.tsx` → `views/pages/app.php`, `public/assets/js/app.js`, `public/assets/css/app.css`.
- React Query/Wouter/Radix/shadcn/Tailwind/Vite → native fetch, History/URL routing, HTML controls, deployable CSS; no Node build is required.

## Behaviour implemented differently

Share links are stored in MySQL instead of memory, improving restart persistence. The React component tree is replaced with server-delivered HTML and browser JavaScript. Image thumbnail generation uses GD. Video thumbnails are generated client-side with the browser's native video decoder and Canvas; the server only provides authenticated range-capable media streaming. WebDAV remains filesystem-backed and keeps the original methods: OPTIONS, PROPFIND, GET, PUT, DELETE, MKCOL and MOVE.

## Current limitation

The supplied project contains FTP/SFTP/SMB/HTTP storage-adapter logic. Server records, activation/default management, local file management, uploads, sharing, thumbnails and WebDAV are migrated. Protocol-specific remote browse/test/upload implementations require the corresponding PHP extension or system client and are not enabled by default in this portable build; the UI identifies configured remote servers rather than pretending those transports work without their runtime dependencies.


## Subdirectory installation

The application automatically detects its URL base path. It can run at the web root (for example `/`) or in a subdirectory such as `/Cloud-File-Hub-PHP/`. API requests, assets, SPA navigation and generated share links use the detected base path.

For Apache, enable `mod_rewrite` and `AllowOverride All` for the project directory. For PHP's built-in server from the project directory, use `php -S 0.0.0.0:8000 -t public` and open `http://localhost:8000/`.

## Subdirectory installation

The project root now contains `index.php`, so an installation such as
`http://localhost:8000/Cloud-File-Hub-PHP/` can enter the application directly.
On Apache, `mod_rewrite` and `.htaccess` overrides must be enabled for clean
application/API URLs. The web server must permit PHP execution in the project.

For PHP's built-in server from the parent directory, use:

```bash
php -S 0.0.0.0:8000
```

and open `/Cloud-File-Hub-PHP/`. Alternatively, from inside the project use:

```bash
php -S 0.0.0.0:8000 -t public
```

and open `/`.

## Login

Authentication is database-backed and uses PHP sessions. **No account is created
for you.** Since Phase 4 the schema deliberately seeds no user, so create the
first administrator yourself:

```bash
php tools/create-admin.php admin
```

The tool prompts for a password (minimum 12 characters), stores an Argon2id hash
where available, and gives the account the `admin` role. Run it again with the
same username to reset that password.

Passwords are stored using PHP-compatible password hashes and are re-hashed to
the preferred algorithm on the next successful login. The browser no longer
receives a `WWW-Authenticate` header, so native Basic Auth popups are not used.

## Upgrading an older Cloud File Hub database (v5)

Do not re-import `schema.sql` over an existing installation. Copy your existing `.env` into this release, back up the database, then run:

```bash
php database/migrate.php
```

The migration creates missing tables/columns/indexes without deleting existing
rows, and creates a local storage server only when there are no storage-server
rows. It covers every schema change through Phase 8 — including the
`login_attempts` and `security_events` tables and the `users.role` column — so
the files in `database/migrations/` do not need to be applied separately. An
existing account named `admin` is promoted to the `admin` role the first time
the role column is added.

The migration creates no user accounts. If the database has none, create one
with `php tools/create-admin.php admin`.


## Portable routing fix (v6)

The UI and API now use the root `index.php` front controller with a `route` query parameter. This means a subdirectory install such as `/Cloud-File-Hub-PHP/` works even when URL rewriting is unavailable, including PHP's built-in server started from a parent directory. Apache clean URLs remain supported by `.htaccess`.

From the parent directory you may run `php -S 0.0.0.0:8000` and open `/Cloud-File-Hub-PHP/`. From the project directory, `php -S 0.0.0.0:8000 router.php` is also supported.

## Upload system

The file manager uses an in-application upload dialog rather than opening a hidden file input directly. The dialog shows the current target directory, selected files and sizes, configured limits, upload progress, transferred bytes, and explicit success/error states.

Upload progress is implemented with `XMLHttpRequest` because its `upload.onprogress` event exposes bytes transferred while the request body is being sent. Authentication remains session-based and the request includes the application's CSRF token. After a successful upload the current directory is refreshed automatically.

Both layers validate uploads. Browser-side checks provide immediate feedback for `MAX_UPLOAD_FILES` and `MAX_UPLOAD_MB`; `public/index.php` repeats those checks and handles PHP's native `UPLOAD_ERR_*` conditions. Server validation is authoritative. The effective PHP configuration (`upload_max_filesize` and `post_max_size`) must be large enough for the limits configured in `.env`.

Relevant files:

- `views/pages/app.php` — upload dialog markup and configured limit values.
- `public/assets/js/app.js` — selection validation, progress events, status messages, cancellation, and directory refresh.
- `public/assets/css/app.css` — responsive dialog, progress, and success/error presentation.
- `public/index.php` — upload endpoint and server-side validation/error translation.


## Navigation URL handling

Application page links use the installation base directory rather than exposing
`index.php` in the browser URL. For example, a subdirectory installation uses
`/Cloud-File-Hub-PHP/` for Files and query-string routes for other application
pages. The front controller remains an internal implementation detail.

Legacy requests to `/index.php` and `/public/index.php` are normalized to the
Files route for compatibility with cached links and older package versions.
API requests continue to use the portable `route` query parameter so the
application works when installed in a subdirectory without clean-URL rewriting.


## Integrated file previews

The Files view now includes an authenticated preview dialog.

Supported inline previews:

- Images: JPEG, PNG, GIF, WebP, BMP, SVG and AVIF where supported by the browser.
- Video: MP4, WebM, OGV, MOV and M4V where the browser has a compatible codec; thumbnails are generated client-side without FFmpeg.
- Audio: MP3, WAV, OGG, M4A, AAC and FLAC where browser codec support is available.
- PDF: embedded using the browser PDF viewer.
- Text/source: common text, web, PHP, SQL, configuration and source-code extensions.

The `/api/files/preview` endpoint uses the same authenticated filesystem
sanitisation as downloads and only permits MIME types suitable for inline
display. Text previews are escaped before rendering and are capped at 500 KB in
the UI to avoid locking the browser on unusually large files.

Existing image thumbnails continue to use the on-disk thumbnail cache. Full
media is loaded only after the user requests a preview.


## Resumable large-file uploads

Cloud File Hub now uploads files in configurable chunks rather than one large
`multipart/form-data` request. The default application limit is **2 GB per
file** (`MAX_UPLOAD_MB=2048`) and the default chunk size is 8 MB.

The protocol is:

1. `POST /api/uploads/init` creates or resumes a staging session.
2. `PUT /api/uploads/chunk` sends one raw chunk at the server-confirmed offset.
3. `GET /api/uploads/status` returns the confirmed byte offset for recovery.
4. `POST /api/uploads/complete` validates the byte count and moves the staged
   file into its destination.
5. `DELETE /api/uploads/cancel` removes an explicitly cancelled staging upload.

Transient chunk failures are retried with exponential backoff. Before a retry,
the browser re-queries the server offset, so a lost HTTP response does not cause
the same bytes to be appended twice. Retrying the same file selection resumes
from the staged offset while the staging session still exists.

### Conflict handling

`UPLOAD_CONFLICT` can be `rename`, `overwrite`, or `reject`. The upload dialog
also exposes this choice per upload batch. `rename` is the default and produces
names such as `video (1).mp4`. `overwrite` still respects `ALLOW_OVERWRITE`.

### Abandoned upload cleanup

Incomplete data is stored under the application-owned `storage/uploads` directory by default. Sessions older than
`UPLOAD_ABANDON_HOURS` (24 hours by default) are removed automatically whenever
a new upload starts. Administrators may also invoke the authenticated
`POST /api/uploads/cleanup` endpoint. For low-traffic installations, a scheduled
request or CLI maintenance task can invoke cleanup periodically.

### Relevant environment settings

```ini
MAX_UPLOAD_MB=2048
MAX_UPLOAD_FILES=20
UPLOAD_CHUNK_MB=8
UPLOAD_RETRY_COUNT=3
UPLOAD_ABANDON_HOURS=24
UPLOAD_CONFLICT=rename
```

`MAX_UPLOAD_MB=2048` is an application policy limit, not a requirement to allow
2 GB PHP request bodies. Keep PHP request limits modestly above the configured
chunk size. The destination filesystem and PHP build must support files larger
than 2 GB; a 64-bit PHP runtime and a large-file-capable filesystem are
recommended.


## v10.1 upload staging repair

Resumable upload staging is no longer derived from `ROOT_DIR`. It defaults to
`storage/uploads`, keeping temporary upload state separate from the served file
tree. Set `UPLOAD_STAGING_DIR` to an absolute writable directory to override it.

Metadata is written atomically through a temporary file and rename. Corrupt
metadata left by an interrupted request is automatically discarded when the
same upload is initialised again. The service also performs a real write probe
so permission failures are reported before chunk transfer begins.

The PHP/web-server user must have write permission to both `storage/uploads`
and the configured file-server destination.


## v10.2 Android shared-storage compatibility

Upload staging now tests actual create/write/delete operations instead of
requiring Unix-style `is_writable()` behaviour or advisory `flock()` support.
Metadata temporary files are written without `LOCK_EX` and are still committed
using an atomic rename. This supports PHP installations under Android paths such
as `/storage/emulated/0/htdocs`.


## v10.3 large video/audio preview streaming

The authenticated preview endpoint now implements HTTP byte-range streaming for
large media. Browsers may request only the sections they need for metadata,
playback and seeking instead of forcing PHP to transmit the entire file.

Supported behaviour includes:

- `GET` and `HEAD` preview requests.
- `Accept-Ranges: bytes`.
- Single `Range: bytes=start-end` requests.
- Open-ended and suffix byte ranges.
- `206 Partial Content` with `Content-Range`.
- `416 Range Not Satisfiable` for invalid ranges.
- Bounded 1 MiB PHP streaming buffers.
- Early termination when the client disconnects.

This improves large MP4/video and audio previews while preserving the existing
authenticated path validation and MIME restrictions.


## v11 modern file-management UX

The Files screen now supports drag-and-drop upload selection, a visible upload
queue with per-file progress, persistent grid/list views, persistent sorting,
multi-select bulk download/delete controls, contextual file menus, improved
breadcrumbs, and application-owned confirmation/input dialogs. File cards also
support desktop right-click menus and double-click open/preview behaviour.

The existing resumable 2 GB upload protocol and HTTP range media streaming are
unchanged; the new interface sits on top of those stable v10.3 APIs.


## v11.1 upload UI/retry fixes

- File-toolbar buttons now use a consistent control height on mobile.
- Recoverable chunk retries no longer display the alarming
  "Connection interrupted" message while an upload is successfully continuing.
- During a transient retry, the active queue item displays "Retrying chunk…".
- Genuine failures are still reported by the upload error state after the retry
  budget is exhausted; resumable upload behaviour is unchanged.


## v12 Phase 2 — Core security foundation

Centralized HTTP/browser security policy, hardened sessions, session expiry,
password rehash migration, stronger CSRF checks, nonce-based CSP/security
headers, and explicit production HTTPS/reverse-proxy/HSTS configuration have
been added. See `SECURITY.md`.

This phase does not claim production readiness.


## v12 Phase 3 — Filesystem hardening

Strict storage-root containment, traversal rejection, symlink denial, safer
mutation destinations, root deletion protection and hardened WebDAV path
handling have been added. See `SECURITY.md` and
`deploy/nginx-security.conf.example`.


## v12 Phase 4 — Authentication hardening

Adds database-backed login throttling, per-user and per-IP attempt limits,
HMAC-pseudonymised throttle keys, periodic authenticated session-ID rotation,
Argon2id preference/automatic password rehashing, and removes the predefined
admin account from fresh-install schema.

Existing installations pick this up from `php database/migrate.php`.
Administrators are created with `php tools/create-admin.php admin`.


## v12 Phase 5 — API hardening

Adds bounded JSON parsing, media-type enforcement, validation helpers, bulk
request limits, stable JSON errors/request IDs, safe 5xx responses and
predictable API route handling.


## v12 Phase 6 — Authorization hardening

Adds viewer/editor/admin roles, capability enforcement and per-user resumable
upload-session ownership. Existing installations pick this up from
`php database/migrate.php`.


## v12 Phase 6.1 — basePath hotfix

Restores `Http::basePath()`, which is required by `public/index.php` when
CloudHub is installed in a subdirectory such as `/Cloud-File-Hub-PHP`.
A regression test covers root, subdirectory, and `/public` entry-point paths.


## v12 Phase 7 — Security audit trail

Adds a database-backed `security_events` audit trail with request IDs, user,
event type, outcome, IP, user agent and redacted JSON context. Login success/
failure, logout and key file mutations are recorded. Administrators can query
recent events through `/api/security/events`. Audit logging is best-effort and
does not break the primary operation if logging itself fails.

## v12 Phase 8 — Uploaded-content and deployment hardening

User-controlled active content is no longer rendered inline. HTML and other
script-capable text types download as attachments; inline preview is restricted
to media, PDF and plain text. Public share responses add `nosniff` and a
restrictive sandbox CSP. Apache rules additionally deny common backup, SQL,
log, INI and development/documentation artefacts.

Existing installations pick this up from `php database/migrate.php`.

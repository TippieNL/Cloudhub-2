# Cloud File Hub — PHP 8.2 Migration

This project is a Node-free PHP 8.2+ migration of the supplied React/Express/TypeScript file server. The runtime frontend is HTML/CSS/vanilla JavaScript and the backend uses PHP, PDO, MySQL/MariaDB, filesystem APIs, ZipArchive, PHP session authentication, public share tokens, thumbnails, and WebDAV.

## Install

For Android/KSWEB video thumbnails, no FFmpeg installation is required; compatible videos are decoded by the browser.

1. Point the web server document root at `public/`, and route unknown paths to
   `public/index.php`. Apache needs `mod_rewrite`; for nginx, adapt
   `deploy/nginx-security.conf.example`. This layout keeps `.env`, `config/`,
   `src/`, `database/`, `tools/` and `storage/` outside the web root, which is a
   stronger guarantee than any deny rule. If you must serve the project directory
   itself, Apache's bundled `.htaccess` denies those paths — other servers need
   the equivalent rules configured by hand.
2. Copy `.env.example` to `.env` and change the database credentials.
3. Create/import the database with `database/schema.sql`, then create the first
   administrator with `php tools/create-admin.php admin` — the schema seeds no
   account (see **Login**).
4. Ensure the PHP/web-server user can read/write `storage/` and `storage/.thumbnails/`.
5. Large uploads use the resumable chunk API, so `upload_max_filesize` and `post_max_size` only need to exceed `UPLOAD_CHUNK_MB` (8 MB by default). A practical PHP configuration is `upload_max_filesize=16M` and `post_max_size=20M`. The application-level per-file limit defaults to 2 GB.
6. Development: `php -S 127.0.0.1:8000 -t public`.

## Public share links

Any file can be handed out as a URL that works without an account.

**The link ends in the file's own name** — `…/share/{token}/holiday.mp4` — and
it hands over the file itself. That is what makes it work in an `<img>` tag, in
`curl -O`, and in the clients that decide what a link is by looking at its
extension; it is also the name a browser suggests when the recipient saves it,
where the old `/raw` suggested a file called `raw`.

The viewer page, with the name, the size, the expiry and a download button, is
still there under the bare token, and the Share dialog offers it beside the file
link as **Open preview page**.

| Route | Purpose |
|---|---|
| `/share/{token}/holiday.mp4` | The file itself, inline and range-capable — the link handed out |
| `/share/{token}` | Viewer page for media, direct download otherwise |
| `/share/{token}/download/holiday.mp4` | The bytes, as an attachment |
| `/share/{token}/raw`, `/share/{token}/download` | The original spellings; links made before names still work |

The name is decoration — the token is the whole credential — but it is not
allowed to lie: a URL edited to end in `invoice.pdf` is redirected to the name
the file actually has rather than served under a name of the sender's choosing.

One consequence worth knowing if you deploy behind your own rules: because the
file's name is now part of the path, `/share/` has to be exempted from the
"never serve `*.log`, `*.ini`, `*.sql`" rules, or sharing `notes.log` becomes a
403. The project's `.htaccess`, `router.php` and
`deploy/nginx-security.conf.example` all do this; a rule of your own needs the
same exemption.

Possession of the token is the credential, so these routes sit outside the
authenticated API guard and no session is started for a visitor — a share link
issues no cookie and leaves no session file behind.

Create, change the lifetime of, and revoke links from the **Share** button on any
file. Lifetimes range from one hour to never; `SHARE_EXPIRY_HOURS` sets the
default for new links. Revoking takes effect immediately. Administrators can list
every live link with `GET /api/shares/list`; creating and revoking a link is
recorded in the audit trail, readable through `GET /api/security/events`.

**What a share link does not do.** Only image, video and audio types render
inline. Everything else — documents, archives, and in particular script-capable
content such as HTML and SVG — is sent as an attachment, and the bytes always
carry `X-Content-Type-Options: nosniff` and a `sandbox` CSP, so a shared file can
never execute markup on the application's origin. Shared pages are served
`X-Robots-Tag: noindex, nofollow`.

Shared video and audio are streamed with HTTP byte ranges, so a recipient can
seek without downloading the whole file.

## Thumbnails

Image thumbnails are generated on demand with GD, capped at 300px, encoded as
WebP and cached under `storage/.thumbnails/images`. The cache key includes the
source file's modification time, so replacing a file produces a new key and the
stale entry is simply never read again.

The server has no video decoder. A video's frame is decoded by the first
browser that sees it and posted back to `POST /api/thumbnail/video`, after
which it is served from the same cache as any image — so a video is decoded
once for everyone, rather than re-fetched and re-decoded by every visitor on
every load. Contributed frames are validated as real WebP/JPEG/PNG images,
bounded to 256 KB and 1280px, and re-encoded to WebP before being stored.

`GET /api/files/list` reports `hasThumbnail` for each video, so the browser
knows whether to fetch a cached frame or decode one, without having to ask and
treat the failure as an answer. Requesting a video thumbnail that has not been
generated yet returns `404`.

Thumbnail URLs carry the file's modification time and are therefore immutable,
so they are served `Cache-Control: private,max-age=31536000,immutable` with an
`ETag` for the reload case. Images use native `loading="lazy"` and declare
intrinsic dimensions, and video frames are decoded through an
`IntersectionObserver` two at a time.

Read-only endpoints release the PHP session lock as soon as authorization has
been decided. Without that, PHP's exclusive per-session file lock serialises
every request in a gallery no matter how many workers are free — the single
largest cost in loading a folder of images.

To clear the cache, delete `storage/.thumbnails/images`; it is rebuilt on
demand.

## Tests

Two suites, and both run on every push (`.github/workflows/ci.yml`):

```bash
php tests/run.php        # 28 scripts pinning decisions in the source
php tests/http/run.php   # the API, over real HTTP against a real database
```

The first is plain PHP check scripts — no framework or Composer install is
needed, and a script fails the run if it exits non-zero or emits any
warning/notice. They are regression pins: they assert the source still *reads*
a certain way, which is what stops a later edit quietly undoing a decision, and
they have caught real reverts. The database-schema checks read
`database/migrate.php` rather than connecting, so this suite needs no MySQL.

The second asserts the application *works*. It starts its own server, signs in,
and makes real requests — sign-in and CSRF rejection, a chunked upload and a
byte-range fetch, share links created, reused and revoked, versions kept and
restored, trash and restore, and a viewer being refused a write. This one does
need a migrated database and an admin account, which the workflow sets up.

CI brings a MariaDB service container rather than a lighter stand-in: the
schema is MySQL-specific throughout — `information_schema` probes,
`ENGINE=InnoDB`, `ENUM`, prefix indexes — and testing a dialect nobody deploys
would prove very little. The Android job runs the JVM tests against that same
server, so the 13 live API tests run instead of skipping.

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

The application detects its own URL base path, so it runs at the web root (`/`)
or in a subdirectory such as `/Cloud-File-Hub-PHP/` with no configuration. API
requests, assets, in-app navigation and generated share links all follow the
detected base path.

Two entry points exist, and the one that runs decides where static assets are
requested from (see `Http::assetBase()`):

- **`public/index.php`** — used when the document root is `public/`. Preferred:
  it keeps `.env`, `config/`, `src/`, `database/` and `storage/` outside the web
  root entirely.
- **`index.php`** in the project root — used when the project directory itself
  is the document root, for example a shared host that serves a subdirectory.

For Apache, enable `mod_rewrite` and `AllowOverride All` so the bundled
`.htaccess` can route clean URLs and deny the application's internals. For nginx,
adapt `deploy/nginx-security.conf.example`. Any server must send unknown paths to
the front controller, otherwise clean URLs — including `/share/<token>` — will
404.

For PHP's built-in server, from inside the project directory:

```bash
php -S 0.0.0.0:8000 -t public      # open http://localhost:8000/
```

or, to serve the project from a parent directory as a subdirectory install:

```bash
php -S 0.0.0.0:8000 router.php     # open http://localhost:8000/
```

## Login

Authentication is database-backed and uses PHP sessions. **No account is created
for you.** Since Phase 4 the schema deliberately seeds no user, so create the
first administrator yourself:

```bash
php tools/create-admin.php admin
```

The tool prompts for a password (minimum 12 characters), stores an Argon2id hash
where available, and gives the account the `admin` role. Run it again with the
same username to reset that password. A role can be passed as a second
argument — `php tools/create-admin.php alice editor` — which is how non-admin
accounts were created before the Users screen existed.

## Installing as an app

CloudHub is a progressive web app. On Android, Chrome offers **Install** from
its menu, or the header shows an Install button once the browser decides the
app qualifies; on iOS use Share → Add to Home Screen, which is the only route
Safari offers. Installed, it launches without browser chrome, with its own
icon and a splash screen drawn from the manifest's colours.

Two pieces are generated by PHP rather than served as static files, because
both have to know where the application lives — `basePath()` may be a
subdirectory, and `assetBase()` can differ from it again depending on whether
the document root is the project or `public/`:

- `GET /manifest.webmanifest` bakes the real `start_url`, `scope` and share
  target into the manifest.
- `GET /sw.js` serves the worker with `Service-Worker-Allowed`. A worker's
  scope defaults to its own directory, so one served from `/assets/js/` would
  control nothing.

Icons are committed so a deployment needs no build step, but they are drawn by
`php tools/make-icons.php` so the set stays consistent. The maskable variant
keeps the mark inside the safe zone because launchers crop icons to their own
shape.

**A service worker needs a secure context.** Over `https://` or on
`localhost` everything below works; served over plain `http://` to a LAN
address the registration is refused by the browser and CloudHub runs as an
ordinary web page — no install, no offline. That is a browser rule, not a
setting.

## Android app

`android/` holds a native Android client, written in Kotlin with Jetpack
Compose. Build it with:

    tools/build-apk.sh

The script installs the Android SDK on first run, generates a signing keystore
if there is not one, and prints the APK's path and signing fingerprint. The
result is at `android/app/build/outputs/apk/release/app-release.apk` — sideload
it.

CloudHub is a PHP server, so the app is a **client**: it asks for your server's
address on first launch and remembers it. One APK works for any deployment.

### Why native rather than a WebView

The first Android build wrapped the web app in a WebView. That works, but a
WebView is not Chrome: downloads (which the page performs by clicking an anchor
at a `blob:` URL), the clipboard and `target="_blank"` all had to be
re-implemented by hand before the page behaved. A native client talks to the
same REST API directly, so none of that indirection exists — and it can do
things the page cannot, like swipe between photos and keep uploading with the
app closed.

### What it covers

Sign in, browse with thumbnails, full-screen image viewing with pinch-zoom and
swipe, video and audio playback, download to the device, upload from the
gallery, the camera, a recorded clip, the file browser or the Android share
sheet, new folder, rename, delete to trash, move, copy, recursive search, trash
restore and empty, and share links.

**Not covered**, deliberately: Users, Storage usage, Storage servers and the
security event log — four administrator screens that stay in the browser. There
is no offline mode; the progressive web app has one.

### Browsing files

A folder that is loading draws placeholder cards with a slow shimmer rather
than a spinner, and they are built from the *same* card layout as the real
thing — so content arriving changes colour, not position. The number of
placeholders comes from the grid actually on screen, so a tablet fills its
viewport and a phone does not over-draw. A folder that lists quickly never
shows one at all, and one that does show is held briefly so it cannot blink.

Cards give slightly under the finger, arrive staggered when a folder opens
(only the first screenful — scrolling never pays for an animation), and re-flow
rather than snap when you filter. Thumbnails hold their place while they load
and crossfade in. Folders get a tinted mark rather than another grey glyph.

Breadcrumbs are ripple-carrying targets that scroll to the folder you just
opened. The search field lifts on focus and its clear button animates in.
Changing folders cross-fades the title; switching grid and list cross-fades the
icon.

Under **Accessibility > Remove animations** the shimmer holds still, cards
arrive without a stagger, and nothing drifts. Placeholders are hidden from
screen readers — TalkBack hears "Loading this folder" once instead of a dozen
empty cards read as content.

**An empty folder and a failed one are no longer the same screen.** A listing
that fails now says so and offers **Retry**; it used to flash the error through
a snackbar that cleared itself and then report "This folder is empty" about
files it had never managed to ask for. "Nothing matched" is likewise its own
state, distinct from a folder that really has nothing in it.

The file menu also shows **Properties** — type, size, when it changed and where
it lives, all from the listing the app already has.

Folders have no preview image: CloudHub's listing does not carry one, and
building a mosaic would mean one extra request per folder on screen.

### Colours

The theme names every role it uses, including the ones nothing obviously
touches. That is not tidiness: `lightColorScheme()` fills anything left unset
with Material's *baseline* palette, which is purple. `surfaceContainer` is what
a `DropdownMenu` paints with, so an incomplete scheme gave this blue app a
lavender overflow menu, and `surfaceTint` put a lilac cast on every raised
surface. A unit test fails the build if a role is ever left at a tinted
baseline value again — matching a *neutral* baseline, like pure white for the
lowest container in a light theme, is a coincidence and allowed.

### Signing in

A gradient background with two soft colour fields drifting behind a translucent
card, the launcher's own cloud mark above it, and the parts of the form fading
and rising into place in sequence. The password can be revealed to check a typo.
The button becomes a spinner and then a check mark, holding just long enough to
read before the files appear; a rejected password shakes the card and names what
went wrong under the field it belongs to.

Under **Accessibility > Remove animations** all of that stops: the background
holds still, nothing shakes, and the form is simply there. The states still
change — instantly.

The server's address is shown under the title, percent-decoded — a CloudHub
installed in a folder with a space in its name reads as `Cloud File Hub`, not
`Cloud%20File%20Hub`. It is there so you can check which server is about to get
your password, and encoding made that harder.

**Remember my username** stores the name and nothing else. It is deliberately
not "keep me signed in", which the app does anyway: the session cookie already
survives a restart, so a box promising it would be describing something that
happens either way.

There is no sign-up, password reset or social sign-in, because CloudHub has
none of them — accounts are made on the server with `tools/create-admin.php`,
and the API accepts a username and a password. The one link is **Use a
different server**, which is the only route back to the address screen.

Launching with a session that is still good no longer flashes the login form:
the app shows the mark while it asks the server, then goes where the answer
says.

### Settings and Storage

Both live in the overflow menu.

**Storage** answers one question first: how much is left, in words, above a
bar. What "left" measures against is whichever ceiling actually applies —
your own quota if one is set, otherwise the whole-store limit, otherwise the
disk itself. That last case matters on a self-hosted box: with no limit
configured the drive *is* the ceiling, and calling it unlimited while the disk
fills up would be a comfortable lie. Past 90% the bar turns red. Below it:
files and folders, what the trash holds, and what the version history costs.
Admins also get the per-account breakdown and a recalculate button.

Every account can see its own figures, over a new **`GET /api/storage/me`**.
`/api/storage/usage` is admin-only, so before this a user with a quota had no
way to see how much of it they had used — they found out when an upload came
back 507. The per-account route never forces a fresh measurement (walking the
whole store stays an admin action), and it sweeps the ledger exactly as the
quota check does, so the number on the screen is the number that will refuse an
upload.

The overflow menu carries an icon on every item and marks the sort actually in
force — three lines reading "Sort by …" with nothing to tell them apart left you
unable to see how a folder was sorted without changing it to find out.

**Settings** covers what previously needed a reinstall: the account and its
role, changing your own password, the server address and how to change it, the
theme (system, light or dark — the app used to follow the phone with no
override), whether folders open as a grid or a list, how many videos have a
saved position and a way to forget them, the thumbnail cache with a way to
clear it, how many uploads are still queued, and signing out.

### The video player

Fullscreen with a rotation to landscape and the system bars out of the way;
**back un-maximises rather than leaving the video**, which is the thing that
otherwise makes fullscreen annoying to use. Playback speed, subtitle tracks and
audio/video track selection come from Media3's own menu. Double-tap the left or
right of the picture to seek ten seconds, but only while the controls are
hidden — an overlay on top of the transport controls would swallow every button
press.

The screen stays awake while a video is playing and stops doing so the moment
you leave, and the player ducks and pauses for calls and other apps.

Reopen a video you did not finish and it offers to carry on where you stopped,
with **Start over** in the snackbar if you would rather not. Positions inside
the first or last few seconds are not offered — resuming a film ten seconds
from the end is worse than starting it — a finished video is forgotten, and the
remembered set is capped so preferences cannot grow without limit.

### Going back

The app had one back handler in it — the video player's, and only while
fullscreen. Everywhere else, Back reached the Activity and finished it: three
folders deep, in Settings, or looking at a photo, a Back press **closed
CloudHub**. Under gesture navigation that press is a swipe in from the edge of
the screen, easy to make by accident and impossible to tell apart from a swipe
meant for the app, so it looked like the app quit at random.

Back now undoes one thing at a time, in the order they were done:

| Where you are | What Back does |
|---|---|
| Files, with a selection | Clears the selection |
| Files, with a search | Clears the search |
| Files, in a subfolder | Goes up one folder |
| Any screen opened from another | Returns to the one it was opened from |
| A video, fullscreen | Un-maximises, as before |
| The root of the file list | **Closes the app**, exactly as Android does everywhere else |

That last row matters as much as the others: an app you cannot leave is worse
than one that leaves too easily. At the root the handler is simply disabled, so
the system does its own thing — including the predictive-back animation, which
the app now opts into with `android:enableOnBackInvokedCallback`.

Screens are a stack rather than a single value, so opening Storage from
Settings and pressing Back returns to Settings — it used to drop you at the
file list, having forgotten Settings entirely. Signing in or out clears the
stack: Back must not walk into a session that has ended.

The photo viewer is the one place with a genuine gesture conflict: swiping
sideways is the whole interaction, and Android owns the strips down each edge.
The pager asks for them back with `systemGestureExclusion()`, which the system
grants up to 200dp of height per edge — so a photo swiped from the very edge
turns the page, while Back by gesture still works above and below that band,
along with the toolbar arrow and three-button Back.

### Coming back where you left off

Scrolling to the fortieth clip in a folder, watching it, and pressing Back put
you back at the first. A list's scroll position belongs to the composable that
owns it, and opening a video or a photo takes that composable out of the
composition entirely, so the position went with it. The same single position
was also shared by every folder, which meant it could only ever describe the
one on screen.

Screens now keep their state while you are on another one, filed under the
screen's own key, and the file list remembers a position **per folder** — so
walking up out of a subfolder lands where you were in the parent rather than at
its top. The memory is bounded at 32 folders, dropped oldest-first, since the
folder you just left is the one you are most likely to return to.

A screen that has been *closed*, though, is finished with, and its state is
dropped: kept, it would outrank the arguments the screen is next opened with.
The photo viewer remembers which photo is on show, so holding that across
visits made opening the next photo show the previous one. The file list is the
root and is never closed, which is what lets your place in it survive.

Closing a photo or a video also brings the list back **to that file**: swipe
through thirty photos and Back lands on the one you ended at, not the one you
opened. Only when it is off screen, though — scrolling a file that is already
visible up to the top edge is a jump for no reason.

### What playback costs

Three things used to make watching a video more expensive than it needed to be.

**Every tile fetched a whole video.** A video the server has no cached frame
for is drawn by decoding a frame on the device — and a frame can only be
decoded from bytes that have arrived, so handing the tile the video's URL meant
fetching the video. A folder of ten holiday clips was gigabytes of traffic to
draw ten thumbnails, over the same connection playback wanted. Tiles now ask
for the first few megabytes, and the frame that comes out is **handed back to
the server**, so it happens once for a file rather than once per device per
folder view — the web client benefits from the same frame. A file whose index
sits at the end of the container cannot be decoded from a prefix; those fall
back to fetching the file, but only while it is small enough for that to be a
fair trade. A 4 GB film gets the icon.

**One request carried the whole film.** A player asks for `bytes=0-` and,
given the chance, holds a single request open for the length of the video.
That is fine on a server with a worker per request and fatal on one without:
PHP's built-in server — what `php -S` gives you, and what many small
installations run — serves one request at a time, so a large video blocked
*everything else*. Measured: while a video streamed, an ordinary file listing
never answered at all. A range request is now answered with at most 8 MB, which
is what HTTP allows and what media clients already handle — verified in
Chromium, which played a file start to finish over 41 short answers, and seeked
into it. Between chunks the server is free. A request that asked for **no**
range still gets the whole file: a download must not be silently truncated.

If you run the built-in server, `PHP_CLI_SERVER_WORKERS=4 php -S 0.0.0.0:8000
router.php` gives it more than one worker and removes the head-of-line blocking
entirely. A real web server (Apache, nginx + php-fpm) does this by default.

**Over 2 GB on a 32-bit PHP.** PHP's integer is signed, so a build for 32-bit —
which Android and some NAS packages are — reports a negative size for a file
over 2 GB, and the video simply never starts. The server now says exactly that
instead.

**Nothing was kept.** Skipping back ten seconds re-fetched ten seconds that had
just arrived, and re-opening a film downloaded it again from the start — which
resume makes worse, dropping you halfway into a file the player then has to
reach from scratch. Playback now reads through a disk cache, evicted
least-recently-used, keyed on the file *and its modification time* so that
replacing a video does not play the old one out of the cache for good. A file
larger than a quarter of the cache is read through it without being written:
a film bigger than the cache cannot be held by it, and trying evicts the spans
it just wrote — including, sometimes, one still being read, which stops
playback rather than merely leaving it uncached. Settings
shows what it holds and empties it.

**Playback waited.** Media3's defaults buffer 2.5 seconds of video before
showing anything and keep nothing behind the playhead — sensible over the
public internet, pure waiting from a server on your own network. CloudHub
starts on one second, and keeps thirty seconds behind for the skip back.

On the server, media responses now carry an **ETag and Last-Modified**, so a
client holding a file asks whether it is still good instead of fetching it
again to find out; an unchanged 200 MB video answers in **0 bytes**. `If-Range`
is honoured too, so a resumed download cannot be stitched together from two
different videos if the file is replaced mid-transfer — and a seek is never
answered with "still fresh", which has no bytes in it and would stop playback
where the seek was.

### Adding files from the phone

The **+** button opens a labelled sheet: photos & videos, take a photo, record
a video, any file, new folder.

"Photos & videos" is Android's own photo picker, showing the gallery with
photos and videos together and multi-select. "Take a photo" and "Record a
video" hand off to the camera app, which writes a **full-resolution** file
through a `FileProvider` — an earlier build used the contract that returns the
camera's preview thumbnail, so "take a photo" uploaded something around 150
pixels wide. "Any file" is the document browser, kept because the gallery
cannot offer a PDF.

None of this needs a permission: the system runs the picker and hands back only
what was chosen, and the camera app owns the capture and holds its own
permissions.

Everything joins the same upload queue. Because the bytes are copied on enqueue
— which is what lets an upload survive the app closing — a 4 GB clip needs 4 GB
free while it is staged, so the space is checked before the copy starts and a
full phone is told plainly rather than failing part-way through.

### Watching an upload

Queueing files used to end in a toast — "3 files queued" — and then silence
until they either appeared on the server or did not. The worker had been
publishing its position on every chunk since uploads were built; nothing read
it.

A bar now sits at the bottom of the file list whenever anything is in flight,
with the name of the file being sent, how many are left and the overall
fraction. Tapping it opens the queue: the file moving with its own bar and byte
count, the rest waiting.

Progress is counted in **bytes, not files**, and the denominator is the size of
the batch when it started rather than what is left. Both matter more than they
sound. A 10 KB note finishing beside a 4 GB video is not half the work; and
because a finished file is *removed* from the queue, a fraction computed over
what remains walks backwards every time something completes. Files added while
a batch is running raise the total instead of overflowing the bar. That
arithmetic is a pure function with tests, because the alternative is finding
out on a phone on a slow connection.

Progress also shows outside the app, as an ordinary notification updated as
chunks land and cancelled when the queue drains. Deliberately not a foreground
service: `setForeground()` would want `FOREGROUND_SERVICE_DATA_SYNC` and a
service type on top, to buy expedited scheduling this app does not need. If you
refuse the notification permission you lose the notification and nothing else —
the in-app bar and the uploads themselves are unaffected.

**A refused upload no longer vanishes.** When the server rejects a file for
good — over quota, too large, not allowed — retrying cannot help, so the item
was dropped and its staged bytes deleted. Silently: you saw "1 file queued", the
file never arrived, and there was no error anywhere to explain it. Refusals are
now kept with the server's own wording, listed under the queue, and dismissed
when you have read them.

### How it hangs together

One OkHttp client is shared by the API, by the thumbnail loader and by the
media player, so all three carry the same session cookie and the same
certificate decisions. Giving them separate clients is how you end up with a
file list that loads and thumbnails that all fail with 401.

Uploads reuse the server's resumable chunk protocol and run under WorkManager,
so an upload survives the app being closed — and unlike the web queue it makes
progress with no page open. `/api/uploads/init` reports how many bytes the
server already holds, so a resumed upload continues from what is actually
there rather than from what the device believes it sent. Files are copied into
app-private storage when queued, because a `content://` grant from the share
sheet is frequently not persistable.

### Permissions

The app declares `INTERNET`, `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS`.
There is no `CAMERA` and no `RECORD_AUDIO` — the camera app performs the
capture through an intent and holds its own permissions — no
`READ_MEDIA_IMAGES` or `READ_MEDIA_VIDEO`, because the system photo picker
returns only what was chosen, and no storage permission, because downloads go
through `MediaStore`.

`POST_NOTIFICATIONS` is the only one you are ever asked about, and it is asked
for the first time you queue an upload rather than at launch, where a prompt
with no context is just noise. Refuse it and uploads work exactly as before.

At install you will also see `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and
`FOREGROUND_SERVICE`. Those are merged in by WorkManager and are what let an
upload keep going with the app closed.

### A server in a subdirectory

CloudHub installed under a path — `https://example.com/Cloud File Hub` — works;
enter the address exactly as you would in a browser, spaces and all.

Requests are addressed to the front controller with its trailing slash, the
same form the web client uses. That is not cosmetic: asking for the directory
without the slash makes a web server answer with a 301 to add one, and OkHttp
follows a redirect by re-sending the request as a GET with the body dropped.
The query string survives, so the route arrives — as a GET, matching nothing,
and the reply is "API endpoint not found" naming an endpoint that exists. Every
write failed that way, since uploads are PUTs and deletes are DELETEs.

If a request is ever redirected in a way that drops it, the app now says so
instead of passing the confusing 404 through.

## Previous versions

Deleting a file puts it in the trash. **Replacing** one used to lose it: an
upload of the same name with the overwrite policy unlinked what was there, and
the previous contents were gone.

Now the outgoing file is kept. **Previous versions** in a file's menu lists what
was replaced, with the date, the size and who replaced it; any version can be
downloaded, restored, or discarded. Restoring keeps the current file as a
version too, so recovering the wrong one does not destroy the right one.

The history lives in `.versions/` inside the storage root — the same trick the
trash uses, so archiving is an atomic same-filesystem rename, and it is
invisible to every file route. Ten versions per file are kept for thirty days
(`MAX_VERSIONS_PER_FILE`, `VERSION_RETENTION_DAYS`), swept alongside the trash.
Unlike the trash they are ordinary bytes on the disk and **count toward storage
and quota** — the Storage page shows what the history costs. `VERSIONS_ENABLED=0`
turns the whole thing off and restores the old behaviour.

Versions follow the *path*: renaming or moving a file leaves its history behind
under the old path, the same way the trash already behaves.

### Certificates

The app never accepts an untrusted certificate silently. The platform trust
manager gets first refusal, so a properly issued certificate — or a private CA
installed on the device — works with no prompt. Otherwise the app shows the
certificate's SHA-256 fingerprint and, if you accept, pins *that certificate*:
a different one for the same host asks again.

### Testing


The half of the app that talks to the server is tested for real, on the host,
with no emulator:

    php -S 127.0.0.1:8900 -t public &
    cd android && CLOUDHUB_TEST_URL=http://127.0.0.1:8900 gradle test

Those tests sign in and exercise the chunked upload — including interrupting
one and resuming it from the server's offset — plus download, rename, move,
copy, search, trash and restore, and share create and revoke. Without
`CLOUDHUB_TEST_URL` they skip, so an ordinary build stays green.

Decisions that are awkward to reach by hand are pure functions or plain state
machines, tested without a server or a device: whether a saved position is
worth resuming, whether there is room to stage a file before the copy begins,
what the sign-in form will send, how sign-in moves between its states, whether
two taps can become two sign-in requests, and which of skeleton, content,
empty, no-matches or error the browser should draw. One needs a half-watched
film, another needs a full phone, another needs hands fast enough to double-tap
a button mid-request, and the last needs a server that fails at the right
moment.

The Compose UI has no such coverage: rendering it needs a device. Fullscreen,
the rotation, the double-tap zones, the gallery picker, the camera, and every
animation on the sign-in and browsing screens are only provable with the APK on
a phone.

### Signing

`android/keystore.jks` and `android/keystore.properties` are generated on first
build and are **gitignored** — a committed keystore is a published signing key.
Back them up: the package id is unchanged from the WebView build, so the native
app installs as an update, but only while it is signed by the same key.

## Working offline

The worker precaches the interface, so the app opens and renders with no
connection. Every route is the same document, so one cached shell serves
`/`, `/trash`, `/users` and the rest.

Folder listings are **network first**: a stale listing showing files that are
no longer there is worse than a slightly slower load, so the cached copy is
only used when there is no network at all. A folder you have opened before is
then still browsable; one you have never opened reports that it is
unavailable offline rather than hanging. A banner says you are offline, and
the actions that need a connection are disabled rather than left to fail.

Range requests are never served from a cache — a cached whole-file response
cannot satisfy a byte range, so video seeking always reaches the network.

**Cached data belongs to whoever was signed in.** API responses are
session-authenticated, so nothing is cached opportunistically: only what you
asked to keep. Signing out clears the cached listings and files so the next
person on the device does not inherit them.

## Uploading from a phone

The toolbar gains **Take photo** and **From gallery** on touch devices — the
same file input, differing only in whether it asks Android for the camera or
the picker. CloudHub also registers as an Android **share target**, so it
appears in the share sheet alongside other apps; shared files go straight into
the upload queue rather than being pushed up in one blocking request.

### The upload queue

Uploads go through a durable queue, shown above the file list. The chunk
protocol could always resume — `/api/uploads/init` reports how many bytes the
server already holds — but there was nothing to resume *from*: the id and
offset were kept in localStorage, which stores strings, so once the tab closed
the browser had no handle on the file's bytes and "resume" could only mean
"start again".

The file itself now lives in IndexedDB, so an upload survives the app being
closed, the screen locking, the connection dropping and the phone restarting.
Progress is written after every chunk, so a crash costs at most one chunk.

The queue resumes when the app loads, when the connection returns, when the
app is brought back to the foreground, and when you sign in. Where the browser
supports Background Sync (Chromium) it also asks to be woken; that is a bonus
on top of the `online` event, not the mechanism relied on.

**The upload engine stays in the page, not the worker.** Reimplementing the
chunk protocol in the service worker would mean two copies of the resume logic
that have to agree exactly. Instead the worker wakes an open page when the
connection returns, and if none is open the queue continues the next time the
app is launched. Either way the upload finishes.

A network failure leaves an item queued rather than failed — you should not
have to press retry for something you did not do wrong. A refusal that
retrying cannot fix (`413` too large, `507` over quota, `403` forbidden) fails
the item outright and says why.

## Keeping files for offline use

Any file's ⋮ menu offers **Keep offline**, which stores its bytes and its
thumbnail on the device; kept files are marked in the listing and readable
with no connection. Without the thumbnail the offline listing would show
broken tiles for files that are in fact available.

The cache name lives only in the service worker, and the page asks it over a
message channel — a page that opened the cache itself would have to know the
version string and would silently write into an orphaned cache the moment the
worker updated.

## Moving, copying and searching

Select one or more items and use **Move** or **Copy**, or pick *Move to…* /
*Copy to…* from a file's ⋮ menu. Either way the destination is chosen by
browsing folders rather than typing a path.

`POST /api/files/move` and `POST /api/files/copy` take a list of source paths
and one destination folder. A failure on one item does not abandon the rest:
every source is attempted and the failures come back named, so twenty files
selected at once cannot report a bare success over a partial result. Neither
route overwrites silently — with `ALLOW_OVERWRITE=false` a name clash is a
409, otherwise the arriving item is given a `(2)` suffix. A folder cannot be
moved into itself, and moving an item into the folder it is already in is
refused; copying into the same folder is allowed, because that is how you
duplicate something.

The search box has two modes. **This folder** filters the listing already on
screen, so it stays instant. **All folders** calls `GET /api/files/search`,
which walks the tree below the folder you are in. That walk is bounded twice
over — by the number of results and by the number of entries examined — and
says so when it stops early, rather than silently returning a short list.

## Trash

Deleting moves an item to a trash inside the storage root; the **Trash**
screen restores it or removes it for good. This is on by default; set
`TRASH_ENABLED=false` to delete permanently instead, and
`TRASH_RETENTION_DAYS` (default 30, `0` to disable expiry) to say how long
items are kept. Expired entries are dropped as later deletions happen.

The trash keeps one directory per deletion, holding the item under its own
name plus a small metadata file recording where it came from, who deleted it
and when. Restore never overwrites: if something has since taken the original
name the item is restored beside it with a suffix, and a parent folder that
was deleted too is recreated.

The trash lives at `.trash` inside the storage root, so moving a file into it
is always a same-filesystem rename — instant, and impossible to half-finish.
That directory is reserved: it is never listed, never searched, and cannot be
addressed through any file route, so the trash cannot be browsed or emptied
except through `/api/trash`. Listing the trash needs only read access;
restoring and purging need write access, like any other change to the store.

## Accounts and roles

Administrators manage accounts from the **Users** screen: create and delete
them, set the role, enable and disable them, and reset a password. Every
signed-in user can change their own password from the **Password** button,
which requires their current one.

| Role | Can |
|---|---|
| `viewer` | Browse, preview, download (including bulk ZIP) and create share links |
| `editor` | Also upload, rename, move and delete |
| `admin` | Also manage storage servers and accounts, and read the audit trail |

The API behind the screen is `/api/users` (administrator-only) plus
`POST /api/users/me/password` (any signed-in user). Password hashes are never
returned by any of them.

Guards that cannot be bypassed by editing the page: you cannot delete your own
account, you cannot remove your own administrator access, and the last enabled
administrator cannot be disabled, demoted or deleted — otherwise nobody could
reach these settings again.

Role and enabled changes reach a signed-in user's existing session within a
minute; disabling an account ends its session rather than waiting for a sign
out. The check is throttled to roughly one query per user per minute so that a
gallery's worth of thumbnail requests does not each incur one.

Passwords are stored using PHP-compatible password hashes and are re-hashed to
the preferred algorithm on the next successful login. The browser no longer
receives a `WWW-Authenticate` header, so native Basic Auth popups are not used.

## Storage and quotas

Administrators get a **Storage** screen: what the store holds, how much of the
disk is free, what is reclaimable from the trash, a breakdown by folder and by
kind of file, the largest files, and how much each account has uploaded.

Measuring means walking the whole tree, so the figure is deliberately not
recomputed per request: it is cached for `USAGE_CACHE_SECONDS` (default 300),
the screen says how old it is, and **Recalculate** forces a fresh measurement.
The cache lives outside the storage root, so it is neither listed, searched,
nor counted in the number it holds. Bounding the walk was rejected — a
dashboard that stops counting early reports a number that is simply wrong.

Two optional limits, both `0` (unlimited) by default and both checked at
`POST /api/uploads/init`, before a single byte is staged:

| Setting | Caps |
|---|---|
| `STORAGE_LIMIT_GB` | the whole file store, measured from disk |
| `USER_QUOTA_GB` | what one account has uploaded |

Refusals come back as `507 INSUFFICIENT_STORAGE` and say where the caller
stands (`You have used 4.1 GB of your 5 GB quota`) — 5xx messages are
otherwise hidden, but a caller cannot act on what they are not told.

### What the per-account figure counts

The per-user quota needs to know who uploaded what, and nothing recorded that:
`file_metadata` was declared in `schema.sql` from the beginning and referenced
by no PHP at all. It is now an upload ledger, with an added nullable
`uploaded_by` column (`php database/migrate.php` adds it to an existing
installation).

It counts **bytes an account uploaded through CloudHub that are still on
disk**. Moves, renames, copies, deletes, trashing and restores all keep it in
step. Files that predate the feature, or that arrive by WebDAV or by a change
made directly on disk, are unattributed: they count towards
`STORAGE_LIMIT_GB` but towards nobody's personal quota. A periodic sweep drops
rows whose file has since disappeared, so the ledger converges instead of
requiring every write in the system to remember it.

A copy is charged to whoever made it, not to the original uploader — a quota
avoided by uploading one file and copying it a hundred times is not a quota.

Every ledger operation fails open. A bookkeeping problem means "the quota does
not bind", never "a legitimate upload is refused".

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

From the project directory, use `php -S 0.0.0.0:8000 router.php`. Prefer it over a
bare `php -S 0.0.0.0:8000` from the parent directory: the built-in server does not
read `.htaccess`, so only `router.php` applies the rules that keep `.env`, `config/`,
`src/` and `database/` from being served.

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

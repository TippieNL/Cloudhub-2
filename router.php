<?php
declare(strict_types=1);

// Router for PHP's built-in development server, started from the project root:
//   php -S 0.0.0.0:8000 router.php
//
// Here the project root is the document root, so pages request static files as
// /public/assets/... (see Http::assetBase()). Apache's .htaccess additionally
// aliases /assets/... and /favicon.png into public/; mirror both spellings so
// the development server behaves like the deployed one.

$uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';

/**
 * What a static file under public/ should be served as.
 *
 * Only the handful of types this application actually ships; anything else
 * falls back to the sniffer, which is fine for the media it is good at.
 */
function dev_content_type(string $file): string
{
    static $byExtension = [
        'css' => 'text/css',
        'js' => 'text/javascript',
        'mjs' => 'text/javascript',
        'json' => 'application/json',
        'webmanifest' => 'application/manifest+json',
        'svg' => 'image/svg+xml',
        'png' => 'image/png',
        'jpg' => 'image/jpeg',
        'jpeg' => 'image/jpeg',
        'gif' => 'image/gif',
        'webp' => 'image/webp',
        'ico' => 'image/x-icon',
        'woff2' => 'font/woff2',
    ];
    $extension = strtolower((string)pathinfo($file, PATHINFO_EXTENSION));
    return $byExtension[$extension] ?? (mime_content_type($file) ?: 'application/octet-stream');
}

/**
 * Tell the application which script it is really running.
 *
 * PHP 8.2's built-in server hands a request whose path ends in an extension --
 * /share/TOKEN/holiday.jpg, /webdav/Photos/beach.jpg -- to this router with
 * SCRIPT_NAME set to that path and SCRIPT_FILENAME set to this file, as if the
 * URL named a script. Http::basePath() reads the install folder from
 * SCRIPT_NAME, so it took "/share/TOKEN" for one and stripped it: every share
 * link ending in its file's name, and every WebDAV file, answered "Not found".
 * PHP 8.3 and later report the front controller, as Apache and nginx always
 * have. Under this router the application sits at the root of the server, so
 * that is what it is told -- in the form the later versions use, and only when
 * the server has named the router itself.
 */
function dev_front_controller(string $uri): void
{
    if (realpath((string)($_SERVER['SCRIPT_FILENAME'] ?? '')) !== __FILE__) return;
    $_SERVER['SCRIPT_FILENAME'] = rtrim((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__), '/').'/index.php';
    $_SERVER['SCRIPT_NAME'] = '/index.php';
    $_SERVER['PHP_SELF'] = '/index.php'.$uri;
}

/*
 * Public share links, before the deny rules.
 *
 * A share URL now ends in the shared file's own name, and the rules below
 * refuse anything ending .log, .ini, .sql and friends -- so sharing notes.log
 * would 403 here while working in production, which is the worst way for a
 * difference between the two to show up. /share/... never names a file under
 * the project root, so there is nothing here for those rules to protect.
 */
if (preg_match('#^/share/[A-Za-z0-9_-]{20,128}(?:/|$)#', $uri)) {
    dev_front_controller($uri);
    require __DIR__ . '/public/index.php';
    return true;
}

// The built-in server does not read .htaccess, so mirror its deny rules here.
// Without this the project root — which is the document root in this layout —
// hands out .env, the database schema and the PHP sources verbatim.
//
// Any dot-segment is refused (/.git/config, /.env, editor droppings) except
// /.well-known/, which ACME certificate renewal answers from, as is
// the android/ client tree, whose keystore.properties is a signing secret and
// whose build.gradle exposes internals. The extension list catches the loose
// artefacts a repo accumulates. /share/ was exempted above, so a shared
// notes.log is unaffected.
$denied = '#(?:^|/)\.(?!well-known(?:/|$))'
    .'|^/(?:config|src|views|database|storage|logs|tests|tools|deploy|android)(?:/|$)'
    .'|^/(?:README|SECURITY|PROJECT_CONTEXT|replit)\.md$'
    .'|\.(?:bak|old|orig|save|sql|log|ini|dist|gradle|properties|kt|lock)$#i';
if (preg_match($denied, $uri)) {
    http_response_code(403);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Forbidden\n";
    return true;
}

if ($uri !== '/') {
    // A real file below the project root, e.g. /public/assets/js/app.js.
    // Returning false lets the built-in server stream (or execute) it.
    if (is_file(__DIR__.$uri)) return false;

    // Alias: /assets/... and /favicon.png live under public/.
    $publicDir = realpath(__DIR__.'/public');
    $aliased = realpath(__DIR__.'/public'.$uri);
    if ($publicDir !== false && $aliased !== false && is_file($aliased)
        && str_starts_with($aliased, $publicDir.DIRECTORY_SEPARATOR)
        && strtolower((string)pathinfo($aliased, PATHINFO_EXTENSION)) !== 'php') {
        /*
         * Typed from the extension, not sniffed.
         *
         * mime_content_type() looks at the bytes, and a stylesheet is bytes of
         * text -- so it answers "text/plain", and a browser refuses to apply a
         * stylesheet served as text/plain in standards mode. The development
         * server was handing out the whole application unstyled.
         */
        header('Content-Type: '.dev_content_type($aliased));
        header('Content-Length: '.filesize($aliased));
        readfile($aliased);
        return true;
    }
}

// Otherwise, boot the application.
dev_front_controller($uri);
require __DIR__ . '/public/index.php';

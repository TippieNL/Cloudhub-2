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
    require __DIR__ . '/public/index.php';
    return true;
}

// The built-in server does not read .htaccess, so mirror its deny rules here.
// Without this the project root — which is the document root in this layout —
// hands out .env, the database schema and the PHP sources verbatim.
$denied = '#^/(?:config|src|views|database|storage|logs|tests|tools|deploy)(?:/|$)'
    .'|^/\.env|^/(?:README|SECURITY)\.md$'
    .'|\.(?:bak|old|orig|save|sql|log|ini|dist)$#i';
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
require __DIR__ . '/public/index.php';

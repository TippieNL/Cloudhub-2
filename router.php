<?php
declare(strict_types=1);

// Router for PHP's built-in development server.
// Usage: php -S 0.0.0.0:8000 router.php

$uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
$file = __DIR__ . '/public' . $uri; // Assuming your static assets are in public/

// If the requested URI is a physical file, let the PHP dev server handle it natively
if ($uri !== '/' && is_file($file)) {
    return false;
}

// Otherwise, boot the application
require __DIR__ . '/public/index.php';

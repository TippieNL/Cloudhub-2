<?php
declare(strict_types=1);

/**
 * Mobile layout of the application chrome.
 *
 * On a phone the header laid brand, section nav and account buttons on one
 * line inside a fixed 56px box with no wrapping, so the buttons rendered
 * partly above and partly below the bar, overlapping the nav. Measured at
 * 412px: the account group sat at y=-31 and ran 30px past the header.
 *
 * Separately the selection bar carried display:flex from a grouped rule, which
 * outranks the user-agent [hidden] rule, so it never hid and "0 selected" was
 * permanently on screen.
 *
 * Rendered behaviour was checked in a real browser at 320/360/412/768/1280;
 * these assertions pin the rules that produce it.
 */
$root = dirname(__DIR__);
$css = (string)file_get_contents($root.'/public/assets/css/app.css');
$view = (string)file_get_contents($root.'/views/pages/app.php');
$app = (string)file_get_contents($root.'/public/assets/js/app.js');

$checks = [];

// --- the hidden attribute must win, everywhere ---------------------------
$checks['a global rule makes [hidden] win'] = str_contains($css, '[hidden]{display:none!important}');
$checks['the guard precedes every display rule'] = (function () use ($css): bool {
    // Compare positions in the stylesheet only -- the guard's own comment
    // mentions display:flex, which would otherwise match first.
    $code = preg_replace('#/\*.*?\*/#s', '', $css);
    $guard = strpos((string)$code, '[hidden]{display:none!important}');
    $firstDisplay = strpos((string)$code, 'display:flex');
    return $guard !== false && $firstDisplay !== false && $guard < $firstDisplay;
})();

// --- header ---------------------------------------------------------------
$checks['the header can grow instead of overflowing'] =
    str_contains($css, 'header{min-height:56px') && !str_contains($css, 'header{height:56px');
$checks['the header wraps rather than spilling'] = (bool)preg_match('/header\{min-height:56px;[^}]*flex-wrap:wrap/', $css);
$checks['the account group has its own hook'] =
    str_contains($view, 'class="header-actions"') && str_contains($css, '.header-actions');
// Six nav entries no longer fit beside the account group at tablet widths, so
// the header wraps there. Left-aligned under the brand looks like a mistake.
$checks['a wrapped header keeps the account group at the end of its row'] =
    str_contains($css, '.header-actions{display:flex;gap:6px;align-items:center;margin-left:auto}');
$checks['mobile puts nav on its own row'] =
    str_contains($css, 'grid-template-areas:"brand actions" "nav nav"');
$checks['a crowded nav scrolls instead of wrapping'] =
    str_contains($css, 'header>nav{grid-area:nav;display:flex') && str_contains($css, 'overflow-x:auto');
$checks['nav items do not break mid-word'] = str_contains($css, 'header>nav a{padding:8px 12px;white-space:nowrap');

// --- toolbars -------------------------------------------------------------
$checks['the toolbar is a predictable grid on mobile'] =
    str_contains($css, '.toolbar-actions{width:100%;display:grid;grid-template-columns:1fr 1fr');
$checks['the odd button spans the row'] = str_contains($css, '.toolbar-actions>button:last-child{grid-column:1/-1}');
$checks['toolbar buttons keep a touch-sized target'] =
    str_contains($css, 'min-height:44px') && !str_contains($css, 'min-height:54px');
$checks['search gets its own row on mobile'] = str_contains($css, '.file-controls #search{grid-column:1/-1}');

// --- wrapping -------------------------------------------------------------
// .muted was grouped with .upload-file-size, so paragraph-length help text
// inherited white-space:nowrap and ran off the side of a phone screen.
$checks['muted help text is allowed to wrap'] =
    (bool)preg_match('/(^|[,{}])\.muted\{[^}]*\}/m', $css)
    && !(bool)preg_match('/\.muted\{[^}]*white-space:nowrap/', $css);
$checks['the size column still stays on one line'] =
    (bool)preg_match('/\.upload-file-size\{[^}]*white-space:nowrap/', $css);

// --- the action label -----------------------------------------------------
$checks['the preview action is labelled for the file'] =
    str_contains($app, "playable ? 'Play' : 'Preview'");

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);

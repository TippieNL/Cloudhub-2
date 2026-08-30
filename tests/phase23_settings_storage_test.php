<?php
declare(strict_types=1);

/**
 * The Android Settings and Storage screens, and the endpoint behind them.
 *
 * Regression pins over the source. What the app *does* is covered where it can
 * be: the endpoint by tests/http/run.php against a real server, and the
 * "how much space is left" rule by StorageMeterTest, which needs neither.
 */
$root = dirname(__DIR__);
$read = fn(string $path): string => (string)@file_get_contents($root.'/'.$path);
$kotlin = $root.'/android/app/src/main/java/nl/tippie/cloudhub';
$readKt = fn(string $path): string => (string)@file_get_contents($kotlin.'/'.$path);

$index = $read('public/index.php');
$httpRunner = $read('tests/http/run.php');
$storage = $readKt('ui/StorageScreen.kt');
$meter = $readKt('ui/StorageMeter.kt');
$settings = $readKt('ui/SettingsScreen.kt');
$stores = $readKt('data/Stores.kt');
$theme = $readKt('ui/Theme.kt');
$api = $readKt('net/CloudHubApi.kt');
$main = $readKt('MainActivity.kt');
$files = $readKt('ui/FilesScreen.kt');
$meterTests = $read('android/app/src/test/java/nl/tippie/cloudhub/StorageMeterTest.kt');
$paletteTests = $read('android/app/src/test/java/nl/tippie/cloudhub/ThemePaletteTest.kt');

$checks = [];

/* --- the endpoint ------------------------------------------------------------
 *
 * /api/storage/usage is admin-only, which left an account with a quota unable
 * to see how much of it it had used until an upload came back 507.
 */
/**
 * One route's body, so a check about it cannot accidentally match the next
 * route down the file -- which is exactly what a lazy `.*?` under /s does.
 */
$routeBody = function (string $haystack, string $needle): string {
    $at = strpos($haystack, $needle);
    if ($at === false) return '';
    $end = strpos($haystack, "\nif (\$path === '", $at + 1);
    return substr($haystack, $at, $end === false ? 2000 : $end - $at);
};

$meRoute = $routeBody($index, "if (\$path === '/api/storage/me' && \$method === 'GET')");
$usageRoute = $routeBody($index, "if (\$path === '/api/storage/usage' && \$method === 'GET')");

$checks['every account can read its own storage'] =
    $meRoute !== '' && !str_contains($meRoute, 'requireAdmin');
$checks['the whole-server report stays admin-only'] =
    $usageRoute !== '' && str_contains($usageRoute, 'Authorization::requireAdmin();');
// Measuring walks the whole store; a route every account can call must not be
// a way to make the server do that on demand.
$checks['the per-account view cannot force a measurement'] =
    str_contains($meRoute, 'storage_report($fs, $config);')
    && !str_contains($meRoute, '$_GET[\'refresh\']');
// A figure that disagrees with the error you eventually get is worse than none.
$checks['the figure shown matches the one that refuses an upload'] =
    str_contains($meRoute, 'ledger()->sweep($fs);');
$checks['it reports what the history costs'] =
    str_contains($meRoute, "'versions' => \$report['versions']");
$checks['the endpoint is proved over real HTTP'] =
    str_contains($httpRunner, 'an ordinary account can see its own storage')
    && str_contains($httpRunner, 'the per-account view cannot force a tree walk')
    && str_contains($httpRunner, 'an anonymous visitor cannot read storage');

/* --- what "space left" means --------------------------------------------------
 *
 * Three things can be the ceiling and only one applies; picking the wrong one
 * tells someone they have room when they do not.
 */
$checks['the space rule is a pure function, not an if-chain in a screen'] =
    str_contains($meter, 'object StorageMeter')
    && str_contains($meter, 'fun of(')
    && str_contains($meterTests, 'class StorageMeterTest');
$checks['a personal quota outranks the store limit'] =
    preg_match('#quotaBytes > 0 -> reading\(Against\.QUOTA.*?\n\s+storeLimitBytes > 0 -> reading\(Against\.STORE#s', $meter) === 1;
// On a self-hosted box with nothing configured the drive is the ceiling, and
// "unlimited" would be a comfortable lie.
$checks['with no limit set the disk is the ceiling, and says so'] =
    str_contains($meter, 'Against.DISK')
    && str_contains($storage, '"the disk, with no limit set"');
$checks['a quota that has been overshot does not read negative'] =
    str_contains($meter, 'remainingBytes = (total - safeUsed).coerceAtLeast(0)');
$checks['an unmeasured server does not divide by zero'] =
    str_contains($meter, 'if (total <= 0) 0f else');
$checks['nearly-full is a shared threshold, not a magic number in the UI'] =
    str_contains($meter, 'const val WARN_AT') && str_contains($storage, 'reading.nearlyFull');

/* --- the screens ---------------------------------------------------------------- */

$checks['storage is reachable and its own screen'] =
    str_contains($storage, 'fun StorageScreen(')
    && str_contains($files, 'MenuItem(Icons.Default.PieChart, "Storage")');
$checks['settings is reachable and its own screen'] =
    str_contains($settings, 'fun SettingsScreen(')
    && str_contains($files, 'MenuItem(Icons.Default.Settings, "Settings")');
// A 403 on the admin report must not replace a screen that was working.
$checks['a non-admin sees their own figures, not an error'] =
    str_contains($storage, 'if (own.isAdmin)')
    && str_contains($storage, '.getOrNull()');
$checks['only an admin can ask for a recalculation'] =
    str_contains($storage, 'if (mine?.isAdmin == true)');
$checks['the trash and the version history are both accounted for'] =
    str_contains($storage, 'Line("In the trash"') && str_contains($storage, 'Line("Previous versions"');

$checks['a viewer can still change their own password'] =
    str_contains($api, 'suspend fun changePassword(')
    && str_contains($settings, 'Tappable("Change password")');
$checks['an obvious password mistake is caught before a round trip'] =
    str_contains($settings, 'internal fun passwordProblem(')
    && str_contains($settings, 'replacement != confirm')
    && str_contains($meterTests, 'class PasswordProblemTest');
// Preferences outlive the code that wrote them.
$checks['the theme choice is a tested three-way rule'] =
    str_contains($theme, 'fun resolve(choice: ThemeChoice, systemDark: Boolean)')
    && str_contains($theme, 'entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM')
    && str_contains($meterTests, 'class ThemeChoiceTest');
$checks['the theme is chosen before it is applied'] =
    str_contains($main, 'var theme by remember { mutableStateOf(ThemeChoice.of(app.settings.themeChoice)) }')
    && str_contains($main, 'CloudHubTheme(theme)');
$checks['the theme choice survives a restart'] =
    str_contains($stores, 'var themeChoice: String?');
// Settings is built from state that already existed rather than new
// preferences invented for the screen.
$checks['settings reports the queue and the cache it already has'] =
    str_contains($main, 'queuedUploads = queue.all().size')
    && str_contains($main, 'private fun thumbnailCacheBytes(): Long');
$checks['one byte formatter, not two'] =
    str_contains($storage, 'humanBytes(') && !str_contains($storage, 'fun humanBytes')
    && str_contains($settings, 'humanBytes(') && !str_contains($settings, 'fun humanBytes');

/* --- the palette, and the menu it was showing through --------------------------
 *
 * Theme.kt set twenty roles and left thirteen to lightColorScheme()'s defaults
 * -- which are Material's *baseline purple*, not neutrals. surfaceContainer is
 * what a DropdownMenu paints with, so the overflow menu came out lavender on a
 * blue app, and surfaceTint lilaced every raised surface. Five releases went
 * out that way: it compiled perfectly and only showed on one screen.
 */
$checks['no colour role is left to Material to fill in'] =
    str_contains($theme, 'surfaceContainer =') && str_contains($theme, 'surfaceContainerLow =')
    && str_contains($theme, 'surfaceContainerHigh =') && str_contains($theme, 'surfaceContainerHighest =')
    && str_contains($theme, 'surfaceContainerLowest =') && str_contains($theme, 'surfaceTint =')
    && str_contains($theme, 'inverseSurface =') && str_contains($theme, 'tertiary =');
$checks['a forgotten role fails the build rather than shipping'] =
    str_contains($paletteTests, 'class ThemePaletteTest')
    && str_contains($paletteTests, 'fun leftPurple(');
// White matching the baseline is a coincidence, not a hole; lavender is not.
$checks['the palette check does not fire on a neutral coincidence'] =
    str_contains($paletteTests, 'isNeutral()')
    && str_contains($paletteTests, 'the check itself notices a tinted baseline value');
$checks['the elevation tint is the brand, not a stranger'] =
    str_contains($theme, 'surfaceTint = Brand') && str_contains($theme, 'surfaceTint = BrandDark');

/* --- the overflow menu ------------------------------------------------------- */

$checks['every menu item carries an icon'] =
    str_contains($files, 'private fun MenuItem(')
    && str_contains($files, 'leadingIcon = { Icon(icon, null, tint = tint) }');
// Three lines reading "Sort by ..." with nothing to tell them apart left you
// unable to see what a folder was sorted by without changing it to find out.
$checks['the sort in force is marked'] =
    str_contains($files, 'private fun SortItem(')
    && str_contains($files, 'sort == FilesState.Sort.NAME')
    && str_contains($files, 'Icons.Default.Check, "Sorted by $label"');
$checks['the menu is grouped rather than a flat list'] =
    str_contains($files, 'private fun MenuHeading(') && str_contains($files, 'MenuHeading("Sort by")');
$checks['the menu does not hug the screen edge'] =
    str_contains($files, 'offset = DpOffset(x = (-8).dp, y = 4.dp)');

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);

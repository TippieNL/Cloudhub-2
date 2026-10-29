<?php
declare(strict_types=1);

/**
 * Thumbnails: upright, transparent where the image is, and never a memory bomb.
 *
 * - A few-KB PNG can declare 10000x10000 pixels; decoding it took one
 *   thumbnail request to 704 MB, outside memory_limit (the system libgd's
 *   allocations are not PHP's). The header is measured first now.
 * - Phone photos record their rotation in EXIF, which GD ignores and the WebP
 *   thumbnail drops, so portrait photos lay on their side in the grid.
 *
 * The helpers are ThumbnailService's own, run against real GD images,
 * including a JPEG carrying a real EXIF Orientation tag. They used to be lifted
 * out of public/index.php and eval'd; they moved into the service with the
 * rest of the thumbnails (phase 50), and are called as themselves now.
 */
$root = dirname(__DIR__);
$service = (string)file_get_contents($root.'/src/Services/ThumbnailService.php');
$checks = [];

if (!extension_loaded('gd')) { echo "[SKIP] GD is not loaded\n"; exit(0); }
require_once $root.'/src/Services/FileService.php';
require_once $root.'/src/Services/ThumbnailService.php';
use CloudHub\Services\ThumbnailService;
$checks['the helpers are the service\'s own'] =
    method_exists(ThumbnailService::class, 'sourceFits') && method_exists(ThumbnailService::class, 'orient');
$thumbnail_source_fits = fn(int $w, int $h): bool => ThumbnailService::sourceFits($w, $h);
$thumbnail_orient = fn(\GdImage $im, int $o): \GdImage => ThumbnailService::orient($im, $o);

// --- the decode guard ------------------------------------------------------
$checks['a 10000x10000 declaration is refused'] = !$thumbnail_source_fits(10000, 10000);
$checks['a 48 MP phone photo is allowed'] = $thumbnail_source_fits(8000, 6000);
$checks['a nonsense size is refused'] = !$thumbnail_source_fits(0, 100) && !$thumbnail_source_fits(100, -1);
$checks['the service measures before it decodes'] =
    (bool)preg_match('/\$dimensions = @getimagesize\(\$file\);.*self::sourceFits\(.*\$source = match \(\$ext\)/s', $service);

// --- orientation: where the top-left pixel of a 4x2 image must end up -------
// The EXIF meaning of each value, as the correction that makes it upright.
$expected = [1 => [4, 2, 0, 0], 2 => [4, 2, 3, 0], 3 => [4, 2, 3, 1], 4 => [4, 2, 0, 1],
             5 => [2, 4, 0, 0], 6 => [2, 4, 1, 0], 7 => [2, 4, 1, 3], 8 => [2, 4, 0, 3]];
$allRight = true;
foreach ($expected as $orientation => [$w, $h, $x, $y]) {
    $im = imagecreatetruecolor(4, 2);
    imagefill($im, 0, 0, imagecolorallocate($im, 0, 0, 255));
    imagesetpixel($im, 0, 0, imagecolorallocate($im, 255, 0, 0));
    $out = $thumbnail_orient($im, $orientation);
    $ok = imagesx($out) === $w && imagesy($out) === $h && (imagecolorat($out, $x, $y) >> 16 & 0xFF) === 255;
    if (!$ok) { $allRight = false; echo "  orientation $orientation: ".imagesx($out).'x'.imagesy($out)."\n"; }
}
$checks['every EXIF orientation is corrected'] = $allRight;

// --- and from a real photo's EXIF ---------------------------------------------
if (function_exists('exif_read_data')) {
    $im = imagecreatetruecolor(40, 20);
    imagefill($im, 0, 0, imagecolorallocate($im, 0, 0, 255));
    imagefilledrectangle($im, 0, 0, 19, 19, imagecolorallocate($im, 255, 0, 0));   // left half red
    ob_start(); imagejpeg($im, null, 95); $jpeg = (string)ob_get_clean();
    // APP1 "Exif" holding one IFD0 entry: Orientation (0x0112), SHORT, value 6.
    $tiff = "II*\0".pack('V', 8).pack('v', 1).pack('vvVv', 0x0112, 3, 1, 6)."\0\0".pack('V', 0);
    $app1 = "Exif\0\0".$tiff;
    $file = sys_get_temp_dir().'/cloudhub-p41-'.bin2hex(random_bytes(4)).'.jpg';
    file_put_contents($file, substr($jpeg, 0, 2)."\xFF\xE1".pack('n', strlen($app1) + 2).$app1.substr($jpeg, 2));
    $orientation = (int)(@exif_read_data($file)['Orientation'] ?? 1);
    $upright = $thumbnail_orient(imagecreatefromjpeg($file), $orientation);
    @unlink($file);
    $checks['a phone photo held upright reads as orientation 6'] = $orientation === 6;
    $checks['and its thumbnail stands upright'] = imagesx($upright) === 20 && imagesy($upright) === 40
        && (imagecolorat($upright, 10, 5) >> 16 & 0xFF) > 200 && (imagecolorat($upright, 10, 35) & 0xFF) > 200;
}
$checks['JPEG thumbnails are turned by their EXIF'] =
    str_contains($service, "? (int)(@exif_read_data(\$file)['Orientation'] ?? 1)")
    && str_contains($service, "if (\$orientation !== 1) \$im = self::orient(\$im, \$orientation);");
$checks['the thumbnail canvas keeps transparency'] =
    str_contains($service, "imagesavealpha(\$im, true);") && str_contains($service, "imagefill(\$im, 0, 0, imagecolorallocatealpha(\$im, 0, 0, 0, 127));");

$bad = false;
foreach ($checks as $name => $ok) { echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL; $bad = $bad || !$ok; }
exit($bad ? 1 : 0);

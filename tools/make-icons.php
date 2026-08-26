<?php
declare(strict_types=1);

/**
 * Regenerate the application icons.
 *
 *   php tools/make-icons.php
 *
 * The icons are committed so a deployment needs no build step, but they are
 * generated rather than hand-drawn so the set stays consistent: change the
 * palette or the mark here and every size follows.
 *
 * Android uses the 192 and 512 icons for the home screen and derives the
 * splash screen from the 512 one plus the manifest's background_color, so
 * those two carry the whole install experience. The maskable variant keeps
 * the mark inside the safe zone (80% of the canvas) because launchers crop
 * icons to their own shape -- a circle on Pixel, a squircle on Samsung -- and
 * anything outside that circle is cut off.
 */

const BRAND = [0x14, 0x79, 0xc9];
const INK   = [0xff, 0xff, 0xff];

/** Rounded rectangle, since GD has no primitive for one. */
function rounded_rect($im, int $x, int $y, int $w, int $h, int $r, int $colour): void
{
    imagefilledrectangle($im, $x + $r, $y, $x + $w - $r, $y + $h, $colour);
    imagefilledrectangle($im, $x, $y + $r, $x + $w, $y + $h - $r, $colour);
    foreach ([[$x + $r, $y + $r], [$x + $w - $r, $y + $r], [$x + $r, $y + $h - $r], [$x + $w - $r, $y + $h - $r]] as [$cx, $cy]) {
        imagefilledellipse($im, $cx, $cy, $r * 2, $r * 2, $colour);
    }
}

/**
 * The mark: a cloud with an upload arrow, drawn in a 100x100 unit box so it
 * can be placed at any size.
 *
 * The arrow is knocked out of the cloud in the background colour rather than
 * drawn beside it. At 48px on a home screen two separate shapes merge into an
 * unreadable blob -- the first attempt paired a cloud with a folder and came
 * out looking like a chef's hat.
 */
function draw_mark($im, float $ox, float $oy, float $size, int $ink, int $ground): void
{
    $u = fn(float $n): int => (int)round($ox + $n * $size / 100);
    $v = fn(float $n): int => (int)round($oy + $n * $size / 100);
    $d = fn(float $n): int => max(1, (int)round($n * $size / 100));

    // Cloud: three bumps over a slab, giving it one flat base.
    imagefilledellipse($im, $u(30), $v(50), $d(34), $d(34), $ink);
    imagefilledellipse($im, $u(52), $v(40), $d(46), $d(46), $ink);
    imagefilledellipse($im, $u(74), $v(52), $d(30), $d(30), $ink);
    imagefilledrectangle($im, $u(30), $v(50), $u(74), $v(67), $ink);

    // Upload arrow, knocked out of the cloud.
    imagefilledpolygon($im, [$u(52), $v(28), $u(66), $v(46), $u(38), $v(46)], $ground);
    imagefilledrectangle($im, $u(46), $v(44), $u(58), $v(70), $ground);
}

function icon(int $size, bool $maskable): GdImage
{
    $im = imagecreatetruecolor($size, $size);
    imagealphablending($im, true);
    imagesavealpha($im, true);

    $brand = imagecolorallocate($im, ...BRAND);
    $ink = imagecolorallocate($im, ...INK);

    if ($maskable) {
        // Full bleed: the launcher supplies the shape, so the background must
        // reach every corner or the crop shows through.
        imagefilledrectangle($im, 0, 0, $size, $size, $brand);
        $mark = $size * 0.52;
    } else {
        imagefill($im, 0, 0, imagecolorallocatealpha($im, 0, 0, 0, 127));
        rounded_rect($im, 0, 0, $size - 1, $size - 1, (int)round($size * 0.22), $brand);
        $mark = $size * 0.66;
    }

    draw_mark($im, ($size - $mark) / 2, ($size - $mark) / 2 - $mark * 0.02, $mark, $ink, $brand);
    return $im;
}

$out = dirname(__DIR__).'/public/assets/icons';
if (!is_dir($out) && !mkdir($out, 0775, true)) {
    fwrite(STDERR, "Unable to create $out\n");
    exit(1);
}

$targets = [
    'icon-192.png' => [192, false],
    'icon-512.png' => [512, false],
    'icon-maskable-512.png' => [512, true],
    'apple-touch-icon.png' => [180, true],   // iOS applies its own rounding
];
foreach ($targets as $name => [$size, $maskable]) {
    $im = icon($size, $maskable);
    imagepng($im, $out.'/'.$name, 9);
    imagedestroy($im);
    echo str_pad($name, 26), $size, "x", $size, ($maskable ? '  maskable' : ''), PHP_EOL;
}

// The browser tab icon shares the mark so the tab and the home screen match.
$im = icon(64, false);
imagepng($im, dirname(__DIR__).'/public/favicon.png', 9);
imagedestroy($im);
echo str_pad('favicon.png', 26), "64x64", PHP_EOL;

/*
 * Android launcher icons.
 *
 * Generated from the same mark so the web app and the installed app cannot
 * drift apart. Two sets are needed:
 *
 *  - legacy square icons per density, for launchers older than adaptive icons;
 *  - an adaptive foreground, which the launcher composes over the background
 *    colour and then masks to its own shape. The mark is drawn small inside a
 *    full-size canvas because the outer 1/6 on each side can be cropped away,
 *    and anything that reaches the edge gets clipped.
 */
$android = dirname(__DIR__).'/android/app/src/main/res';
if (is_dir(dirname($android))) {
    $densities = ['mdpi' => 48, 'hdpi' => 72, 'xhdpi' => 96, 'xxhdpi' => 144, 'xxxhdpi' => 192];

    foreach ($densities as $density => $size) {
        $dir = $android.'/mipmap-'.$density;
        if (!is_dir($dir)) mkdir($dir, 0775, true);

        $im = icon($size, false);
        imagepng($im, $dir.'/ic_launcher.png', 9);
        imagedestroy($im);

        // The round variant is masked to a circle by the launcher, so it is
        // the full-bleed drawing rather than the rounded-square one.
        $im = icon($size, true);
        imagepng($im, $dir.'/ic_launcher_round.png', 9);
        imagedestroy($im);

        // Adaptive foreground: 108dp canvas, mark confined to the safe circle.
        $fg = (int)round($size * 108 / 48);
        $im = imagecreatetruecolor($fg, $fg);
        imagealphablending($im, false);
        imagesavealpha($im, true);
        imagefilledrectangle($im, 0, 0, $fg, $fg, imagecolorallocatealpha($im, 0, 0, 0, 127));
        imagealphablending($im, true);
        // The arrow is knocked out in the brand colour rather than in
        // transparency: the background layer is that same colour, so the
        // composite is identical, and it avoids relying on alpha blending
        // behaviour to punch a hole.
        $mark = $fg * 0.52;
        draw_mark($im, ($fg - $mark) / 2, ($fg - $mark) / 2 - $mark * 0.02, $mark,
            imagecolorallocate($im, ...INK), imagecolorallocate($im, ...BRAND));
        imagepng($im, $dir.'/ic_launcher_foreground.png', 9);
        imagedestroy($im);

        echo str_pad('mipmap-'.$density, 26), $size, "x", $size, PHP_EOL;
    }
}

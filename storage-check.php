<?php
declare(strict_types=1);

$root = __DIR__ . '/storage/files';

header('Content-Type: text/plain; charset=utf-8');

echo "Root:\n";
var_dump($root);

echo "\nExists:\n";
var_dump(file_exists($root));

echo "\nIs directory:\n";
var_dump(is_dir($root));

echo "\nReadable:\n";
var_dump(is_readable($root));

echo "\nReal path:\n";
var_dump(realpath($root));

echo "\nDirectory contents:\n";
var_dump(scandir($root));
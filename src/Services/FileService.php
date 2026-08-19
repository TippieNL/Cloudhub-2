<?php
declare(strict_types=1);
namespace CloudHub\Services;
use RuntimeException;

/**
 * Filesystem boundary for CloudHub-managed storage.
 *
 * Client paths are virtual paths rooted at ROOT_DIR. Traversal, control
 * characters, absolute filesystem paths and symlink traversal are rejected.
 */
final class FileService {
 private string $root;
 public function __construct(private array $config) {
  $real=realpath((string)$config['root_dir']);
  if($real===false||!is_dir($real))throw new RuntimeException('Storage root is unavailable',500);
  $this->root=rtrim(str_replace('\\','/',$real),'/');
 }
 public function root(): string{return $this->root;}

 public function sanitize(string $requested): string {
  if(str_contains($requested,"\0"))throw new RuntimeException('Invalid path',400);
  $decoded=rawurldecode($requested);
  if(str_contains($decoded,"\0"))throw new RuntimeException('Invalid path',400);
  $decoded=str_replace('\\','/',$decoded);
  // Virtual paths may start with one slash, but native absolute/drive/UNC paths are never accepted.
  if(preg_match('/^[A-Za-z]:\//',$decoded)||str_starts_with($decoded,'//'))throw new RuntimeException('Absolute filesystem paths are not allowed',400);
  $parts=explode('/',ltrim($decoded,'/'));$safe=[];
  foreach($parts as $part){
   if($part===''||$part==='.')continue;
   if($part==='..')throw new RuntimeException('Path traversal is not allowed',400);
   if(preg_match('/[\x00-\x1F\x7F]/u',$part))throw new RuntimeException('Control characters are not allowed in paths',400);
   $safe[]=$part;
  }
  $candidate=$this->root.($safe?'/'.implode('/',$safe):'');
  $this->assertNoSymlinkTraversal($candidate);
  return $candidate;
 }

 /** Resolve an existing path and prove its canonical target remains under ROOT_DIR. */
 public function existing(string $requested): string {
  $candidate=$this->sanitize($requested);
  $real=realpath($candidate);
  if($real===false)throw new RuntimeException('File or directory not found',404);
  $real=str_replace('\\','/',$real);$this->assertContained($real);
  if($this->pathContainsSymlink($candidate))throw new RuntimeException('Symlink access is not allowed',403);
  return $real;
 }

 /** Resolve a destination while requiring its existing parent to be canonical and contained. */
 public function destination(string $requested): string {
  $candidate=$this->sanitize($requested);
  if($candidate===$this->root)throw new RuntimeException('The storage root cannot be used as a destination item',400);
  $parent=dirname($candidate);$realParent=realpath($parent);
  if($realParent===false||!is_dir($realParent))throw new RuntimeException('Destination parent directory not found',404);
  $realParent=str_replace('\\','/',$realParent);$this->assertContained($realParent);
  if($this->pathContainsSymlink($parent))throw new RuntimeException('Symlink destinations are not allowed',403);
  return rtrim($realParent,'/').'/'.basename($candidate);
 }

 public function relative(string $full): string {
  $full=str_replace('\\','/',$full);$this->assertContained($full);
  $rel=substr($full,strlen($this->root));return $rel===''?'/':$rel;
 }

 public function escapingSymlink(string $path): bool {
  try{return $this->pathContainsSymlink($path);}catch(\Throwable){return true;}
 }

 public function list(string $path): array {
  $dir=$this->existing($path);if(!is_dir($dir))throw new RuntimeException('Directory not found',404);
  $out=[];foreach(scandir($dir)?:[] as $name){
   if($name==='.'||$name==='..'||$name==='.thumbnails')continue;
   $full=$dir.'/'.$name;if(is_link($full)||$this->escapingSymlink($full))continue;
   $isDir=is_dir($full);$rel=$this->relative($full);
   $out[]=['name'=>$name,'path'=>$rel,'isDirectory'=>$isDir,'size'=>$isDir?0:(filesize($full)?:0),'modified'=>gmdate('c',filemtime($full)?:time()),...(!$isDir&&pathinfo($name,PATHINFO_EXTENSION)!==''?['extension'=>'.'.pathinfo($name,PATHINFO_EXTENSION)]:[])];
  }
  usort($out,fn($a,$b)=>$a['isDirectory']!==$b['isDirectory']?($a['isDirectory']?-1:1):strcasecmp($a['name'],$b['name']));return $out;
 }

 public function writable(): void {if($this->config['read_only'])throw new RuntimeException('Server is in read-only mode',403);}

 public function safeName(string $name): string {
  if(str_contains($name,"\0"))throw new RuntimeException('Invalid filename',400);
  $n=basename(str_replace('\\','/',$name));
  if($n===''||$n==='.'||$n==='..'||preg_match('/[\x00-\x1F\x7F]/u',$n))throw new RuntimeException('Invalid filename',400);
  if(strlen($n)>255)throw new RuntimeException('Filename is too long',400);
  return $n;
 }

 public function deleteTree(string $path): void {
  $this->assertContained(str_replace('\\','/',$path));
  if($path===$this->root)throw new RuntimeException('Storage root cannot be deleted',403);
  if(is_link($path)){if(!unlink($path))throw new RuntimeException('Unable to remove symlink',500);return;}
  if(is_dir($path)){
   foreach(scandir($path)?:[] as $n)if($n!=='.'&&$n!=='..')$this->deleteTree($path.'/'.$n);
   if(!rmdir($path))throw new RuntimeException('Unable to remove directory',500);
  }elseif(file_exists($path)&&!unlink($path))throw new RuntimeException('Unable to remove file',500);
 }

 private function assertContained(string $path): void {
  $path=rtrim(str_replace('\\','/',$path),'/');
  if($path!==$this->root&&!str_starts_with($path,$this->root.'/'))throw new RuntimeException('Path escapes the configured storage root',403);
 }
 private function assertNoSymlinkTraversal(string $candidate): void {
  if($this->pathContainsSymlink($candidate))throw new RuntimeException('Symlink traversal is not allowed',403);
 }
 private function pathContainsSymlink(string $candidate): bool {
  $candidate=str_replace('\\','/',$candidate);$this->assertContained($candidate);
  $rel=ltrim(substr($candidate,strlen($this->root)),'/');
  if($rel==='')return false;$cur=$this->root;
  foreach(explode('/',$rel) as $part){$cur.='/'.$part;if(is_link($cur))return true;if(!file_exists($cur))break;}
  return false;
 }
}

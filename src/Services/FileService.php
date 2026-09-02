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
 /**
  * Directory names at the storage root that hold CloudHub's own state.
  *
  * They are never listed and can never be addressed by a client path.
  * Without this the trash could be browsed and hard-deleted through the
  * ordinary file routes, which defeats the point of having a trash.
  */
 public const RESERVED_ROOT_NAMES=['.trash','.thumbnails','.versions'];

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
  if($safe&&in_array($safe[0],self::RESERVED_ROOT_NAMES,true))throw new RuntimeException('That path is reserved',403);
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
  $out=[];foreach($this->children($dir) as $full)$out[]=$this->entry($full);
  usort($out,fn($a,$b)=>$a['isDirectory']!==$b['isDirectory']?($a['isDirectory']?-1:1):strcasecmp($a['name'],$b['name']));return $out;
 }

 /**
  * Readable children of one directory, symlinks and reserved names removed.
  *
  * The reserved skip only applies at the storage root, because that is the
  * only place CloudHub puts those directories -- a user's own folder called
  * ".trash" three levels down is their file and stays visible.
  *
  * @return list<string> absolute paths
  */
 private function children(string $dir): array {
  $atRoot=rtrim($dir,'/')===$this->root;$out=[];
  foreach(scandir($dir)?:[] as $name){
   if($name==='.'||$name==='..')continue;
   if($atRoot&&in_array($name,self::RESERVED_ROOT_NAMES,true))continue;
   $full=$dir.'/'.$name;if(is_link($full)||$this->escapingSymlink($full))continue;
   $out[]=$full;
  }
  return $out;
 }

 /** One listing row. Shared so search results and folder listings never drift apart. */
 private function entry(string $full): array {
  $name=basename($full);$isDir=is_dir($full);
  return ['name'=>$name,'path'=>$this->relative($full),'isDirectory'=>$isDir,'size'=>$isDir?0:(filesize($full)?:0),'modified'=>gmdate('c',filemtime($full)?:time()),...(!$isDir&&pathinfo($name,PATHINFO_EXTENSION)!==''?['extension'=>'.'.pathinfo($name,PATHINFO_EXTENSION)]:[])];
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


 /**
  * Depth-first name search below $path.
  *
  * Bounded twice over: $limit caps what comes back and $maxNodes caps what is
  * examined, so one keystroke cannot turn into an unbounded walk of a deep
  * tree. `truncated` tells the caller which bound was reached, so the UI can
  * say "showing the first N" instead of quietly lying about the result count.
  *
  * @return array{results:list<array>,truncated:bool,scanned:int}
  */
 public function search(string $path,string $needle,int $limit=200,int $maxNodes=20000): array {
  $start=$this->existing($path);if(!is_dir($start))throw new RuntimeException('Directory not found',404);
  $needle=trim($needle);if($needle==='')return ['results'=>[],'truncated'=>false,'scanned'=>0];
  $results=[];$scanned=0;$truncated=false;$stack=[$start];
  while($stack){
   $dir=array_pop($stack);
   foreach($this->children($dir) as $full){
    if(++$scanned>$maxNodes){$truncated=true;break 2;}
    if(stripos(basename($full),$needle)!==false){
     if(count($results)>=$limit){$truncated=true;break 2;}
     $results[]=$this->entry($full);
    }
    if(is_dir($full))$stack[]=$full;
   }
  }
  usort($results,fn($a,$b)=>$a['isDirectory']!==$b['isDirectory']?($a['isDirectory']?-1:1):strcasecmp($a['path'],$b['path']));
  return ['results'=>$results,'truncated'=>$truncated,'scanned'=>$scanned];
 }

 /** Recursive copy that refuses symlinks, mirroring deleteTree's contract. */
 public function copyTree(string $src,string $dst): void {
  $this->assertContained(str_replace('\\','/',$src));$this->assertContained(str_replace('\\','/',$dst));
  if(is_link($src))throw new RuntimeException('Symlinks cannot be copied',403);
  if(is_dir($src)){
   if(!is_dir($dst)&&!mkdir($dst,0775,true)&&!is_dir($dst))throw new RuntimeException('Unable to create the destination directory',500);
   foreach(scandir($src)?:[] as $n){if($n==='.'||$n==='..')continue;$this->copyTree($src.'/'.$n,$dst.'/'.$n);}
   return;
  }
  if(!copy($src,$dst))throw new RuntimeException('Unable to copy '.basename($src),500);
 }

 /**
  * Every file at or below a path, for attributing what a copy just created.
  *
  * @return list<string> absolute paths
  */
 public function copiedFiles(string $path): array {
  if(is_link($path))return [];
  if(!is_dir($path))return [$path];
  $out=[];$stack=[$path];
  while($stack){
   $dir=array_pop($stack);
   foreach(scandir($dir)?:[] as $n){
    if($n==='.'||$n==='..')continue;$full=$dir.'/'.$n;if(is_link($full))continue;
    is_dir($full)?$stack[]=$full:$out[]=$full;
   }
  }
  return $out;
 }

 /**
  * Total bytes and file count below a path.
  *
  * @return array{bytes:int,files:int}
  */
 public function measure(string $path): array {
  if(is_link($path))return ['bytes'=>0,'files'=>0];
  if(!is_dir($path))return ['bytes'=>(int)(filesize($path)?:0),'files'=>1];
  $bytes=0;$files=0;$stack=[$path];
  while($stack){
   $dir=array_pop($stack);
   foreach(scandir($dir)?:[] as $n){
    if($n==='.'||$n==='..')continue;$full=$dir.'/'.$n;if(is_link($full))continue;
    if(is_dir($full)){$stack[]=$full;continue;}
    $bytes+=(int)(filesize($full)?:0);$files++;
   }
  }
  return ['bytes'=>$bytes,'files'=>$files];
 }


 /**
  * One pass over the whole tree, producing everything the storage screen shows.
  *
  * Deliberately unbounded, unlike search(): a dashboard that stopped counting
  * early would report a number that is simply wrong, and "your disk is 60%
  * full" is only useful if it is true. The cost is paid once and cached by the
  * caller rather than reduced by guessing.
  *
  * The trash is measured separately and excluded from the browsable totals,
  * because it is space you can reclaim rather than space you are using.
  */
 public function storageReport(int $largestCount=10): array {
  $acc=['bytes'=>0,'files'=>0,'largest'=>[],'byType'=>[]];
  $folders=[];
  foreach($this->children($this->root) as $child){
   $before=[$acc['bytes'],$acc['files']];
   $this->accumulate($child,$acc,$largestCount);
   if(is_dir($child))$folders[]=['name'=>basename($child),'path'=>$this->relative($child),
    'bytes'=>$acc['bytes']-$before[0],'files'=>$acc['files']-$before[1]];
  }
  usort($folders,fn($a,$b)=>$b['bytes']<=>$a['bytes']);

  $largest=$acc['largest'];
  usort($largest,fn($a,$b)=>$b['bytes']<=>$a['bytes']);
  arsort($acc['byType']);

  // Summed from what each entry recorded when it was trashed, rather than by
  // measuring .trash: that would count the bookkeeping files as reclaimable
  // space and report a folder of one file as two.
  $trash=['bytes'=>0,'files'=>0,'entries'=>0];
  foreach($this->trashList() as $entry){
   $trash['bytes']+=(int)($entry['bytes']??0);
   $trash['files']+=(int)($entry['files']??0);
   $trash['entries']++;
  }

  // Versions are measured rather than summed from metadata: unlike the trash
  // they *are* ordinary bytes on the disk, they count against a quota, and the
  // storage page is where you find out what your history costs.
  $versions=$this->versionsUsage();

  return ['bytes'=>$acc['bytes'],'files'=>$acc['files'],'folders'=>$folders,
   'largest'=>array_slice($largest,0,$largestCount),
   'byType'=>$acc['byType'],'trash'=>$trash,'versions'=>$versions,
   'diskTotal'=>(int)(@disk_total_space($this->root)?:0),
   'diskFree'=>(int)(@disk_free_space($this->root)?:0),
   'measuredAt'=>gmdate('c')];
 }

 /**
  * Every file in the store, handed to $visit one at a time.
  *
  * Shared with the duplicate finder so it walks the tree by exactly the rules
  * the rest of the application does: the bookkeeping folders at the root are
  * skipped, symlinks are not followed, and nothing outside the root is
  * reachable. A second walker written beside this one would drift from it, and
  * a duplicate report that lists files out of .trash is worse than none.
  *
  * @param callable(string $absolute, string $relative, int $bytes, int $modified): bool $visit
  *        returning false stops the walk
  */
 public function eachFile(callable $visit): void {
  $this->walkFiles($this->root,$visit);
 }

 private function walkFiles(string $dir,callable $visit): bool {
  foreach($this->children($dir) as $child){
   if(is_link($child))continue;
   if(is_dir($child)){
    if(!$this->walkFiles($child,$visit))return false;
    continue;
   }
   if(!is_file($child))continue;
   if($visit($child,$this->relative($child),(int)(filesize($child)?:0),(int)(filemtime($child)?:0))===false)return false;
  }
  return true;
 }

 private function accumulate(string $path,array &$acc,int $largestCount): void {
  if(is_link($path))return;
  if(is_dir($path)){
   foreach($this->children($path) as $child)$this->accumulate($child,$acc,$largestCount);
   return;
  }
  $size=(int)(filesize($path)?:0);
  $acc['bytes']+=$size;$acc['files']++;
  $type=self::fileCategory($path);
  $acc['byType'][$type]=($acc['byType'][$type]??0)+$size;

  // Trimmed periodically rather than sorted on every file: a hundred thousand
  // files would otherwise mean a hundred thousand sorts.
  $acc['largest'][]=['name'=>basename($path),'path'=>$this->relative($path),'bytes'=>$size];
  if(count($acc['largest'])>$largestCount*8){
   usort($acc['largest'],fn($a,$b)=>$b['bytes']<=>$a['bytes']);
   $acc['largest']=array_slice($acc['largest'],0,$largestCount);
  }
 }

 /** Coarse grouping for "what is filling the disk", by extension. */
 public static function fileCategory(string $path): string {
  static $map=null;
  if($map===null){
   $map=[];
   foreach(['image'=>['jpg','jpeg','png','gif','webp','bmp','svg','avif','heic','tif','tiff'],
    'video'=>['mp4','webm','ogv','mov','m4v','avi','mkv','mpeg','mpg','3gp','ts','m2ts','mts'],
    'audio'=>['mp3','wav','ogg','oga','m4a','aac','flac','opus'],
    'document'=>['pdf','doc','docx','odt','xls','xlsx','ods','ppt','pptx','odp','txt','md','csv','rtf'],
    'archive'=>['zip','tar','gz','bz2','xz','7z','rar','iso']] as $group=>$exts)
    foreach($exts as $e)$map[$e]=$group;
  }
  $ext=strtolower(pathinfo($path,PATHINFO_EXTENSION));
  return $map[$ext]??'other';
 }

 /* ---- Trash ------------------------------------------------------------
  *
  * The trash lives inside the storage root so that moving a file into it is
  * always a same-filesystem rename -- an instant, atomic operation that
  * cannot half-finish, and cannot fail because the root is a separate mount.
  * sanitize() refuses to address it, so it is invisible to every other route.
  *
  * Layout, one directory per deletion:
  *   .trash/<id>/meta.json      what it was and where it came from
  *   .trash/<id>/payload/<name> the item itself, under its original name
  *
  * The payload subdirectory is what makes name collisions impossible: two
  * files called the same thing, and a file called "meta.json", all coexist.
  */

 private const TRASH_ID='/^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$/';

 public function trashRoot(): string {return $this->root.'/.trash';}

 /** Move an item into the trash and return its recorded metadata. */
 public function trash(string $realPath,?string $actor=null): array {
  $realPath=str_replace('\\','/',$realPath);$this->assertContained($realPath);
  if(rtrim($realPath,'/')===$this->root)throw new RuntimeException('Storage root cannot be deleted',403);
  if(str_starts_with($realPath.'/',$this->trashRoot().'/'))throw new RuntimeException('That item is already in the trash',400);
  if(!file_exists($realPath))throw new RuntimeException('File or directory not found',404);

  // Recorded before the move, because afterwards the original path is gone.
  $original=$this->relative($realPath);
  $isDir=is_dir($realPath)&&!is_link($realPath);
  $measured=$this->measure($realPath);

  $id=gmdate('Ymd-His').'-'.bin2hex(random_bytes(4));
  $entry=$this->trashRoot().'/'.$id;
  if(!mkdir($entry.'/payload',0775,true))throw new RuntimeException('Unable to open the trash',500);

  $name=basename($realPath);
  if(!rename($realPath,$entry.'/payload/'.$name)){
   $this->deleteTree($entry);
   throw new RuntimeException('Unable to move '.$name.' to the trash',500);
  }

  $meta=['id'=>$id,'name'=>$name,'originalPath'=>$original,'isDirectory'=>$isDir,
   'bytes'=>$measured['bytes'],'files'=>$measured['files'],
   'deletedAt'=>gmdate('c'),'deletedBy'=>$actor];
  file_put_contents($entry.'/meta.json',json_encode($meta,JSON_UNESCAPED_SLASHES|JSON_PRETTY_PRINT));
  return $meta;
 }

 /** @return list<array> newest deletion first */
 public function trashList(): array {
  $dir=$this->trashRoot();if(!is_dir($dir))return [];
  $out=[];
  foreach(scandir($dir)?:[] as $id){
   if($id==='.'||$id==='..'||!preg_match(self::TRASH_ID,$id))continue;
   $meta=$this->trashMeta($id);if($meta!==null)$out[]=$meta;
  }
  usort($out,fn($a,$b)=>strcmp((string)$b['deletedAt'],(string)$a['deletedAt']));
  return $out;
 }

 /**
  * Put a trashed item back, and report the path it landed on.
  *
  * Restore never overwrites and never fails on a busy name: if something now
  * occupies the original path the item is restored beside it with a suffix.
  * A missing parent folder is recreated, so restoring survives the folder
  * having been deleted too.
  */
 public function restore(string $id): array {
  $meta=$this->trashMeta($id);
  if($meta===null)throw new RuntimeException('That trash entry no longer exists',404);
  $payload=$this->trashRoot().'/'.$id.'/payload/'.$meta['name'];
  if(!file_exists($payload))throw new RuntimeException('The trashed item is missing from disk',410);

  $target=$this->sanitize((string)$meta['originalPath']);
  $parent=dirname($target);
  if(!is_dir($parent)&&!mkdir($parent,0775,true)&&!is_dir($parent))throw new RuntimeException('Unable to recreate the original folder',500);
  $target=$this->freeName($target);

  if(!rename($payload,$target))throw new RuntimeException('Unable to restore '.$meta['name'],500);
  $this->deleteTree($this->trashRoot().'/'.$id);
  return ['path'=>$this->relative($target),'renamed'=>basename($target)!==$meta['name']];
 }

 /** Permanently remove one trash entry, or every entry when $id is null. */
 public function trashPurge(?string $id=null): int {
  if($id!==null){
   if(!preg_match(self::TRASH_ID,$id))throw new RuntimeException('Unknown trash entry',404);
   $entry=$this->trashRoot().'/'.$id;
   if(!is_dir($entry))throw new RuntimeException('That trash entry no longer exists',404);
   $this->deleteTree($entry);return 1;
  }
  $n=0;foreach($this->trashList() as $meta){$this->deleteTree($this->trashRoot().'/'.$meta['id']);$n++;}
  return $n;
 }

 /** Drop entries deleted longer than $days ago. 0 disables expiry. */
 public function trashPurgeExpired(int $days): int {
  if($days<=0)return 0;
  $cutoff=time()-$days*86400;$n=0;
  foreach($this->trashList() as $meta){
   $at=strtotime((string)$meta['deletedAt']);
   if($at!==false&&$at<$cutoff){$this->deleteTree($this->trashRoot().'/'.$meta['id']);$n++;}
  }
  return $n;
 }

 private function trashMeta(string $id): ?array {
  if(!preg_match(self::TRASH_ID,$id))return null;
  $file=$this->trashRoot().'/'.$id.'/meta.json';
  if(!is_file($file))return null;
  $meta=json_decode((string)file_get_contents($file),true);
  if(!is_array($meta)||!isset($meta['name'],$meta['originalPath']))return null;
  $meta['id']=$id;
  return $meta;
 }

 /** The given path if free, otherwise the same name with " (n)" appended. */
 public function freeName(string $target): string {
  if(!file_exists($target))return $target;
  $dir=dirname($target);$name=basename($target);
  $ext=pathinfo($name,PATHINFO_EXTENSION);
  $stem=$ext===''?$name:substr($name,0,-(strlen($ext)+1));
  for($i=2;$i<1000;$i++){
   $candidate=$dir.'/'.$stem.' ('.$i.')'.($ext===''?'':'.'.$ext);
   if(!file_exists($candidate))return $candidate;
  }
  throw new RuntimeException('Too many items with that name',409);
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

 /* ---- previous versions --------------------------------------------------
  *
  * The trash covers deleting a file. Nothing covered *replacing* one: an
  * upload with the same name and the overwrite policy unlinked what was there,
  * and the previous contents were simply gone.
  *
  * Same shape as the trash, and for the same reasons: inside the storage root
  * so archiving is an atomic same-filesystem rename, and a payload
  * subdirectory so a file called "meta.json" cannot collide with the
  * bookkeeping.
  *
  *   .versions/<key>/<id>/meta.json      what it was and when
  *   .versions/<key>/<id>/payload/<name> the bytes themselves
  *
  * <key> is a hash of the file's path, so listing one file's history is a
  * directory read rather than a walk of every version ever kept. Versions
  * follow the path: renaming or moving a file leaves its history behind, the
  * same way the trash already behaves.
  */

 private const VERSION_ID='/^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$/';

 public function versionsRoot(): string {return $this->root.'/.versions';}

 /** Where one file's history lives. Hashed, so any path is a safe directory name. */
 private function versionKey(string $relative): string {
  return substr(hash('sha256','/'.ltrim($relative,'/')),0,32);
 }

 /**
  * Archive the file currently at $realPath, and return what was recorded.
  *
  * Called instead of unlinking when an upload overwrites. Returns null when
  * there is nothing to keep, so the caller can carry on regardless.
  */
 public function keepVersion(string $realPath,?string $actor=null,?int $maxVersions=null): ?array {
  $realPath=str_replace('\\','/',$realPath);$this->assertContained($realPath);
  if(!is_file($realPath)||is_link($realPath))return null;

  $original=$this->relative($realPath);
  $bytes=(int)(filesize($realPath)?:0);
  $id=gmdate('Ymd-His').'-'.bin2hex(random_bytes(4));
  $entry=$this->versionsRoot().'/'.$this->versionKey($original).'/'.$id;
  if(!mkdir($entry.'/payload',0775,true))throw new RuntimeException('Unable to open the version store',500);

  $name=basename($realPath);
  if(!rename($realPath,$entry.'/payload/'.$name)){
   $this->deleteTree($entry);
   throw new RuntimeException('Unable to keep the previous version of '.$name,500);
  }

  /*
   * Milliseconds as well as the ISO stamp.
   *
   * The id carries only whole seconds, so several rewrites inside one second
   * share a prefix and differ by random bytes -- sorting on the id then orders
   * them arbitrarily, and "newest first" is a coin toss. That is not a corner
   * case: a sync client saving repeatedly does exactly this, and it decides
   * which version the cap throws away.
   */
  $meta=['id'=>$id,'name'=>$name,'path'=>$original,'bytes'=>$bytes,
   'keptAt'=>gmdate('c'),'keptAtMs'=>(int)round(microtime(true)*1000),'keptBy'=>$actor];
  file_put_contents($entry.'/meta.json',json_encode($meta,JSON_UNESCAPED_SLASHES|JSON_PRETTY_PRINT));

  // Trimmed here rather than only on the sweep, so a file rewritten every
  // night cannot grow its history without bound between sweeps.
  if($maxVersions!==null&&$maxVersions>0)$this->trimVersions($original,$maxVersions);
  return $meta;
 }

 /** @return list<array> newest first */
 public function versionList(string $relative): array {
  $dir=$this->versionsRoot().'/'.$this->versionKey($relative);
  if(!is_dir($dir))return [];
  $out=[];
  foreach(scandir($dir)?:[] as $id){
   if($id==='.'||$id==='..'||!preg_match(self::VERSION_ID,$id))continue;
   $meta=$this->versionMeta($relative,$id);
   if($meta!==null)$out[]=$meta;
  }
  // The id is the tie-break, so the order is at least stable for entries kept
  // before the millisecond stamp existed.
  usort($out,fn($a,$b)=>((int)($b['keptAtMs']??0)<=>(int)($a['keptAtMs']??0))
   ?:strcmp((string)$b['id'],(string)$a['id']));
  return $out;
 }

 /** The archived file itself, for streaming back. */
 public function versionPayload(string $relative,string $id): string {
  $meta=$this->versionMeta($relative,$id);
  if($meta===null)throw new RuntimeException('Version not found',404);
  $file=$this->versionsRoot().'/'.$this->versionKey($relative).'/'.$id.'/payload/'.$meta['name'];
  if(!is_file($file))throw new RuntimeException('Version contents are missing',404);
  return $file;
 }

 /**
  * Put a version back, keeping what it replaces.
  *
  * Restoring is itself a change, so the current file is archived on the way --
  * otherwise recovering the wrong version would destroy the right one.
  */
 public function versionRestore(string $relative,string $id,?string $actor=null,?int $maxVersions=null): array {
  $source=$this->versionPayload($relative,$id);
  $target=$this->destination($relative);
  if(is_dir($target))throw new RuntimeException('A folder is in the way',409);
  if(is_file($target))$this->keepVersion($target,$actor,$maxVersions);
  if(!rename($source,$target)){
   if(!copy($source,$target))throw new RuntimeException('Unable to restore that version',500);
   @unlink($source);
  }
  $this->deleteTree($this->versionsRoot().'/'.$this->versionKey($relative).'/'.$id);
  return ['path'=>$relative,'restored'=>$id];
 }

 public function versionDelete(string $relative,string $id): bool {
  if(!preg_match(self::VERSION_ID,$id))throw new RuntimeException('Unknown version',404);
  $dir=$this->versionsRoot().'/'.$this->versionKey($relative).'/'.$id;
  if(!is_dir($dir))return false;
  $this->deleteTree($dir);
  return true;
 }

 /** Drop the oldest until only $keep remain. */
 public function trimVersions(string $relative,int $keep): int {
  if($keep<=0)return 0;
  $all=$this->versionList($relative);
  $n=0;
  foreach(array_slice($all,$keep) as $meta){
   $this->deleteTree($this->versionsRoot().'/'.$this->versionKey($relative).'/'.$meta['id']);
   $n++;
  }
  return $n;
 }

 /**
  * Drop versions older than $days, across every file.
  *
  * Runs beside the trash sweep. Empty key directories go too, or the store
  * accumulates a directory per file ever overwritten.
  */
 public function versionsPurgeExpired(int $days): int {
  if($days<=0)return 0;
  $root=$this->versionsRoot();if(!is_dir($root))return 0;
  $cutoff=time()-$days*86400;$n=0;
  foreach(scandir($root)?:[] as $key){
   if($key==='.'||$key==='..')continue;
   $keyDir=$root.'/'.$key;if(!is_dir($keyDir))continue;
   foreach(scandir($keyDir)?:[] as $id){
    if($id==='.'||$id==='..'||!preg_match(self::VERSION_ID,$id))continue;
    $meta=@json_decode((string)@file_get_contents($keyDir.'/'.$id.'/meta.json'),true);
    $at=strtotime((string)($meta['keptAt']??''));
    if($at!==false&&$at<$cutoff){$this->deleteTree($keyDir.'/'.$id);$n++;}
   }
   $rest=array_diff(scandir($keyDir)?:[],['.','..']);
   if(!$rest)@rmdir($keyDir);
  }
  return $n;
 }

 /** What the whole history costs on disk, for the storage dashboard. */
 public function versionsUsage(): array {
  $root=$this->versionsRoot();
  if(!is_dir($root))return ['bytes'=>0,'files'=>0];
  $measured=$this->measure($root);
  return ['bytes'=>$measured['bytes'],'files'=>$measured['files']];
 }

 private function versionMeta(string $relative,string $id): ?array {
  if(!preg_match(self::VERSION_ID,$id))return null;
  $file=$this->versionsRoot().'/'.$this->versionKey($relative).'/'.$id.'/meta.json';
  if(!is_file($file))return null;
  $meta=json_decode((string)file_get_contents($file),true);
  return is_array($meta)?$meta:null;
 }
}

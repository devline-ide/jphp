<?php
function cs_effect($tag='argument',$value=4){global $csEvents;$csEvents[]=$tag;return $value;}
function cs_result($owner,&$p,$other){cs_effect('body');$old=$p;$p=9;return [$owner,$old,$other];}
function cs_capture($label,$fn){global $csEvents;$csEvents=[];$result=null;set_error_handler(function($n,$m){global $csEvents;$csEvents[]=[$n,$m];return true;});try{$result=$fn();}catch(Throwable $e){$csEvents[]=[get_class($e),$e->getMessage()];}restore_error_handler();return [$label,$result,$csEvents];}
class CsPlain {
 public function m(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public static function s(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private function p(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private static function ps(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected function q(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected static function qs(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public function run($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public static function runStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function closure(){return function($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];};}
}
class CsPlainChild extends CsPlain { public function own($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];} public static function ownStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function p($p,$other=0){return ["child-public",$p,$other];}
}
class CsDynamic {
 public function m(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public static function s(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private function p(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private static function ps(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected function q(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected static function qs(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public function __call($name,$args){cs_effect("dynamic-magic");return [static::class,$name,$args];}
 public function run($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public static function runStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function closure(){return function($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];};}
}
class CsDynamicChild extends CsDynamic { public function own($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];} public static function ownStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function p($p,$other=0){return ["child-public",$p,$other];}
}
class CsStatic {
 public function m(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public static function s(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private function p(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private static function ps(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected function q(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected static function qs(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public static function __callStatic($name,$args){cs_effect("static-magic");return [static::class,$name,$args];}
 public function run($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public static function runStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function closure(){return function($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];};}
}
class CsStaticChild extends CsStatic { public function own($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];} public static function ownStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function p($p,$other=0){return ["child-public",$p,$other];}
}
class CsBoth {
 public function m(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public static function s(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private function p(&$p,$other=0){return cs_result(static::class,$p,$other);}
 private static function ps(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected function q(&$p,$other=0){return cs_result(static::class,$p,$other);}
 protected static function qs(&$p,$other=0){return cs_result(static::class,$p,$other);}
 public function __call($name,$args){cs_effect("dynamic-magic");return [static::class,$name,$args];}
 public static function __callStatic($name,$args){cs_effect("static-magic");return [static::class,$name,$args];}
 public function run($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public static function runStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function closure(){return function($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];};}
}
class CsBothChild extends CsBoth { public function own($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];} public static function ownStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}
 public function p($p,$other=0){return ["child-public",$p,$other];}
}
class CsOther {public function run($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}public static function runStatic($fn){$x=3;$r=$fn($x,cs_effect());return [$r,$x];}}
$rows=[];
foreach(["Plain","Dynamic","Static","Both"] as $family){$class="Cs".$family;$child=$class."Child";
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/global/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/global/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/global/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/global/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/global/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/global/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-instance/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$o=new $class;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-instance/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$o=new $class;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-instance/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$o=new $class;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-instance/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$o=new $class;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-instance/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$o=new $class;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-instance/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$o=new $class;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-inherited/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$o=new $child;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-inherited/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$o=new $child;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-inherited/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$o=new $child;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-inherited/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$o=new $child;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-inherited/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$o=new $child;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-inherited/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$o=new $child;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$o=new $child;return $o->own($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$o=new $child;return $o->own($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$o=new $child;return $o->own($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$o=new $child;return $o->own($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$o=new $child;return $o->own($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$o=new $child;return $o->own($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-static/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];return $class::runStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-static/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];return $class::runStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-static/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;return $class::runStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-static/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;return $class::runStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-static/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];return $class::runStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/base-static/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];return $class::runStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own-static/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];return $child::ownStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own-static/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];return $child::ownStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own-static/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;return $child::ownStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own-static/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;return $child::ownStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own-static/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];return $child::ownStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/child-own-static/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];return $child::ownStatic($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/unrelated/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$o=new CsOther;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/unrelated/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$o=new CsOther;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/unrelated/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$o=new CsOther;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/unrelated/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$o=new CsOther;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/unrelated/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$o=new CsOther;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/unrelated/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$o=new CsOther;return $o->run($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-inherited/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$o=new $child;$f=$o->closure();return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-inherited/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$o=new $child;$f=$o->closure();return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-inherited/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$o=new $child;$f=$o->closure();return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-inherited/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$o=new $child;$f=$o->closure();return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-inherited/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$o=new $child;$f=$o->closure();return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-inherited/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$o=new $child;$f=$o->closure();return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-child-scope/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,$child);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-child-scope/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,$child);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-child-scope/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,$child);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-child-scope/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,$child);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-child-scope/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,$child);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-child-scope/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,$child);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-null-scope/class-array/".$name,function()use($class,$child,$name){$fn=[$class,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,null);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-null-scope/child-class-array/".$name,function()use($class,$child,$name){$fn=[$child,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,null);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-null-scope/class-string/".$name,function()use($class,$child,$name){$fn=$class."::".$name;$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,null);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-null-scope/child-class-string/".$name,function()use($class,$child,$name){$fn=$child."::".$name;$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,null);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-null-scope/object-array/".$name,function()use($class,$child,$name){$fn=[new $class,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,null);return $f($fn);});}
foreach(["m","s","p","ps","q","qs","missing","M","S","PS","bad::s",""] as $name){
$rows[]=cs_capture($family."/closure-null-scope/child-object-array/".$name,function()use($class,$child,$name){$fn=[new $child,$name];$o=new $class;$f=$o->closure();$f=$f->bindTo(new $child,null);return $f($fn);});}
}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/global/array/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=[$relative,$name];$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/global/string/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=$relative."::".$name;$x=3;$r=$fn($x,cs_effect());return [$r,$x];});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/base-instance/array/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=[$relative,$name];$o=new $class;return $o->run($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/base-instance/string/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=$relative."::".$name;$o=new $class;return $o->run($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/child-inherited/array/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=[$relative,$name];$o=new $child;return $o->run($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/child-inherited/string/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=$relative."::".$name;$o=new $child;return $o->run($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/child-own/array/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=[$relative,$name];$o=new $child;return $o->own($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/child-own/string/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=$relative."::".$name;$o=new $child;return $o->own($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/base-static/array/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=[$relative,$name];return $class::runStatic($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/base-static/string/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=$relative."::".$name;return $class::runStatic($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/child-own-static/array/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=[$relative,$name];return $child::ownStatic($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/child-own-static/string/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=$relative."::".$name;return $child::ownStatic($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/unrelated/array/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=[$relative,$name];$o=new CsOther;return $o->run($fn);});}
foreach(["self","parent","static","CsMissing","CsPlain"] as $relative)foreach(["s","CsPlain::s","self::s",""] as $name){
$rows[]=cs_capture("relative/unrelated/string/".$relative."/".$name,function()use($relative,$name){$class="CsPlain";$child="CsPlainChild";$fn=$relative."::".$name;$o=new CsOther;return $o->run($fn);});}
return $rows;

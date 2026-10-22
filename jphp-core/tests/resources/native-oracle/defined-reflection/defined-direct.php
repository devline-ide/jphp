<?php
function row($id,$value){echo json_encode([$id,$value],JSON_THROW_ON_ERROR|JSON_UNESCAPED_SLASHES),"\n";}
class NoParent {static function run(){try{row('direct-parent-no-parent',defined('parent::X'));}catch(Throwable $e){row('direct-parent-no-parent',[get_class($e),$e->getMessage()]);}}}
NoParent::run();
class Outer {const X=null;static function run(){return [defined('SELF::X'),defined('STATIC::X')];}}
row('direct-keyword-case',Outer::run());

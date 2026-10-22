<?php
error_reporting(-1);$e=[];$calls=0;class C{public $v;function __construct(){global $calls;$this->v=++$calls;}}function f($x=new C){return $x->v;}$e[]='one:'.f();$e[]='two:'.f();echo serialize($e);

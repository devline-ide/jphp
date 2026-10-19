<?php
$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;function leaf(&$w){$w=6;}function borrow(&$v){leaf($v);}borrow($r);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;
<?php
#[NoDiscard] function borrow(&$v){}
$f=function &(){static $x=0;return $x;};
$r=&$f();$r=5;
borrow($r);
unset($r);
$g=clone $f;$s=&$g();$s=9;$t=&$f();
echo $t,',',$s,',',$held,"\n";

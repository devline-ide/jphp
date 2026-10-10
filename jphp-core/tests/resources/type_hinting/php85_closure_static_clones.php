<?php
$f=function(){static $x=0;return ++$x;};echo 'clone-static:',$f();$g=clone $f;echo ',',$f(),',',$g(),',',$f(),',',$g(),"\n";
$f=function(){static $x=0;return ++$x;};echo 'bind-static:',$f();$g=$f->bindTo(null);echo ',',$f(),',',$g(),',',$f(),',',$g(),"\n";
$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$g=clone $f;$s=&$g();$s=9;echo 'clone-ref-static:',$r,',',$s,"\n";
$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$g=$f->bindTo(null);$s=&$g();$s=9;echo 'bind-ref-static:',$r,',',$s,"\n";
$f=function(){static $x=[];$x[]=count($x)+1;return implode(',',$x);};echo 'clone-array:',$f();$g=clone $f;echo '|',$f(),'|',$g(),'|',$f(),'|',$g(),"\n";
$f=function(){static $x=null;if ($x===null) $x=(object)['n'=>0];return ++$x->n;};echo 'clone-object:',$f();$g=clone $f;echo ',',$f(),',',$g(),"\n";
$f=function(){static $x=0;return ++$x;};class StaticCallOwner {} $o=new StaticCallOwner;echo 'call-warm:',$f(),',',$f->call($o),',',$f(),',',$f->call($o),',',$f(),"\n";
$f=function(){static $x=0;return ++$x;};echo 'call-cold:',$f->call($o),',',$f(),',',$f->call($o),"\n";
$f=function(&$e,$bind){static $x=1;if ($bind) $x=&$e;return $x;};$e=10;echo 'static-rebind:',$f($e,true);$e=20;echo ',',$f($e,false);$g=clone $f;$e=30;echo ',',$g($e,false),"\n";

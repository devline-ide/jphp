<?php
class Once {
    public static $calls=0;
    function __destruct(){self::$calls++;echo 'once:'.self::$calls."\n";$GLOBALS['revived']=$this;}
}
$o=new Once;unset($o);unset($GLOBALS['revived']);echo 'resurrection-count:'.Once::$calls."\n";
class ThrowOnce {
    public static $calls=0;
    function __destruct(){self::$calls++;$GLOBALS['thrownRevived']=$this;throw new Exception('destructor');}
}
try{$o=new ThrowOnce;unset($o);}catch(Throwable $e){echo 'throw:'.get_class($e).':'.$e->getMessage()."\n";}
unset($GLOBALS['thrownRevived']);echo 'throw-count:'.ThrowOnce::$calls."\n";

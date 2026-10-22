<?php
error_reporting(-1);$e=[];$calls=0;function f($x=E_STRICT){return $x;}set_error_handler(function($n,$s)use(&$e,&$calls){$e[]='warn:'.$n.':'.$s;if(++$calls===1)$e[]='inner:'.f();return true;});$e[]='outer:'.f();$e[]='again:'.f();restore_error_handler();echo serialize($e);

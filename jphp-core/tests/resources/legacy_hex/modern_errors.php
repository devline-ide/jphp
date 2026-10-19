$out='';set_error_handler(function($n,$m)use(&$out){$out.=$n.':'.$m.'|';return true;});
foreach(array('A','ABC','G1','0g') as $hex) {$v=hex2bin($hex);$out.=($v===false?'false':bin2hex($v)).';';}
restore_error_handler();return $out;

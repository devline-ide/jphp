function legacyHexRoute($route,$hex) {
    switch($route) {
        case 'direct': return hex2bin($hex);
        case 'dynamic': $f='hex2bin';return $f($hex);
        case 'cufa': return call_user_func_array('hex2bin',array($hex));
        case 'reflection': $f=new ReflectionFunction('hex2bin');return $f->invokeArgs(array($hex));
    }
}
$out='';foreach(array('direct','dynamic','cufa','reflection') as $route) {
    foreach(array('c3a9','fffe80','30','00017f80ffc328') as $hex) {
        $s=legacyHexRoute($route,$hex);$r=&$s;$t=$s.'X';$p='X'.$s;$n=$s . 17;
        $out.=$route.':'.$hex.':'.bin2hex($t).':'.bin2hex($p).':'.bin2hex($n).':';
        $t=$s;$t.='Y';$out.=bin2hex($t).':'.bin2hex($s).':';
        $r.='Z';$out.=bin2hex($s).':'.bin2hex($r).';';
    }
}return $out;

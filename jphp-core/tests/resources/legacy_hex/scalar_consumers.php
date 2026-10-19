function legacyHexRoute($route,$hex) {
    switch($route) {
        case 'direct': return hex2bin($hex);
        case 'dynamic': $f='hex2bin';return $f($hex);
        case 'cufa': return call_user_func_array('hex2bin',array($hex));
        case 'reflection': $f=new ReflectionFunction('hex2bin');return $f->invokeArgs(array($hex));
    }
}
$out='';foreach(array('direct','dynamic','cufa','reflection') as $route) {
    foreach(array('','00','30','3041','fffe80','c3a9','00017f80ffc328') as $hex) {
        $s=legacyHexRoute($route,$hex);$r=&$s;
        $out.=$route.':'.$hex.':'.bin2hex($s).':'.strlen($s).':'.strlen($r).':'.((bool)$s?'1':'0');
        if(strlen($s)>0) $out.=':'.ord($s).':'.ord($r).':'.bin2hex($s[0]).':'.bin2hex($s[strlen($s)-1]);
        $out.=';';
    }
}return $out;

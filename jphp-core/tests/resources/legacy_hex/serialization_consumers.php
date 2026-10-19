function legacyHexRoute($route,$hex) {
    switch($route) {
        case 'direct': return hex2bin($hex);
        case 'dynamic': $f='hex2bin';return $f($hex);
        case 'cufa': return call_user_func_array('hex2bin',array($hex));
        case 'reflection': $f=new ReflectionFunction('hex2bin');return $f->invokeArgs(array($hex));
    }
}
$out='';foreach(array('direct','dynamic','cufa','reflection') as $route) {
    foreach(array('','30','c3a9','fffe80','00017f80ffc328') as $hex) {
        $s=legacyHexRoute($route,$hex);$wire=serialize($s);
        $out.=$route.':'.$hex.':'.bin2hex($wire).':'.bin2hex(unserialize($wire)).':';
        $out.=bin2hex(unserialize(hex2bin(bin2hex($wire)))).':';
        $a=unserialize(serialize(array($s,'ASCII')));$out.=bin2hex($a[0]).':'.$a[1].';';
    }
}return $out;

function legacyHexRoute($route,$hex) {
    switch($route) {
        case 'direct': return hex2bin($hex);
        case 'dynamic': $f='hex2bin';return $f($hex);
        case 'cufa': return call_user_func_array('hex2bin',array($hex));
        case 'reflection': $f=new ReflectionFunction('hex2bin');return $f->invokeArgs(array($hex));
    }
}
foreach(array('direct','dynamic','cufa','reflection') as $route) {
    foreach(array('','00','30','3041','fffe80','c3a9','00017f80ffc328') as $hex) echo legacyHexRoute($route,$hex);
}return 'ok';

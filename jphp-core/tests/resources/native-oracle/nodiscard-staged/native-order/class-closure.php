<?php
require __DIR__.'/../native/helper.php';
class C {function make(){return #[NoDiscard] function(){emit('body',true);return 1;};}}
$f=(new C)->make();$f();

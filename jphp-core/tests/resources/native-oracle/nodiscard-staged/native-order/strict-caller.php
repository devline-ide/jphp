<?php
declare(strict_types=1);
require __DIR__.'/../native/helper.php';require __DIR__.'/weak-declarations.php';
emit('route','strict-caller-weak-declaration');attempt('discard',function(){weakMarked();});
attempt('reflection',fn()=>(new ReflectionFunction('weakMarked'))->getAttributes()[0]->newInstance());

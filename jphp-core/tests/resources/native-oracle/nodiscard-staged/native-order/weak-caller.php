<?php
require __DIR__.'/../native/helper.php';require __DIR__.'/strict-declarations.php';
emit('route','weak-caller-strict-declaration');attempt('discard',function(){strictMarked();});
attempt('reflection',fn()=>(new ReflectionFunction('strictMarked'))->getAttributes()[0]->newInstance());

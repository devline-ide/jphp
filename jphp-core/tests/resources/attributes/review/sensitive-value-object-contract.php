<?php
require __DIR__."/helper.php";
$v=new SensitiveParameterValue('qa-secret');emit('value',$v->getValue());emit('debug',$v->__debugInfo());attempt('serialize',fn()=>serialize($v));attempt('clone',function()use($v){return (clone $v)->getValue();});

<?php
require __DIR__ . "/helper.php";
#[NoDiscard('use result')] function marked($arg=0) { emit('body',$arg); return 7; }
marked(1); $f='marked'; $f(2); $c=marked(...); $c(3); call_user_func('marked',4); call_user_func_array('marked',[5]); (new ReflectionFunction('marked'))->invoke(6);
emit('used',marked(7)); $unused=marked(8); (void)marked(9); (int)marked(10);

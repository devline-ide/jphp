<?php
require __DIR__."/helper.php";
#[NoDiscard('use result')] function marked($value){emit('body',$value);return 7;}
emit('route','direct'); marked(1);
emit('route','dynamic'); $f='marked'; $f(2);
emit('route','firstclass'); $c=marked(...); $c(3);
emit('route','cuf'); call_user_func('marked',4);
emit('route','cufa'); call_user_func_array('marked',[5]);
emit('route','reflection'); (new ReflectionFunction('marked'))->invoke(6);
emit('route','used'); emit('value',marked(7));
emit('route','assigned'); $unused=marked(8);
emit('route','void'); (void)marked(9);
emit('route','intcast'); (int)marked(10);

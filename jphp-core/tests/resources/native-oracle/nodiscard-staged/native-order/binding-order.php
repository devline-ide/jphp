<?php
require __DIR__.'/../native/helper.php';
#[NoDiscard] function marked(int $v){emit('body',$v);return $v;}
function arg(){emit('arg',true);return 1;}
emit('route','missing');attempt('missing',function(){marked();});
emit('route','unknown');attempt('unknown',function(){marked(other:arg());});
emit('route','duplicate');attempt('duplicate',function(){marked(arg(),...['v'=>2]);});
emit('route','bad-unpack');attempt('bad-unpack',function(){marked(...arg());});
emit('route','duplicate-unpack');attempt('duplicate-unpack',function(){marked(...['v'=>arg()],...['v'=>2]);});
emit('route','extra');attempt('extra',function(){marked(arg(),arg());});
emit('route','cuf-missing');attempt('cuf-missing',function(){call_user_func('marked');});
emit('route','cufa-unknown');attempt('cufa-unknown',function(){call_user_func_array('marked',['other'=>arg()]);});

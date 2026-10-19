<?php
require __DIR__.'/helper.php';
$closure=#[NoDiscard] function($v){emit('closure-body',$v);return 1;};
emit('route','closure');$closure(1);emit('route','closure-used');emit('result',$closure(2));
$arrow=#[NoDiscard] fn($v)=>$v;emit('route','arrow');$arrow(3);
#[NoDiscard] function generator(){emit('generator-body',true);yield 1;}
emit('route','generator-discard');generator();emit('route','generator-used');$g=generator();emit('yield',$g->current());

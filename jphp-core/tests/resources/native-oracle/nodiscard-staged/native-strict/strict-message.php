<?php
declare(strict_types=1);
require __DIR__.'/../native/helper.php';
#[NoDiscard(42)] function marked(){emit('body',true);return 1;}
emit('route','strict-message-discard');attempt('discard',function(){marked();});
emit('route','strict-message-reflection');attempt('reflection',fn()=>(new ReflectionFunction('marked'))->getAttributes()[0]->newInstance());
#[NoDiscard('')] function emptyMessage(){emit('empty-body',true);return 1;}emit('route','empty-message');emptyMessage();
emit('route','named-native-cuf');call_user_func(callback:'marked');
emit('route','unpacked-native-cuf');call_user_func(...['marked']);
emit('route','named-native-cufa');call_user_func_array(callback:'marked',args:[]);

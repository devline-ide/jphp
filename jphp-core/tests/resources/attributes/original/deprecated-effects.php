<?php
require __DIR__ . "/helper.php";
#[Deprecated('replacement', since:'qa')] function oldf() { emit('body','function'); return 1; }
#[Deprecated('trait')] trait T { public function m() {} }
class C { use T; #[Deprecated('method')] public function m() { emit('body','method'); } #[Deprecated('class-constant')] public const K=1; }
#[Deprecated('global-constant')] const K=2;
emit('flags', [(new ReflectionFunction('oldf'))->isDeprecated(),(new ReflectionMethod('C','m'))->isDeprecated(),(new ReflectionClassConstant('C','K'))->isDeprecated(),(new ReflectionConstant('K'))->isDeprecated()]);
oldf(); (new C)->m(); emit('constants',[C::K,C::K,K,K]);

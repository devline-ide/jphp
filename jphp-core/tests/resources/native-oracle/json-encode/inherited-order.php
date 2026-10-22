<?php
class A {protected $x=1;public $y=2;}
class B extends A {public $x=3;public $z=4;}
class C {private $x=1;public $y=2;}
class D extends C {public $x=3;public $z=4;}
class E {public $x=1;public $y=2;}
class F extends E {public $x=3;public $z=4;}
foreach([new B,new D,new F] as $o)echo get_class($o),':',json_encode($o),"
";

<?php
declare(strict_types=1);
class A { public $x { get => 7; } } class B extends A { public $x { get { return (function(){ yield parent::$x::get(); })(); } } } echo "entered\n";

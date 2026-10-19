<?php
class P{public int $x=2{get=>$this->x;}}class C extends P{function run(){return parent::$x::get();}}echo(new C)->run();

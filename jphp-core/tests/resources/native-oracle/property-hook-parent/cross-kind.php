<?php
class P{public int $x=2{get=>$this->x;set{$this->x=$value;}}}class C extends P{public int $x{get{parent::$x::set(5);return parent::$x::get();}}}echo(new C)->x;

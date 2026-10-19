<?php
#[A(static function() use ($x){return $x;})] function f() {}
echo "body-entered\n";

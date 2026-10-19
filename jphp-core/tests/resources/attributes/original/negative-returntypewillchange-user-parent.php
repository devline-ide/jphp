<?php
class P { function f():int { return 1; } } class C extends P { #[ReturnTypeWillChange] function f():string { return "x"; } }
echo "body-entered\n";

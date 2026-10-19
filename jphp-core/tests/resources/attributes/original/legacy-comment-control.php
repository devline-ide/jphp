<?php
require __DIR__ . "/helper.php";
#[Imaginary] echo "must remain part of line comment";
class C { public $value=1; }
echo "legacy-comment: ", (new C)->value, "\n";

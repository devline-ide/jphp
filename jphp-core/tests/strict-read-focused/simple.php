<?php
error_reporting(-1);$e=[];set_error_handler(function($n,$s)use(&$e){$e[]='warn';return true;});class C{const X=E_STRICT;const A=[E_STRICT];}function f($x=E_STRICT){return $x;}$e[]='before';$e[]='one:'.serialize(f());$e[]='two:'.serialize(f());echo serialize($e);

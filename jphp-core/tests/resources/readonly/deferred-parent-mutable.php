<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class P{}function create(){readonly class C extends P{}return new C;}create();

<?php
declare(strict_types=1);
$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=['notice',$n,$m];return true;});
function attempt($name,$v,$field='o') {global $events;$events=[];try{$v->$field->k=1;$r=['ok'];}catch(Throwable $e){$r=[get_class($e),$e->getMessage()];}$p=new ReflectionProperty($v,$field);return [$name,$r,$p->isInitialized($v),$events];}
class Plain {public object $o;}
class PlainNull {public ?object $o=null;}
class PlainInt {public mixed $o=1;}
class PlainArray {public mixed $o=[];}
class R {public readonly object $o;}
class RN {public readonly ?object $o;function __construct(){$this->o=null;}}
class RI {public readonly mixed $o;function __construct(){$this->o=1;}}
class RA {public readonly mixed $o;function __construct(){$this->o=[];}}
class RO {public readonly object $o;function __construct(){$this->o=(object)[];}}
class P {public private(set) object $o;}
class PN {public private(set) ?object $o=null;}
class PI {public private(set) mixed $o=1;}
class PA {public private(set) mixed $o=[];}
class PO {public private(set) object $o;function __construct(){$this->o=(object)[];}}
class PP {public protected(set) object $o;}
class HooksScalar {public int $o {get {global $events;$events[]='GET';return 1;}}}
class HooksNull {public ?object $o {get {global $events;$events[]='GET';return null;}}}
class HooksObject {public private(set) object $o {get {global $events;$events[]='GET';return $this->back;}set {global $events;$events[]='SET';}}public object $back;function __construct(){$this->back=(object)[];}}
class HooksRef {public mixed $o {&get {global $events;$events[]='REFGET';return $this->back;}}public mixed $back=null;}
class HooksError {public object $o {get {global $events;$events[]='GET';throw new Error('getter-effect');}}}
class MagicObject {public object $o;public object $back;function __construct(){$this->back=(object)[];unset($this->o);}function __get($n){global $events;$events[]='GET';return $this->back;}}
class MagicPrivateObject {public private(set) object $o;public object $back;function __construct(){$this->back=(object)[];unset($this->o);}function __get($n){global $events;$events[]='GET';return $this->back;}}
class MagicNull {public object $o;function __construct(){unset($this->o);}function __get($n){global $events;$events[]='GET';return null;}}
class MagicUntypedNull {public $o;function __construct(){unset($this->o);}function __get($n){global $events;$events[]='GET';return null;}}
$rows=[];$plain=new Plain;$rows[]=attempt('plain-uninit',$plain);$rows[]=['plain-read', (function()use($plain){try{return $plain->o;}catch(Throwable $e){return [get_class($e),$e->getMessage()];}})(),(new ReflectionProperty($plain,'o'))->isInitialized($plain)];
foreach(['plain-null'=>new PlainNull,'plain-int'=>new PlainInt,'plain-array'=>new PlainArray,'readonly-uninit'=>new R,'readonly-null'=>new RN,'readonly-int'=>new RI,'readonly-array'=>new RA,'readonly-object'=>new RO,'private-uninit'=>new P,'private-null'=>new PN,'private-int'=>new PI,'private-array'=>new PA,'private-object'=>new PO,'protected-uninit'=>new PP,'hook-int'=>new HooksScalar,'hook-null'=>new HooksNull,'hook-object'=>new HooksObject,'hook-ref-null'=>new HooksRef,'hook-error'=>new HooksError,'magic-object'=>new MagicObject,'magic-private-object'=>new MagicPrivateObject,'magic-null'=>new MagicNull,'magic-untyped-null'=>new MagicUntypedNull] as $name=>$value)$rows[]=attempt($name,$value);
$v=new Plain;$v->o=(object)[];unset($v->o);$rows[]=attempt('plain-unset',$v);
restore_error_handler();echo json_encode($rows,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);

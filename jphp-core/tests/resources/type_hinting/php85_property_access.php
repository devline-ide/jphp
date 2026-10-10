<?php
class PAV {private $p=1;protected $q=2;private static $s=3;protected static $t=4;function snapshot(){return [$this->p,$this->q,self::$s,self::$t,serialize($this)];}function own(){return [$this->p,$this->q,isset($this->p),empty($this->p)];}}
class PAForeign {static function write($o,$k){$o->$k=9;return 9;}static function compound($o,$k){return $o->$k+=9;}static function bind($o,$k){$v=9;$o->$k=&$v;return $v;}static function read($o,$k){return $o->$k;}static function remove($o,$k){unset($o->$k);return null;}}
class PAMagic {private int $p=1;public $back=4;public $log=[];function &__get($n){$this->log[]='get:'.$n;return $this->back;}function __set($n,$v){$this->log[]='set:'.$n.':'.$v;}function __isset($n){$this->log[]='isset:'.$n;return true;}function __unset($n){$this->log[]='unset:'.$n;}function snapshot(){return [$this->p,$this->back,$this->log,serialize($this)];}}
class PAGetOnly {private $p=1;public $log=[];function __get($n){$this->log[]='get:'.$n;return 4;}function snapshot(){return [$this->p,$this->log,serialize($this)];}}
class PASetOnly {private $p=1;public $log=[];function __set($n,$v){$this->log[]='set:'.$n.':'.$v;}function snapshot(){return [$this->p,$this->log,serialize($this)];}}
class PAQuietGetter {private $p=1;public $back=4;public $log=[];public $throws=false;function __get($n){$this->log[]='get:'.$n;if($this->throws)throw new Error('getter failed');return $this->back;}function snapshot(){return [$this->p,$this->back,$this->log,$GLOBALS['pa_rhs']];}}
class PAQuietIsset {private $p=1;public $flag=true;public $log=[];function __isset($n){$this->log[]='isset:'.$n;return $this->flag;}function snapshot(){return [$this->p,$this->log,$GLOBALS['pa_rhs']];}}
class PAQuietBoth {private $p=1;public $back=4;public $flag=true;public $log=[];public $throws=false;function __isset($n){$this->log[]='isset:'.$n;return $this->flag;}function __get($n){$this->log[]='get:'.$n;if($this->throws)throw new Error('getter failed');return $this->back;}function __set($n,$v){$this->log[]='set:'.$n.':'.$v;}function snapshot(){return [$this->p,$this->back,$this->log,$GLOBALS['pa_rhs']];}}
class PAQuietInitial {public int $p;public $log=[];function __get($n){$this->log[]='get:'.$n;return 4;}function snapshot(){return [$this->log,$GLOBALS['pa_rhs']];}}
function pa_rhs(){return ++$GLOBALS['pa_rhs'];}
class PAQuietRecursive {public $log=[];function __get($n){$this->log[]='get:'.$n;return $this->$n??7;}function snapshot(){return [$this->log,$GLOBALS['pa_rhs']];}}
class PAAncestor {protected $p=1;protected static $s=2;private static $secret=3;function snapshot(){return [$this->p,self::$s,self::$secret,serialize($this)];}}
class PADescendant extends PAAncestor {}
function pa_case($label,$call,$snapshot){$events=[];set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});try{try{$value=$call();$out=['ok',$value];}catch(Error $e){$out=[get_class($e),$e->getMessage()];}return [$label,$out,$snapshot(),$events];}finally{restore_error_handler();}}
$rows=[];
$o=new PAV;$rows[]=pa_case('instance-p-get',function()use($o){return $o->p;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-set',function()use($o){$o->p=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-ref',function()use($o){$r=&$o->p;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-bind',function()use($o){$v=9;$o->p=&$v;return $v;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-isset',function()use($o){return isset($o->p);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-empty',function()use($o){return empty($o->p);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-unset',function()use($o){unset($o->p);return null;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-compound',function()use($o){return $o->p+=9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-preinc',function()use($o){return ++$o->p;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-postinc',function()use($o){return $o->p++;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-predec',function()use($o){return --$o->p;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-postdec',function()use($o){return $o->p--;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-coalesce',function()use($o){return $o->p??8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-p-coalesce-set',function()use($o){return $o->p??=8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-p-write',function()use($o){return PAForeign::write($o,'p');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-p-compound',function()use($o){return PAForeign::compound($o,'p');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-p-bind',function()use($o){return PAForeign::bind($o,'p');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-p-read',function()use($o){return PAForeign::read($o,'p');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-p-remove',function()use($o){return PAForeign::remove($o,'p');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-get',function()use($o){return $o->q;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-set',function()use($o){$o->q=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-ref',function()use($o){$r=&$o->q;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-bind',function()use($o){$v=9;$o->q=&$v;return $v;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-isset',function()use($o){return isset($o->q);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-empty',function()use($o){return empty($o->q);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-unset',function()use($o){unset($o->q);return null;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-compound',function()use($o){return $o->q+=9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-preinc',function()use($o){return ++$o->q;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-postinc',function()use($o){return $o->q++;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-predec',function()use($o){return --$o->q;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-postdec',function()use($o){return $o->q--;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-coalesce',function()use($o){return $o->q??8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('instance-q-coalesce-set',function()use($o){return $o->q??=8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-q-write',function()use($o){return PAForeign::write($o,'q');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-q-compound',function()use($o){return PAForeign::compound($o,'q');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-q-bind',function()use($o){return PAForeign::bind($o,'q');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-q-read',function()use($o){return PAForeign::read($o,'q');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('foreign-q-remove',function()use($o){return PAForeign::remove($o,'q');},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-get',function(){return PAV::$s;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-set',function(){PAV::$s=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-ref',function(){$r=&PAV::$s;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-bind',function(){$v=9;PAV::$s=&$v;return $v;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-isset',function(){return isset(PAV::$s);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-empty',function(){return empty(PAV::$s);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-unset',function(){unset(PAV::$s);return null;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-compound',function(){return PAV::$s+=9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-preinc',function(){return ++PAV::$s;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-postinc',function(){return PAV::$s++;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-predec',function(){return --PAV::$s;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-postdec',function(){return PAV::$s--;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-coalesce',function(){return PAV::$s??8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-s-coalesce-set',function(){return PAV::$s??=8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-get',function(){return PAV::$t;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-set',function(){PAV::$t=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-ref',function(){$r=&PAV::$t;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-bind',function(){$v=9;PAV::$t=&$v;return $v;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-isset',function(){return isset(PAV::$t);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-empty',function(){return empty(PAV::$t);},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-unset',function(){unset(PAV::$t);return null;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-compound',function(){return PAV::$t+=9;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-preinc',function(){return ++PAV::$t;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-postinc',function(){return PAV::$t++;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-predec',function(){return --PAV::$t;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-postdec',function(){return PAV::$t--;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-coalesce',function(){return PAV::$t??8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=pa_case('static-t-coalesce-set',function(){return PAV::$t??=8;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-get',function()use($o){return $o->p;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-set',function()use($o){$o->p=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-ref',function()use($o){$r=&$o->p;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-isset',function()use($o){return isset($o->p);},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-empty',function()use($o){return empty($o->p);},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-unset',function()use($o){unset($o->p);return null;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-compound',function()use($o){return $o->p+=9;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-preinc',function()use($o){return ++$o->p;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-postdec',function()use($o){return $o->p--;},function()use($o){return $o->snapshot();});
$o=new PAMagic;$rows[]=pa_case('magic-coalesce',function()use($o){return $o->p??8;},function()use($o){return $o->snapshot();});
$o=new PAV;$rows[]=['own-scope',$o->own()];
$o=new PAGetOnly;$rows[]=pa_case('PAGetOnly-set',function()use($o){$o->p=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PAGetOnly;$rows[]=pa_case('PAGetOnly-compound',function()use($o){return $o->p+=9;},function()use($o){return $o->snapshot();});
$o=new PAGetOnly;$rows[]=pa_case('PAGetOnly-divide-zero',function()use($o){return $o->p/=0;},function()use($o){return $o->snapshot();});
$o=new PAGetOnly;$rows[]=pa_case('PAGetOnly-inc',function()use($o){return ++$o->p;},function()use($o){return $o->snapshot();});
$o=new PAGetOnly;$rows[]=pa_case('PAGetOnly-ref',function()use($o){$r=&$o->p;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PAGetOnly;$rows[]=pa_case('PAGetOnly-coalesce-set',function()use($o){return $o->p??=8;},function()use($o){return $o->snapshot();});
$o=new PASetOnly;$rows[]=pa_case('PASetOnly-set',function()use($o){$o->p=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PASetOnly;$rows[]=pa_case('PASetOnly-compound',function()use($o){return $o->p+=9;},function()use($o){return $o->snapshot();});
$o=new PASetOnly;$rows[]=pa_case('PASetOnly-divide-zero',function()use($o){return $o->p/=0;},function()use($o){return $o->snapshot();});
$o=new PASetOnly;$rows[]=pa_case('PASetOnly-inc',function()use($o){return ++$o->p;},function()use($o){return $o->snapshot();});
$o=new PASetOnly;$rows[]=pa_case('PASetOnly-ref',function()use($o){$r=&$o->p;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PASetOnly;$rows[]=pa_case('PASetOnly-coalesce-set',function()use($o){return $o->p??=8;},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietGetter;$o->back=4;$rows[]=pa_case('quiet-PAQuietGetter-non-null-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietGetter;$o->back=4;$rows[]=pa_case('quiet-PAQuietGetter-non-null-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietGetter;$o->back=null;$rows[]=pa_case('quiet-PAQuietGetter-null-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietGetter;$o->back=null;$rows[]=pa_case('quiet-PAQuietGetter-null-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietGetter;$o->throws=true;$rows[]=pa_case('quiet-PAQuietGetter-throw-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietGetter;$o->throws=true;$rows[]=pa_case('quiet-PAQuietGetter-throw-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietIsset;$o->flag=true;$rows[]=pa_case('quiet-PAQuietIsset-true-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietIsset;$o->flag=true;$rows[]=pa_case('quiet-PAQuietIsset-true-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietIsset;$o->flag=false;$rows[]=pa_case('quiet-PAQuietIsset-false-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietIsset;$o->flag=false;$rows[]=pa_case('quiet-PAQuietIsset-false-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=true;$o->back=4;$rows[]=pa_case('quiet-PAQuietBoth-true-non-null-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=true;$o->back=4;$rows[]=pa_case('quiet-PAQuietBoth-true-non-null-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=false;$o->back=4;$rows[]=pa_case('quiet-PAQuietBoth-false-non-null-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=false;$o->back=4;$rows[]=pa_case('quiet-PAQuietBoth-false-non-null-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=true;$o->back=null;$rows[]=pa_case('quiet-PAQuietBoth-true-null-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=true;$o->back=null;$rows[]=pa_case('quiet-PAQuietBoth-true-null-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=true;$o->throws=true;$rows[]=pa_case('quiet-PAQuietBoth-throw-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietBoth;$o->flag=true;$o->throws=true;$rows[]=pa_case('quiet-PAQuietBoth-throw-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietInitial;$rows[]=pa_case('quiet-PAQuietInitial-uninitialized-??',function()use($o){return $o->p ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietInitial;$rows[]=pa_case('quiet-PAQuietInitial-uninitialized-??=',function()use($o){return $o->p ??= pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietGetter;$rows[]=pa_case('getter-only-isset-empty',function()use($o){return [isset($o->p),empty($o->p)];},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietRecursive;$rows[]=pa_case('recursive-??',function()use($o){return $o->x ?? pa_rhs();},function()use($o){return $o->snapshot();});
$GLOBALS['pa_rhs']=0;$o=new PAQuietRecursive;$rows[]=pa_case('recursive-??=',function()use($o){return $o->x ??= pa_rhs();},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-instance-get',function()use($o){return $o->p;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-instance-set',function()use($o){$o->p=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-instance-ref',function()use($o){$r=&$o->p;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-instance-postinc',function()use($o){return $o->p++;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-protected-get',function()use($o){return PADescendant::$s;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-protected-set',function()use($o){PADescendant::$s=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-protected-ref',function()use($o){$r=&PADescendant::$s;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-protected-postinc',function()use($o){return PADescendant::$s++;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-protected-isset',function()use($o){return isset(PADescendant::$s);},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-protected-empty',function()use($o){return empty(PADescendant::$s);},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-private-get',function()use($o){return PADescendant::$secret;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-private-set',function()use($o){PADescendant::$secret=9;return 9;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-private-ref',function()use($o){$r=&PADescendant::$secret;$r=9;return $r;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-private-preinc',function()use($o){return ++PADescendant::$secret;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-private-isset',function()use($o){return isset(PADescendant::$secret);},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('inherited-static-private-empty',function()use($o){return empty(PADescendant::$secret);},function()use($o){return $o->snapshot();});
$o=new PAAncestor;$rows[]=pa_case('parent-static-private',function()use($o){return PAAncestor::$secret;},function()use($o){return $o->snapshot();});
$o=new PAAncestor;$rows[]=pa_case('parent-static-protected',function()use($o){return PAAncestor::$s;},function()use($o){return $o->snapshot();});
$o=new PAAncestor;$rows[]=pa_case('parent-instance-protected',function()use($o){return $o->p;},function()use($o){return $o->snapshot();});
$o=new PADescendant;$rows[]=pa_case('parent-legal-scope',function()use($o){return $o->snapshot();},function()use($o){return $o->snapshot();});
return $rows;

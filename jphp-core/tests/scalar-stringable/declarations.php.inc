error_reporting(0);
class OrdRaw {function __toString(){ord_callback_hit();return "\xff";}}
class OrdUtf {function __toString(){ord_callback_hit();return "\u{00e9}";}}
class OrdDrop {function __toString(){$GLOBALS['character']=null;ord_callback_alive();return "\xff";}}
class OrdWrong {function __toString(){ord_callback_hit();return [];}}
class OrdThrow {function __toString(){ord_callback_fail();return "A";}}
function owned_raw(){return ord(new OrdRaw());}
function owned_utf(){return ord(new OrdUtf());}
function owned_drop(){global $character;$character=new OrdDrop();return ord($character);}
function owned_wrong(){return ord(new OrdWrong());}
function owned_throw(){return ord(new OrdThrow());}
function owned_plain(){return ord("A");}
function owned_object(){return new OrdRaw();}
return 0;

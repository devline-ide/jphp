<?php
$GLOBALS['events'][]='enter';try{if(Fiber::getCurrent())Fiber::suspend('park');throw new RuntimeException('boom');}finally{$GLOBALS['events'][]='finally';}

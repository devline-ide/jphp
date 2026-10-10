<?php
class LifecycleOwner {
    public $calls=0;
    public $callback=null;
    public $failure=null;
    function __destruct() {
        $this->calls++;
        echo 'destroy:'.$this->calls."\n";
        if ($this->callback!==null) { $callback=$this->callback; $callback($this); }
        if ($this->failure!==null) throw $this->failure;
    }
}
return new LifecycleOwner;

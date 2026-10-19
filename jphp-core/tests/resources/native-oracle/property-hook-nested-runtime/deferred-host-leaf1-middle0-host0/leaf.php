<?php
declare(strict_types=1);
if(true) {trait Leaf {
 public int $p=2 {get {trigger_error("leaf-warning",E_USER_WARNING);echo "Dget;";$this->p="7";return $this->p;}set(int|string $value) {echo "Dset:$value;";$this->p=$value;}}
 public int $q=4 {&get {return $this->q;}}
}}

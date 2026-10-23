<?php
#[Deprecated('composition conflict')]
trait DepReviewConflictTrait { public function shared() { return 'marked'; } }
trait DepReviewOtherTrait { public function shared() { return 'other'; } }

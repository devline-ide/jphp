package org.develnext.jphp.core.compiler.jvm.misc;

import org.objectweb.asm.tree.LabelNode;
import org.develnext.jphp.core.tokenizer.token.stmt.BodyStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.TryStmtToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JumpItem {
    public final LabelNode breakLabel;
    public final LabelNode continueLabel;
    public final int stackSize;
    public final List<TryStmtToken> tryScopes;
    public final List<BodyStmtToken> finallyBodies;

    public JumpItem(LabelNode breakLabel, LabelNode continueLabel, int stackSize) {
        this(breakLabel, continueLabel, stackSize, Collections.emptyList(), Collections.emptyList());
    }

    public JumpItem(LabelNode breakLabel, LabelNode continueLabel, int stackSize,
                    List<TryStmtToken> tryScopes, List<BodyStmtToken> finallyBodies) {
        this.tryScopes = Collections.unmodifiableList(new ArrayList<>(tryScopes));
        this.finallyBodies = Collections.unmodifiableList(new ArrayList<>(finallyBodies));
        this.breakLabel = breakLabel;
        this.continueLabel = continueLabel;
        this.stackSize = stackSize;
    }
}

package org.jetbrains.jet.lang.psi;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.JetNodeTypes;
import org.jetbrains.jet.lexer.JetTokens;

/**
 * @author max
 */
public class JetParameter extends JetNamedDeclaration {
    public JetParameter(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public void accept(@NotNull JetVisitorVoid visitor) {
        visitor.visitParameter(this);
    }

    @Override
    public <R, D> R accept(@NotNull JetVisitor<R, D> visitor, D data) {
        return visitor.visitParameter(this, data);
    }

    @Nullable
    public JetTypeReference getTypeReference() {
        return (JetTypeReference) findChildByType(JetNodeTypes.TYPE_REFERENCE);
    }

    @Nullable
    public JetExpression getDefaultValue() {
        boolean passedEQ = false;
        ASTNode child = getNode().getFirstChildNode();
        while (child != null) {
            if (child.getElementType() == JetTokens.EQ) passedEQ = true;
            if (passedEQ && child.getPsi() instanceof JetExpression) {
                return (JetExpression) child.getPsi();
            }
            child = child.getTreeNext();
        }

        return null;
    }

    public boolean isMutable() {
        return findChildByType(JetTokens.VAR_KEYWORD) != null;
    }

    public boolean isVarArg() {
        JetModifierList modifierList = getModifierList();
        return modifierList != null && modifierList.getModifierNode(JetTokens.VARARG_KEYWORD) != null;
    }

    @Nullable
    public ASTNode getValOrVarNode() {
        ASTNode val = getNode().findChildByType(JetTokens.VAL_KEYWORD);
        if (val != null) return val;

        return getNode().findChildByType(JetTokens.VAR_KEYWORD);
    }


}

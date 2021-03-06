package org.jetbrains.jet.lang.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.JetNodeTypes;
import org.jetbrains.jet.lexer.JetTokens;

import java.util.List;

/**
 * @author abreslav
 */
public class JetWhenExpression extends JetExpression {
    public JetWhenExpression(@NotNull ASTNode node) {
        super(node);
    }

    @NotNull
    public List<JetWhenEntry> getEntries() {
        return findChildrenByType(JetNodeTypes.WHEN_ENTRY);
    }

    @Nullable
    public JetExpression getSubjectExpression() {
        return findChildByClass(JetExpression.class);
    }

    @Override
    public void accept(@NotNull JetVisitorVoid visitor) {
        visitor.visitWhenExpression(this);
    }

    @Override
    public <R, D> R accept(@NotNull JetVisitor<R, D> visitor, D data) {
        return visitor.visitWhenExpression(this, data);
    }

    @NotNull
    public PsiElement getWhenKeywordElement() {
        return findChildByType(JetTokens.WHEN_KEYWORD);
    }
}

package org.jetbrains.jet.codegen;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

/**
* @author alex.tkachman
*/
public class CompilationException extends RuntimeException {
    private PsiElement element;

    CompilationException(String message, Throwable cause, PsiElement element) {
        super(message, cause);
        this.element = element;
    }

    public PsiElement getElement() {
        return element;
    }

    @Override
    public String toString() {
        PsiFile psiFile = element.getContainingFile();
        TextRange textRange = element.getTextRange();
        Document document = psiFile.getViewProvider().getDocument();
        int line;
        int col;
        if (document != null) {
            line = document.getLineNumber(textRange.getStartOffset());
            col = textRange.getStartOffset() - document.getLineStartOffset(line) + 1;
        }
        else {
            line = -1;
            col = -1;
        }

        String s2 = getCause().getMessage() != null ? getCause().getMessage() : getCause().toString();
        return "Internal error: (" + (line+1) + "," + col + ") " + s2 + "\n@" + where();
    }
    
    private String where() {
        if (getCause().getStackTrace().length > 0) {
            return getCause().getStackTrace()[0].getFileName() + ":" + getCause().getStackTrace()[0].getLineNumber();
        } else {
            return "far away in cyberspace";
        }
    }

    @Override
    public String getMessage() {
        return this.toString();
    }
}

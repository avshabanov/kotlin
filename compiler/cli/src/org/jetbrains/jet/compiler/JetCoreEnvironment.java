package org.jetbrains.jet.compiler;

import com.intellij.core.JavaCoreEnvironment;
import com.intellij.lang.java.JavaParserDefinition;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.jet.lang.parsing.JetParserDefinition;
import org.jetbrains.jet.plugin.JetFileType;
import org.jetbrains.jet.plugin.compiler.PathUtil;

/**
 * @author yole
 */
public class JetCoreEnvironment extends JavaCoreEnvironment {
    public JetCoreEnvironment(Disposable parentDisposable) {
        super(parentDisposable);
        registerFileType(JetFileType.INSTANCE, "kt");
        registerFileType(JetFileType.INSTANCE, "kts");
        registerFileType(JetFileType.INSTANCE, "ktm");
        registerFileType(JetFileType.INSTANCE, "jet");
        registerParserDefinition(new JavaParserDefinition());
        registerParserDefinition(new JetParserDefinition());

/*
        Extensions.getArea(myProject)
                .getExtensionPoint(PsiElementFinder.EP_NAME)
                .registerExtension(new JavaElementFinder(myProject));
*/

        for (VirtualFile root : PathUtil.getAltHeadersRoots()) {
            addLibraryRoot(root);
        }
    }

    public MockApplication getApplication() {
        return myApplication;
    }
}

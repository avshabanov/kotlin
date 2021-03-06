package org.jetbrains.jet.lang.descriptors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.types.TypeSubstitutor;

import java.util.Collections;

/**
 * @author abreslav
 */
public class ModuleDescriptor extends DeclarationDescriptorImpl {
    public ModuleDescriptor(String name) {
        super(null, Collections.<AnnotationDescriptor>emptyList(), name);
    }

    @NotNull
    @Override
    public ModuleDescriptor substitute(TypeSubstitutor substitutor) {
        return this;
    }

    @Override
    public <R, D> R accept(DeclarationDescriptorVisitor<R, D> visitor, D data) {
        return visitor.visitModuleDeclaration(this, data);
    }
}

package org.jetbrains.jet.lang.descriptors;

import org.jetbrains.annotations.NotNull;

/**
 * @author abreslav
 */
public interface MemberDescriptor extends DeclarationDescriptor, DeclarationDescriptorWithVisibility {
    @NotNull
    Modality getModality();

    @Override
    @NotNull
    Visibility getVisibility();
}

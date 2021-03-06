package org.jetbrains.jet.rt;

import jet.TypeInfo;
import jet.typeinfo.TypeInfoProjection;
import jet.typeinfo.TypeInfoVariance;

/**
 * @author abreslav
 * @author yole
 * @author alex.tkachman
 * @author Stepan Koltsov
 */
public abstract class TypeInfoProjectionImpl implements TypeInfoProjection {
    private final TypeInfo type;

    public TypeInfoProjectionImpl(TypeInfo typeInfo) {
        this.type = typeInfo;
    }

    @Override
    public final TypeInfo getType() {
        return type;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TypeInfoProjectionImpl that = (TypeInfoProjectionImpl) o;
        // no need to compare variance as we compared classes already
        return type.equals(that.type);
    }

    @Override
    public final int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (getVariance().hashCode());
        return result;
    }

    @Override
    public final String toString() {
        return (getVariance() == TypeInfoVariance.INVARIANT ? "" : getVariance().toString() + " ") + type;
    }
}

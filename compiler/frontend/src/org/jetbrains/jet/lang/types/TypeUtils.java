package org.jetbrains.jet.lang.types;

import com.google.common.collect.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.TypeParameterDescriptor;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.resolve.scopes.ChainedScope;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.types.checker.JetTypeChecker;
import org.jetbrains.jet.util.CommonSuppliers;

import java.util.*;

/**
 * @author abreslav
 */
public class TypeUtils {
    public static final JetType FORBIDDEN = new JetType() {
        @NotNull
        @Override
        public TypeConstructor getConstructor() {
            throw new UnsupportedOperationException(); // TODO
        }

        @NotNull
        @Override
        public List<TypeProjection> getArguments() {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public boolean isNullable() {
            throw new UnsupportedOperationException(); // TODO
        }

        @NotNull
        @Override
        public JetScope getMemberScope() {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public List<AnnotationDescriptor> getAnnotations() {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public String toString() {
            return "FORBIDDEN";
        }
    };
    public static final JetType NO_EXPECTED_TYPE = new JetType() {
        @NotNull
        @Override
        public TypeConstructor getConstructor() {
            throw new UnsupportedOperationException(); // TODO
        }

        @NotNull
        @Override
        public List<TypeProjection> getArguments() {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public boolean isNullable() {
            throw new UnsupportedOperationException(); // TODO
        }

        @NotNull
        @Override
        public JetScope getMemberScope() {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public List<AnnotationDescriptor> getAnnotations() {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public String toString() {
            return "NO_EXPECTED_TYPE";
        }
    };

    public static JetType makeNullable(@NotNull JetType type) {
        return makeNullableAsSpecified(type, true);
    }

    public static JetType makeNotNullable(@NotNull JetType type) {
        return makeNullableAsSpecified(type, false);
    }

    @NotNull
    public static JetType makeNullableAsSpecified(@NotNull JetType type, boolean nullable) {
        if (type.isNullable() == nullable) {
            return type;
        }
        if (ErrorUtils.isErrorType(type)) {
            return type;
        }
        return new JetTypeImpl(type.getAnnotations(), type.getConstructor(), nullable, type.getArguments(), type.getMemberScope());
    }

    @NotNull
    public static JetType safeIntersect(JetTypeChecker typeChecker, Set<JetType> types) {
        JetType intersection = intersect(typeChecker, types);
        if (intersection == null) return ErrorUtils.createErrorType("No intersection for " + types); // TODO : message
        return intersection;
    }

    @Nullable
    public static JetType intersect(@NotNull JetTypeChecker typeChecker, @NotNull Set<JetType> types) {
        assert !types.isEmpty();

        if (types.size() == 1) {
            return types.iterator().next();
        }

        // Intersection of T1..Tn is an intersection of their non-null versions,
        //   made nullable is they all were nullable
        boolean allNullable = true;
        boolean nothingTypePresent = false;
        List<JetType> nullabilityStripped = Lists.newArrayList();
        for (JetType type : types) {
            nothingTypePresent |= JetStandardClasses.isNothingOrNullableNothing(type);
            allNullable &= type.isNullable();
            nullabilityStripped.add(makeNotNullable(type));
        }
        
        if (nothingTypePresent) {
            return allNullable ? JetStandardClasses.getNullableNothingType() : JetStandardClasses.getNothingType();
        }

        // Now we remove types that have subtypes in the list
        List<JetType> resultingTypes = Lists.newArrayList();
        outer:
        for (JetType type : nullabilityStripped) {
            if (!canHaveSubtypes(typeChecker, type)) {
                for (JetType other : nullabilityStripped) {
                    // It makes sense to check for subtyping of other <: type, despite that
                    // type is not supposed to be open, for there're enums
                    if (!type.equals(other) && !typeChecker.isSubtypeOf(type, other) && !typeChecker.isSubtypeOf(other, type)) {
                        return null;
                    }
                }
                return makeNullableAsSpecified(type, allNullable);
            }
            else {
                for (JetType other : nullabilityStripped) {
                    if (!type.equals(other) && typeChecker.isSubtypeOf(other, type)) {
                        continue outer;
                    }

                }
            }

            resultingTypes.add(type);
        }
        
        if (resultingTypes.size() == 1) {
            return makeNullableAsSpecified(resultingTypes.get(0), allNullable);
        }


        List<AnnotationDescriptor> noAnnotations = Collections.<AnnotationDescriptor>emptyList();
        TypeConstructor constructor = new TypeConstructorImpl(
                null,
                noAnnotations,
                false,
                makeDebugNameForIntersectionType(resultingTypes).toString(),
                Collections.<TypeParameterDescriptor>emptyList(),
                resultingTypes);

        JetScope[] scopes = new JetScope[resultingTypes.size()];
        int i = 0;
        for (JetType type : resultingTypes) {
            scopes[i] = type.getMemberScope();
            i++;
        }

        return new JetTypeImpl(
                noAnnotations,
                constructor,
                allNullable,
                Collections.<TypeProjection>emptyList(),
                new ChainedScope(null, scopes)); // TODO : check intersectibility, don't use a chanied scope
    }

    private static StringBuilder makeDebugNameForIntersectionType(Iterable<JetType> resultingTypes) {
        StringBuilder debugName = new StringBuilder("{");
        for (Iterator<JetType> iterator = resultingTypes.iterator(); iterator.hasNext(); ) {
            JetType type = iterator.next();

            debugName.append(type.toString());
            if (iterator.hasNext()) {
                debugName.append(" & ");
            }
        }
        debugName.append("}");
        return debugName;
    }

    public static boolean canHaveSubtypes(JetTypeChecker typeChecker, JetType type) {
        if (type.isNullable()) {
            return true;
        }
        if (!type.getConstructor().isSealed()) {
            return true;
        }

        List<TypeParameterDescriptor> parameters = type.getConstructor().getParameters();
        List<TypeProjection> arguments = type.getArguments();
        for (int i = 0, parametersSize = parameters.size(); i < parametersSize; i++) {
            TypeParameterDescriptor parameterDescriptor = parameters.get(i);
            TypeProjection typeProjection = arguments.get(i);
            Variance projectionKind = typeProjection.getProjectionKind();
            JetType argument = typeProjection.getType();

            switch (parameterDescriptor.getVariance()) {
                case INVARIANT:
                    switch (projectionKind) {
                        case INVARIANT:
                            if (lowerThanBound(typeChecker, argument, parameterDescriptor) || canHaveSubtypes(typeChecker, argument)) {
                                return true;
                            }
                            break;
                        case IN_VARIANCE:
                            if (lowerThanBound(typeChecker, argument, parameterDescriptor)) {
                                return true;
                            }
                            break;
                        case OUT_VARIANCE:
                            if (canHaveSubtypes(typeChecker, argument)) {
                                return true;
                            }
                            break;
                    }
                    break;
                case IN_VARIANCE:
                    if (projectionKind != Variance.OUT_VARIANCE) {
                        if (lowerThanBound(typeChecker, argument, parameterDescriptor)) {
                            return true;
                        }
                    } else {
                        if (canHaveSubtypes(typeChecker, argument)) {
                            return true;
                        }
                    }
                    break;
                case OUT_VARIANCE:
                    if (projectionKind != Variance.IN_VARIANCE) {
                        if (canHaveSubtypes(typeChecker, argument)) {
                            return true;
                        }
                    } else {
                        if (lowerThanBound(typeChecker, argument, parameterDescriptor)) {
                            return true;
                        }
                    }
                    break;
            }
        }
        return false;
    }

    private static boolean lowerThanBound(JetTypeChecker typeChecker, JetType argument, TypeParameterDescriptor parameterDescriptor) {
        for (JetType bound : parameterDescriptor.getUpperBounds()) {
            if (typeChecker.isSubtypeOf(argument, bound)) {
                if (!argument.getConstructor().equals(bound.getConstructor())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static JetType makeNullableIfNeeded(JetType type, boolean nullable) {
        if (nullable) {
            return makeNullable(type);
        }
        return type;
    }

    @NotNull
    public static JetType makeUnsubstitutedType(ClassDescriptor classDescriptor, JetScope unsubstitutedMemberScope) {
        if (ErrorUtils.isError(classDescriptor)) {
            return ErrorUtils.createErrorType("This is very helpful diagnostics message");
        }
        List<TypeProjection> arguments = getDefaultTypeProjections(classDescriptor.getTypeConstructor().getParameters());
        return new JetTypeImpl(
                Collections.<AnnotationDescriptor>emptyList(),
                classDescriptor.getTypeConstructor(),
                false,
                arguments,
                unsubstitutedMemberScope
        );
    }

    @NotNull
    public static List<TypeProjection> getDefaultTypeProjections(List<TypeParameterDescriptor> parameters) {
        List<TypeProjection> result = new ArrayList<TypeProjection>();
        for (TypeParameterDescriptor parameterDescriptor : parameters) {
            result.add(new TypeProjection(parameterDescriptor.getDefaultType()));
        }
        return result;
    }

    @NotNull
    public static List<JetType> getDefaultTypes(List<TypeParameterDescriptor> parameters) {
        List<JetType> result = Lists.newArrayList();
        for (TypeParameterDescriptor parameterDescriptor : parameters) {
            result.add(parameterDescriptor.getDefaultType());
        }
        return result;
    }

    @NotNull
    public static Map<TypeConstructor, TypeProjection> buildSubstitutionContext(@NotNull  JetType context) {
        return buildSubstitutionContext(context.getConstructor().getParameters(), context.getArguments());
    }

    /**
     * Builds a context with all the supertypes' parameters substituted
     */
    @NotNull
    public static TypeSubstitutor buildDeepSubstitutor(@NotNull JetType type) {
        Map<TypeConstructor, TypeProjection> substitution = Maps.newHashMap();
        TypeSubstitutor typeSubstitutor = TypeSubstitutor.create(substitution);
        // we use the mutability of the map here
        fillInDeepSubstitutor(type, typeSubstitutor, substitution, null);
        return typeSubstitutor;
    }

    @NotNull
    public static Multimap<TypeConstructor, TypeProjection> buildDeepSubstitutionMultimap(@NotNull JetType type) {
        Multimap<TypeConstructor, TypeProjection> fullSubstitution = CommonSuppliers.newLinkedHashSetHashSetMultimap();
        Map<TypeConstructor, TypeProjection> substitution = Maps.newHashMap();
        TypeSubstitutor typeSubstitutor = TypeSubstitutor.create(substitution);
        // we use the mutability of the map here
        fillInDeepSubstitutor(type, typeSubstitutor, substitution, fullSubstitution);
        return fullSubstitution;
    }

    // we use the mutability of the substitution map here
    private static void fillInDeepSubstitutor(@NotNull JetType context, @NotNull TypeSubstitutor substitutor, @NotNull Map<TypeConstructor, TypeProjection> substitution, @Nullable Multimap<TypeConstructor, TypeProjection> fullSubstitution) {
        List<TypeParameterDescriptor> parameters = context.getConstructor().getParameters();
        List<TypeProjection> arguments = context.getArguments();
        
        if (parameters.size() != arguments.size()) {
            throw new IllegalStateException();
        }
        
        for (int i = 0; i < arguments.size(); i++) {
            TypeProjection argument = arguments.get(i);
            TypeParameterDescriptor typeParameterDescriptor = parameters.get(i);

            JetType substitute = substitutor.substitute(argument.getType(), Variance.INVARIANT);
            assert substitute != null;
            TypeProjection substitutedTypeProjection = new TypeProjection(argument.getProjectionKind(), substitute);
            substitution.put(typeParameterDescriptor.getTypeConstructor(), substitutedTypeProjection);
            if (fullSubstitution != null) {
                fullSubstitution.put(typeParameterDescriptor.getTypeConstructor(), substitutedTypeProjection);
            }
        }
        if (JetStandardClasses.isNothingOrNullableNothing(context)) return;
        for (JetType supertype : context.getConstructor().getSupertypes()) {
            fillInDeepSubstitutor(supertype, substitutor, substitution, fullSubstitution);
        }
    }

    @NotNull
    public static Map<TypeConstructor, TypeProjection> buildSubstitutionContext(@NotNull List<TypeParameterDescriptor> parameters, @NotNull List<TypeProjection> contextArguments) {
        Map<TypeConstructor, TypeProjection> parameterValues = new HashMap<TypeConstructor, TypeProjection>();
        fillInSubstitutionContext(parameters, contextArguments, parameterValues);
        return parameterValues;
    }

    private static void fillInSubstitutionContext(List<TypeParameterDescriptor> parameters, List<TypeProjection> contextArguments, Map<TypeConstructor, TypeProjection> parameterValues) {
        for (int i = 0, parametersSize = parameters.size(); i < parametersSize; i++) {
            TypeParameterDescriptor parameter = parameters.get(i);
            TypeProjection value = contextArguments.get(i);
            parameterValues.put(parameter.getTypeConstructor(), value);
        }
    }

    @NotNull
    public static TypeProjection makeStarProjection(@NotNull TypeParameterDescriptor parameterDescriptor) {
        return new TypeProjection(Variance.OUT_VARIANCE, parameterDescriptor.getUpperBoundsAsType());
    }

    private static void collectImmediateSupertypes(@NotNull JetType type, @NotNull Collection<JetType> result) {
        TypeSubstitutor substitutor = TypeSubstitutor.create(type);
        for (JetType supertype : type.getConstructor().getSupertypes()) {
            result.add(substitutor.substitute(supertype, Variance.INVARIANT));
        }
    }

    @NotNull
    public static List<JetType> getImmediateSupertypes(@NotNull JetType type) {
        List<JetType> result = Lists.newArrayList();
        collectImmediateSupertypes(type, result);
        return result;
    }

    private static void collectAllSupertypes(@NotNull JetType type, @NotNull Set<JetType> result) {
        List<JetType> immediateSupertypes = getImmediateSupertypes(type);
        result.addAll(immediateSupertypes);
        for (JetType supertype : immediateSupertypes) {
            collectAllSupertypes(supertype, result);
        }
    }


    @NotNull
    public static Set<JetType> getAllSupertypes(@NotNull JetType type) {
        Set<JetType> result = Sets.newLinkedHashSet();
        collectAllSupertypes(type, result);
        return result;
    }

    public static boolean hasNullableLowerBound(@NotNull TypeParameterDescriptor typeParameterDescriptor) {
        for (JetType bound : typeParameterDescriptor.getLowerBounds()) {
            if (bound.isNullable()) {
                return true;
            }
        }
        return false;
    }

    public static boolean equalClasses(@NotNull JetType type1, @NotNull JetType type2) {
        DeclarationDescriptor declarationDescriptor1 = type1.getConstructor().getDeclarationDescriptor();
        if (declarationDescriptor1 == null) return false; // No class, classes are not equal
        DeclarationDescriptor declarationDescriptor2 = type2.getConstructor().getDeclarationDescriptor();
        if (declarationDescriptor2 == null) return false; // Class of type1 is not null
        return declarationDescriptor1.getOriginal().equals(declarationDescriptor2.getOriginal());
    }

    @Nullable
    public static ClassDescriptor getClassDescriptor(@NotNull JetType type) {
        DeclarationDescriptor declarationDescriptor = type.getConstructor().getDeclarationDescriptor();
        if (declarationDescriptor instanceof ClassDescriptor) {
            return (ClassDescriptor) declarationDescriptor;
        }
        return null;
    }

    public static boolean hasUnsubstitutedTypeParameters(JetType type) {
        if(type.getConstructor().getDeclarationDescriptor() instanceof TypeParameterDescriptor)
            return true;

        for(TypeProjection proj : type.getArguments()) {
            if(hasUnsubstitutedTypeParameters(proj.getType())) {
                return true;
            }
        }

        return false;
    }

    public static boolean equalTypes(@NotNull JetType a, @NotNull JetType b) {
        return JetTypeChecker.INSTANCE.isSubtypeOf(a, b) && JetTypeChecker.INSTANCE.isSubtypeOf(b, a);
    }
}

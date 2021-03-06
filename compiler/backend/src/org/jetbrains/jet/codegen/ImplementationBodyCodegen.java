package org.jetbrains.jet.codegen;

import com.google.common.collect.Lists;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.OverridingUtil;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.java.JvmAbi;
import org.jetbrains.jet.lang.resolve.java.JvmStdlibNames;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.TypeProjection;
import org.jetbrains.jet.lexer.JetTokens;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.commons.Method;

import java.util.*;

import static org.jetbrains.jet.codegen.JetTypeMapper.TYPE_OBJECT;
import static org.objectweb.asm.Opcodes.*;

/**
 * @author max
 * @author yole
 * @author alex.tkachman
 */
public class ImplementationBodyCodegen extends ClassBodyCodegen {
    private JetDelegationSpecifier superCall;
    private String superClass;
    @Nullable // null means java/lang/Object
    private JetType superClassType;
    private final JetTypeMapper typeMapper;
    private final BindingContext bindingContext;

    public ImplementationBodyCodegen(JetClassOrObject aClass, CodegenContext context, ClassBuilder v, GenerationState state) {
        super(aClass, context, v, state);
        typeMapper = state.getTypeMapper();
        bindingContext = state.getBindingContext();
    }

    @Override
    protected void generateDeclaration() {
        getSuperClass();

        JvmClassSignature signature = signature();

        boolean isAbstract = false;
        boolean isInterface = false;
        boolean isFinal = false;
        boolean isStatic = false;
        
        if(myClass instanceof JetClass) {
            JetClass jetClass = (JetClass) myClass;
            if (jetClass.hasModifier(JetTokens.ABSTRACT_KEYWORD))
               isAbstract = true;
            if (jetClass.isTrait()) {
                isAbstract = true;
                isInterface = true;
            }
            if (!jetClass.hasModifier(JetTokens.OPEN_KEYWORD) && !isAbstract) {
                isFinal = true;
            }
        }
        else if (myClass.getParent() instanceof JetClassObject) {
            isStatic = true;
        }

        int access = 0;
        access |= ACC_PUBLIC;
        if (isAbstract) {
            access |= ACC_ABSTRACT;
        }
        if (isInterface) {
            access |= ACC_INTERFACE; // ACC_SUPER
        }
        if (isFinal) {
            access |= ACC_FINAL;
        }
        if (isStatic) {
            access |= ACC_STATIC;
        }
        v.defineClass(myClass, V1_6,
                access,
                      signature.getName(),
                      signature.getJavaGenericSignature(),
                      signature.getSuperclassName(),
                      signature.getInterfaces().toArray(new String[0])
        );
        v.visitSource(state.transformFileName(myClass.getContainingFile().getName()), null);

        ClassDescriptor container = getContainingClassDescriptor(descriptor);
        if(container != null) {
            v.visitOuterClass(typeMapper.mapType(container.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName(), null, null);
        }

        for (ClassDescriptor innerClass : descriptor.getInnerClassesAndObjects()) {
            // TODO: proper access
            int innerClassAccess = ACC_PUBLIC;
            if (innerClass.getModality() == Modality.FINAL) {
                innerClassAccess |= ACC_FINAL;
            } else if (innerClass.getModality() == Modality.ABSTRACT) {
                innerClassAccess |= ACC_ABSTRACT;
            }

            if (innerClass.getKind() == ClassKind.TRAIT) {
                innerClassAccess |= ACC_INTERFACE;
            }

            // TODO: cache internal names
            String outerClassInernalName = typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName();
            String innerClassInternalName = typeMapper.mapType(innerClass.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName();
            v.visitInnerClass(innerClassInternalName, outerClassInernalName, innerClass.getName(), innerClassAccess);
        }

        AnnotationCodegen.forClass(v.getVisitor()).genAnnotations(descriptor, typeMapper);

        if(myClass instanceof JetClass && signature.getKotlinGenericSignature() != null) {
            AnnotationVisitor annotationVisitor = v.newAnnotation(myClass, JvmStdlibNames.JET_CLASS.getDescriptor(), true);
            annotationVisitor.visit(JvmStdlibNames.JET_CLASS_SIGNATURE, signature.getKotlinGenericSignature());
            annotationVisitor.visitEnd();
        }
    }

    private static ClassDescriptor getContainingClassDescriptor(ClassDescriptor decl) {
        DeclarationDescriptor container = decl.getContainingDeclaration();
        while (container != null && !(container instanceof NamespaceDescriptor)) {
            if (container instanceof ClassDescriptor) return (ClassDescriptor) container;
            container = container.getContainingDeclaration();
        }
        return null;
    }

    private JvmClassSignature signature() {
        List<String> superInterfaces;

        LinkedHashSet<String> superInterfacesLinkedHashSet = new LinkedHashSet<String>();

        // TODO: generics signature is not always needed
        BothSignatureWriter signatureVisitor = new BothSignatureWriter(BothSignatureWriter.Mode.CLASS, true);


        {   // type parameters
            List<TypeParameterDescriptor> typeParameters = descriptor.getTypeConstructor().getParameters();
            typeMapper.writeFormalTypeParameters(typeParameters, signatureVisitor);
        }
        
        signatureVisitor.writeSupersStart();

        {   // superclass
            signatureVisitor.writeSuperclass();
            if (superClassType == null) {
                signatureVisitor.writeClassBegin(superClass, false, false);
                signatureVisitor.writeClassEnd();
            } else {
                typeMapper.mapType(superClassType, OwnerKind.IMPLEMENTATION, signatureVisitor, true);
            }
            signatureVisitor.writeSuperclassEnd();
        }


        {   // superinterfaces
            superInterfacesLinkedHashSet.add(JvmStdlibNames.JET_OBJECT.getInternalName());

            for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
                JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
                assert superType != null;
                ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
                if (CodegenUtil.isInterface(superClassDescriptor)) {
                    signatureVisitor.writeInterface();
                    Type jvmName = typeMapper.mapType(superType, OwnerKind.IMPLEMENTATION, signatureVisitor, true);
                    signatureVisitor.writeInterfaceEnd();
                    superInterfacesLinkedHashSet.add(jvmName.getInternalName());
                }
            }

            superInterfaces = new ArrayList<String>(superInterfacesLinkedHashSet);
        }
        
        signatureVisitor.writeSupersEnd();

        return new JvmClassSignature(jvmName(), superClass, superInterfaces, signatureVisitor.makeJavaString(), signatureVisitor.makeKotlinClassSignature());
    }

    private String jvmName() {
        return typeMapper.mapType(descriptor.getDefaultType(), kind).getInternalName();
    }

    protected void getSuperClass() {
        superClass = "java/lang/Object";
        superClassType = null;

        List<JetDelegationSpecifier> delegationSpecifiers = myClass.getDelegationSpecifiers();

        if(myClass instanceof JetClass && ((JetClass) myClass).isTrait())
            return;

        for (JetDelegationSpecifier specifier : delegationSpecifiers) {
            if (specifier instanceof JetDelegatorToSuperClass || specifier instanceof JetDelegatorToSuperCall) {
                JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
                assert superType != null;
                ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
                if(!CodegenUtil.isInterface(superClassDescriptor)) {
                    superClassType = superType;
                    superClass = typeMapper.mapType(superClassDescriptor.getDefaultType(), kind).getInternalName();
                    superCall = specifier;
                }
            }
        }
    }

    @Override
    protected void generateSyntheticParts() {
        generateGetTypeInfo();

        generateFieldForObjectInstance();
        generateFieldForClassObject();

        try {
            generatePrimaryConstructor();
        }
        catch (CompilationException e) {
            throw e;
        }
        catch(RuntimeException e) {
            throw new RuntimeException("Error generating primary constructor of class " + myClass.getName() + " with kind " + kind, e);
        }

        generateAccessors();
    }

    private void generateAccessors() {
        if(context.accessors != null) {
            for (Map.Entry<DeclarationDescriptor, DeclarationDescriptor> entry : context.accessors.entrySet()) {
                genAccessor(entry);
            }
        }
    }

    private void genAccessor(Map.Entry<DeclarationDescriptor, DeclarationDescriptor> entry) {
        if(entry.getValue() instanceof FunctionDescriptor) {
            FunctionDescriptor bridge = (FunctionDescriptor) entry.getValue();
            FunctionDescriptor original = (FunctionDescriptor) entry.getKey();

            Method method = typeMapper.mapSignature(bridge.getName(), bridge).getAsmMethod();
            Method originalMethod = typeMapper.mapSignature(original.getName(), original).getAsmMethod();
            Type[] argTypes = method.getArgumentTypes();

            MethodVisitor mv = v.newMethod(null, ACC_PUBLIC| ACC_BRIDGE| ACC_FINAL, bridge.getName(), method.getDescriptor(), null, null);
            if (v.generateCode()) {
                mv.visitCode();

                InstructionAdapter iv = new InstructionAdapter(mv);

                iv.load(0, JetTypeMapper.TYPE_OBJECT);
                for (int i = 0, reg = 1; i < argTypes.length; i++) {
                    Type argType = argTypes[i];
                    iv.load(reg, argType);
                    //noinspection AssignmentToForLoopParameter
                    reg += argType.getSize();
                }
                iv.invokespecial(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION), originalMethod.getName(), originalMethod.getDescriptor());

                iv.areturn(method.getReturnType());
                FunctionCodegen.endVisit(iv, "accessor", null);
            }
        }
        else if(entry.getValue() instanceof PropertyDescriptor) {
            PropertyDescriptor bridge = (PropertyDescriptor) entry.getValue();
            PropertyDescriptor original = (PropertyDescriptor) entry.getKey();

            {
                Method method = typeMapper.mapGetterSignature(bridge, OwnerKind.IMPLEMENTATION).getJvmMethodSignature().getAsmMethod();
                JvmPropertyAccessorSignature originalSignature = typeMapper.mapGetterSignature(original, OwnerKind.IMPLEMENTATION);
                Method originalMethod = originalSignature.getJvmMethodSignature().getAsmMethod();
                MethodVisitor mv = v.newMethod(null, ACC_PUBLIC | ACC_BRIDGE | ACC_FINAL, method.getName(), method.getDescriptor(), null, null);
                PropertyCodegen.generateJetPropertyAnnotation(mv, originalSignature.getPropertyTypeKotlinSignature(), originalSignature.getJvmMethodSignature().getKotlinTypeParameter());
                if (v.generateCode()) {
                    mv.visitCode();

                    InstructionAdapter iv = new InstructionAdapter(mv);

                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    if(original.getVisibility() == Visibility.PRIVATE)
                        iv.getfield(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION), original.getName(), originalMethod.getReturnType().getDescriptor());
                    else
                        iv.invokespecial(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION), originalMethod.getName(), originalMethod.getDescriptor());

                    iv.areturn(method.getReturnType());
                    FunctionCodegen.endVisit(iv, "accessor", null);
                }
            }

            if(bridge.isVar())
            {
                Method method = typeMapper.mapSetterSignature(bridge, OwnerKind.IMPLEMENTATION).getJvmMethodSignature().getAsmMethod();
                JvmPropertyAccessorSignature originalSignature2 = typeMapper.mapSetterSignature(original, OwnerKind.IMPLEMENTATION);
                Method originalMethod = originalSignature2.getJvmMethodSignature().getAsmMethod();
                MethodVisitor mv = v.newMethod(null, ACC_PUBLIC | ACC_BRIDGE | ACC_FINAL, method.getName(), method.getDescriptor(), null, null);
                PropertyCodegen.generateJetPropertyAnnotation(mv, originalSignature2.getPropertyTypeKotlinSignature(), originalSignature2.getJvmMethodSignature().getKotlinTypeParameter());
                if (v.generateCode()) {
                    mv.visitCode();

                    InstructionAdapter iv = new InstructionAdapter(mv);

                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    Type[] argTypes = method.getArgumentTypes();
                    for (int i = 0, reg = 1; i < argTypes.length; i++) {
                        Type argType = argTypes[i];
                        iv.load(reg, argType);
                        //noinspection AssignmentToForLoopParameter
                        reg += argType.getSize();
                    }
                    if(original.getVisibility() == Visibility.PRIVATE && original.getModality() == Modality.FINAL)
                        iv.putfield(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION), original.getName(), originalMethod.getArgumentTypes()[0].getDescriptor());
                    else
                        iv.invokespecial(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION), originalMethod.getName(), originalMethod.getDescriptor());

                    iv.areturn(method.getReturnType());
                    FunctionCodegen.endVisit(iv, "accessor", null);
                }
            }
        }
        else {
            throw new UnsupportedOperationException();
        }
    }

    private void generateFieldForObjectInstance() {
        if (CodegenUtil.isNonLiteralObject(myClass)) {
            Type type = typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION);
            v.newField(myClass, ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "$instance", type.getDescriptor(), null, null);

            staticInitializerChunks.add(new CodeChunk() {
                @Override
                public void generate(InstructionAdapter v) {
                    String name = jvmName();
                    v.anew(Type.getObjectType(name));
                    v.dup();
                    v.invokespecial(name, "<init>", "()V");
                    v.putstatic(name, "$instance", typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getDescriptor());
                }
            });

        }
    }

    private void generateFieldForClassObject() {
        final JetClassObject classObject = getClassObject();
        if (classObject != null) {
            final ClassDescriptor descriptor1 = bindingContext.get(BindingContext.CLASS, classObject.getObjectDeclaration());
            Type type = Type.getObjectType(typeMapper.mapType(descriptor1.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName());
            v.newField(classObject, ACC_PUBLIC | ACC_STATIC, "$classobj", type.getDescriptor(), null, null);

            staticInitializerChunks.add(new CodeChunk() {
                @Override
                public void generate(InstructionAdapter v) {
                    final ClassDescriptor descriptor1 = bindingContext.get(BindingContext.CLASS, classObject.getObjectDeclaration());
                    String name = typeMapper.mapType(descriptor1.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName();
                    final Type classObjectType = Type.getObjectType(name);
                    v.anew(classObjectType);
                    v.dup();
                    v.invokespecial(name, "<init>", "()V");
                    v.putstatic(typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName(), "$classobj",
                                classObjectType.getDescriptor());
                }
            });
        }
    }

    protected void generatePrimaryConstructor() {
        if(myClass instanceof JetClass && ((JetClass) myClass).isTrait())
            return;

        ConstructorDescriptor constructorDescriptor = bindingContext.get(BindingContext.CONSTRUCTOR, myClass);

        CodegenContext.ConstructorContext constructorContext = context.intoConstructor(constructorDescriptor, typeMapper);

        JvmMethodSignature constructorMethod;
        CallableMethod callableMethod;
        if (constructorDescriptor == null) {
            BothSignatureWriter signatureWriter = new BothSignatureWriter(BothSignatureWriter.Mode.METHOD, false);
            
            signatureWriter.writeFormalTypeParametersStart();
            signatureWriter.writeFormalTypeParametersEnd();
            
            signatureWriter.writeParametersStart();
            
            if (CodegenUtil.hasThis0(descriptor)) {
                signatureWriter.writeParameterType(JvmMethodParameterKind.THIS0);
                typeMapper.mapType(CodegenUtil.getOuterClassDescriptor(descriptor).getDefaultType(), OwnerKind.IMPLEMENTATION, signatureWriter, false);
                signatureWriter.writeParameterTypeEnd();
            }

            if (CodegenUtil.requireTypeInfoConstructorArg(descriptor.getDefaultType())) {
                signatureWriter.writeTypeInfoParameter();
            }

            signatureWriter.writeParametersEnd();
            
            signatureWriter.writeVoidReturn();

            constructorMethod = signatureWriter.makeJvmMethodSignature("<init>");
            callableMethod = new CallableMethod("", "", "", constructorMethod, INVOKESPECIAL);
        }
        else {
            callableMethod = typeMapper.mapToCallableMethod(constructorDescriptor, kind);
            constructorMethod = callableMethod.getSignature();
        }

        ObjectOrClosureCodegen closure = context.closure;
        int firstSuperArgument = -1;
        final LinkedList<JvmMethodParameterSignature> consArgTypes = new LinkedList<JvmMethodParameterSignature>(constructorMethod.getKotlinParameterTypes());

        int insert = 0;
        if(closure != null) {
            if(closure.captureThis) {
                if(!CodegenUtil.hasThis0(descriptor))
                    consArgTypes.add(insert, new JvmMethodParameterSignature(Type.getObjectType(context.getThisDescriptor().getName()), "", JvmMethodParameterKind.THIS0));
                insert++;
            }
            else {
                if(CodegenUtil.hasThis0(descriptor))
                    insert++;
            }

            if(closure.captureReceiver != null)
                consArgTypes.add(insert++, new JvmMethodParameterSignature(closure.captureReceiver, "", JvmMethodParameterKind.RECEIVER));

            for (DeclarationDescriptor descriptor : closure.closure.keySet()) {
                if(descriptor instanceof VariableDescriptor && !(descriptor instanceof PropertyDescriptor)) {
                    final Type sharedVarType = typeMapper.getSharedVarType(descriptor);
                    final Type type = sharedVarType != null ? sharedVarType : state.getTypeMapper().mapType(((VariableDescriptor) descriptor).getOutType());
                    consArgTypes.add(insert++, new JvmMethodParameterSignature(type, "", JvmMethodParameterKind.SHARED_VAR));
                }
                else if(descriptor instanceof FunctionDescriptor) {
                    assert closure.captureReceiver != null;
                }
            }
        }

        if(myClass instanceof JetObjectDeclaration && ((JetObjectDeclaration) myClass).isObjectLiteral()) {
            if(superCall instanceof JetDelegatorToSuperCall) {
                if(closure != null)
                    closure.superCall = (JetDelegatorToSuperCall) superCall;
                DeclarationDescriptor declarationDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, ((JetDelegatorToSuperCall) superCall).getCalleeExpression().getConstructorReferenceExpression());
                if(declarationDescriptor instanceof ClassDescriptor) {
                    declarationDescriptor = ((ClassDescriptor)declarationDescriptor).getUnsubstitutedPrimaryConstructor();
                }
                ConstructorDescriptor superConstructor = (ConstructorDescriptor) declarationDescriptor;
                CallableMethod superCallable = typeMapper.mapToCallableMethod(superConstructor, OwnerKind.IMPLEMENTATION);
                firstSuperArgument = insert;
                for(Type t : superCallable.getSignature().getAsmMethod().getArgumentTypes()) {
                    consArgTypes.add(insert++, new JvmMethodParameterSignature(t, "", JvmMethodParameterKind.SHARED_VAR));
                }
            }

            constructorMethod = JvmMethodSignature.simple("<init>", Type.VOID_TYPE, consArgTypes);
        }

        int flags = ACC_PUBLIC; // TODO
        final MethodVisitor mv = v.newMethod(myClass, flags, constructorMethod.getName(), constructorMethod.getAsmMethod().getDescriptor(), constructorMethod.getGenericsSignature(), null);
        if (!v.generateCode()) return;
        
        AnnotationVisitor jetConstructorVisitor = mv.visitAnnotation(JvmStdlibNames.JET_CONSTRUCTOR.getDescriptor(), true);
        if (constructorDescriptor == null) {
            jetConstructorVisitor.visit(JvmStdlibNames.JET_CONSTRUCTOR_HIDDEN_FIELD, true);
        }
        jetConstructorVisitor.visitEnd();
        
        AnnotationCodegen.forMethod(mv).genAnnotations(constructorDescriptor, typeMapper);

        if (constructorDescriptor != null) {
            int i = 0;

            if (CodegenUtil.hasThis0(descriptor)) {
                i++;
            }

            if (CodegenUtil.requireTypeInfoConstructorArg(descriptor.getDefaultType())) {
                AnnotationVisitor jetTypeParameterAnnotation =
                        mv.visitParameterAnnotation(i++, JvmStdlibNames.JET_TYPE_PARAMETER.getDescriptor(), true);
                jetTypeParameterAnnotation.visitEnd();
            }

            for (ValueParameterDescriptor valueParameter : constructorDescriptor.getValueParameters()) {
                AnnotationVisitor jetValueParameterAnnotation =
                        mv.visitParameterAnnotation(i++, JvmStdlibNames.JET_VALUE_PARAMETER.getDescriptor(), true);
                jetValueParameterAnnotation.visit(JvmStdlibNames.JET_VALUE_PARAMETER_NAME_FIELD, valueParameter.getName());
                if(valueParameter.hasDefaultValue())
                    jetValueParameterAnnotation.visit(JvmStdlibNames.JET_VALUE_PARAMETER_HAS_DEFAULT_VALUE_FIELD, Boolean.TRUE);
                jetValueParameterAnnotation.visitEnd();
            }
        }

        mv.visitCode();

        List<ValueParameterDescriptor> paramDescrs = constructorDescriptor != null
                ? constructorDescriptor.getValueParameters()
                : Collections.<ValueParameterDescriptor>emptyList();

        ConstructorFrameMap frameMap = new ConstructorFrameMap(callableMethod, constructorDescriptor, descriptor, kind);

        final InstructionAdapter iv = new InstructionAdapter(mv);
        ExpressionCodegen codegen = new ExpressionCodegen(mv, frameMap, Type.VOID_TYPE, constructorContext, state);

//        for(int slot = 0; slot != frameMap.getTypeParameterCount(); ++slot) {
//            if(constructorDescriptor != null)
//                codegen.addTypeParameter(constructorDescriptor.getTypeParameters().get(slot), StackValue.local(frameMap.getFirstTypeParameter() + slot, JetTypeMapper.TYPE_TYPEINFO));
//            else
//                codegen.addTypeParameter(descriptor.getTypeConstructor().getParameters().get(slot), StackValue.local(frameMap.getFirstTypeParameter() + slot, JetTypeMapper.TYPE_TYPEINFO));
//        }

        String classname = typeMapper.mapType(descriptor.getDefaultType(), kind).getInternalName();
        final Type classType = Type.getType("L" + classname + ";");

        HashSet<FunctionDescriptor> overridden = new HashSet<FunctionDescriptor>();
        for (JetDeclaration declaration : myClass.getDeclarations()) {
            if (declaration instanceof JetNamedFunction) {
                NamedFunctionDescriptor functionDescriptor = bindingContext.get(BindingContext.FUNCTION, declaration);
                assert functionDescriptor != null;
                overridden.addAll(functionDescriptor.getOverriddenDescriptors());
            }
        }

        if (superCall == null) {
            iv.load(0, Type.getType("L" + superClass + ";"));
            iv.invokespecial(superClass, "<init>", "()V");
        }
        else if (superCall instanceof JetDelegatorToSuperClass) {
            iv.load(0, Type.getType("L" + superClass + ";"));
            JetType superType = bindingContext.get(BindingContext.TYPE, superCall.getTypeReference());
            List<Type> parameterTypes = new ArrayList<Type>();
            assert superType != null;
            ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
            if (CodegenUtil.hasThis0(superClassDescriptor)) {
                iv.load(1, JetTypeMapper.TYPE_OBJECT);
                parameterTypes.add(typeMapper.mapType(CodegenUtil.getOuterClassDescriptor(descriptor).getDefaultType(), OwnerKind.IMPLEMENTATION));
            }
            for(TypeProjection typeParameterDescriptor : superType.getArguments()) {
                codegen.generateTypeInfo(typeParameterDescriptor.getType(), null);
                parameterTypes.add(JetTypeMapper.TYPE_TYPEINFO);
            }
            Method superCallMethod = new Method("<init>", Type.VOID_TYPE, parameterTypes.toArray(new Type[parameterTypes.size()]));
            iv.invokespecial(typeMapper.mapType(superClassDescriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName(), "<init>", superCallMethod.getDescriptor());
        }
        else {
            ConstructorDescriptor constructorDescriptor1 = (ConstructorDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET, ((JetDelegatorToSuperCall) superCall).getCalleeExpression().getConstructorReferenceExpression());
            generateDelegatorToConstructorCall(iv, codegen, (JetDelegatorToSuperCall) superCall, constructorDescriptor1, frameMap, firstSuperArgument);
        }

        int n = 0;
        for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
            if(specifier == superCall)
                continue;

            if (specifier instanceof JetDelegatorByExpressionSpecifier) {
                iv.load(0, classType);
                codegen.genToJVMStack(((JetDelegatorByExpressionSpecifier) specifier).getDelegateExpression());

                JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
                assert superType != null;
                ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
                String delegateField = "$delegate_" + n;
                Type fieldType = typeMapper.mapType(superClassDescriptor.getDefaultType(), OwnerKind.IMPLEMENTATION);
                String fieldDesc = fieldType.getDescriptor();
                v.newField(specifier, ACC_PRIVATE, delegateField, fieldDesc, /*TODO*/null, null);
                iv.putfield(classname, delegateField, fieldDesc);

                JetClass superClass = (JetClass) bindingContext.get(BindingContext.DESCRIPTOR_TO_DECLARATION, superClassDescriptor);
                final CodegenContext delegateContext = context.intoClass(superClassDescriptor,
                        new OwnerKind.DelegateKind(StackValue.field(fieldType, classname, delegateField, false),
                                                   typeMapper.mapType(superClassDescriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName()), state.getTypeMapper());
                generateDelegates(superClass, delegateContext, overridden);
            }
        }

        final ClassDescriptor outerDescriptor = getOuterClassDescriptor();
        if (outerDescriptor != null && !CodegenUtil.isClassObject(outerDescriptor)) {
            final Type type = typeMapper.mapType(outerDescriptor.getDefaultType(), OwnerKind.IMPLEMENTATION);
            String interfaceDesc = type.getDescriptor();
            final String fieldName = "this$0";
            v.newField(myClass, ACC_PRIVATE | ACC_FINAL, fieldName, interfaceDesc, null, null);
            iv.load(0, classType);
            iv.load(frameMap.getOuterThisIndex(), type);
            iv.putfield(classname, fieldName, interfaceDesc);

            Type outerType = typeMapper.mapType(outerDescriptor.getDefaultType());
            MethodVisitor outer = v.newMethod(myClass, ACC_PUBLIC, JvmStdlibNames.JET_OBJECT_GET_OUTER_OBJECT_METHOD, "()Ljet/JetObject;", null, null);
            outer.visitCode();
            outer.visitVarInsn(ALOAD, 0);
            outer.visitFieldInsn(GETFIELD, classname, "this$0", outerType.getDescriptor());
            outer.visitInsn(ARETURN);
            FunctionCodegen.endVisit(outer, JvmStdlibNames.JET_OBJECT_GET_OUTER_OBJECT_METHOD, myClass);
        }

        if (CodegenUtil.requireTypeInfoConstructorArg(descriptor.getDefaultType()) && kind == OwnerKind.IMPLEMENTATION) {
            iv.load(0, JetTypeMapper.TYPE_OBJECT);
            iv.load(frameMap.getTypeInfoIndex(), JetTypeMapper.TYPE_OBJECT);
            iv.invokevirtual(typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName(), JvmAbi.SET_TYPE_INFO_METHOD, "(Ljet/TypeInfo;)V");
        }

        if(closure != null) {
            int k = outerDescriptor != null && outerDescriptor.getKind() != ClassKind.OBJECT ? 2 : 1;
            if(closure.captureReceiver != null) {
                iv.load(0, JetTypeMapper.TYPE_OBJECT);
                iv.load(1, closure.captureReceiver);
                iv.putfield(typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName(), "receiver$0", closure.captureReceiver.getDescriptor());
                k += closure.captureReceiver.getSize();
            }

            int l = 0;
            for (DeclarationDescriptor varDescr : closure.closure.keySet()) {
                if(varDescr instanceof VariableDescriptor && !(varDescr instanceof PropertyDescriptor)) {
                    Type sharedVarType = typeMapper.getSharedVarType(varDescr);
                    if(sharedVarType == null) {
                        sharedVarType = typeMapper.mapType(((VariableDescriptor) varDescr).getOutType());
                    }
                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    iv.load(k, StackValue.refType(sharedVarType));
                    k += StackValue.refType(sharedVarType).getSize();
                    iv.putfield(typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName(), "$" + varDescr.getName(), sharedVarType.getDescriptor());
                    l++;
                }
            }
        }

        int curParam = 0;
        List<JetParameter> constructorParameters = getPrimaryConstructorParameters();
        for (JetParameter parameter : constructorParameters) {
            if (parameter.getValOrVarNode() != null) {
                VariableDescriptor descriptor = paramDescrs.get(curParam);
                Type type = typeMapper.mapType(descriptor.getOutType());
                iv.load(0, classType);
                iv.load(frameMap.getIndex(descriptor), type);
                iv.putfield(classname, descriptor.getName(), type.getDescriptor());
            }
            curParam++;
        }

        generateInitializers(codegen, iv);

        generateTraitMethods(codegen);

        mv.visitInsn(RETURN);
        FunctionCodegen.endVisit(mv, "constructor", myClass);

        FunctionCodegen.generateDefaultIfNeeded(constructorContext, state, v, constructorMethod.getAsmMethod(), constructorDescriptor, OwnerKind.IMPLEMENTATION);
    }

    private void generateTraitMethods(ExpressionCodegen codegen) {
        if(!(myClass instanceof JetClass) || ((JetClass)myClass).isTrait() || ((JetClass)myClass).hasModifier(JetTokens.ABSTRACT_KEYWORD))
            return;
        
        for (Pair<CallableMemberDescriptor, CallableMemberDescriptor> needDelegates : getTraitImplementations(descriptor)) {
            CallableMemberDescriptor callableDescriptor = needDelegates.first;
            FunctionDescriptor fun = (FunctionDescriptor) needDelegates.second;
            generateDelegationToTraitImpl(codegen, fun);
        }
    }

    private void generateDelegationToTraitImpl(ExpressionCodegen codegen, FunctionDescriptor fun) {
        DeclarationDescriptor containingDeclaration = fun.getContainingDeclaration();
        if(containingDeclaration instanceof ClassDescriptor) {
            ClassDescriptor declaration = (ClassDescriptor) containingDeclaration;
            PsiElement psiElement = bindingContext.get(BindingContext.DESCRIPTOR_TO_DECLARATION, declaration);
            if(psiElement instanceof JetClass) {
                JetClass jetClass = (JetClass) psiElement;
                if(jetClass.isTrait()) {
                    int flags = ACC_PUBLIC; // TODO.

                    Method function = typeMapper.mapSignature(fun.getName(), fun).getAsmMethod();
                    Method functionOriginal = typeMapper.mapSignature(fun.getName(), fun.getOriginal()).getAsmMethod();

                    final MethodVisitor mv = v.newMethod(myClass, flags, function.getName(), function.getDescriptor(), null, null);
                    if (v.generateCode()) {
                        mv.visitCode();

                        codegen.generateThisOrOuter(descriptor);

                        Type[] argTypes = function.getArgumentTypes();
                        InstructionAdapter iv = new InstructionAdapter(mv);
                        iv.load(0, JetTypeMapper.TYPE_OBJECT);
                        for (int i = 0, reg = 1; i < argTypes.length; i++) {
                            Type argType = argTypes[i];
                            iv.load(reg, argType);
                            //noinspection AssignmentToForLoopParameter
                            reg += argType.getSize();
                        }

                        JetType jetType = TraitImplBodyCodegen.getSuperClass(declaration, bindingContext);
                        Type type = typeMapper.mapType(jetType);
                        if(type.getInternalName().equals("java/lang/Object")) {
                            jetType = declaration.getDefaultType();
                            type = typeMapper.mapType(jetType);
                        }

                        String fdescriptor = functionOriginal.getDescriptor().replace("(","(" +  type.getDescriptor());
                        iv.invokestatic(typeMapper.mapType(((ClassDescriptor) fun.getContainingDeclaration()).getDefaultType(), OwnerKind.TRAIT_IMPL).getInternalName(), function.getName(), fdescriptor);
                        if(function.getReturnType().getSort() == Type.OBJECT) {
                            iv.checkcast(function.getReturnType());
                        }
                        iv.areturn(function.getReturnType());
                        FunctionCodegen.endVisit(iv, "trait method", bindingContext.get(BindingContext.DESCRIPTOR_TO_DECLARATION, fun));
                    }

                    FunctionCodegen.generateBridgeIfNeeded(context, state, v, function, fun, kind);
                }
            }
        }
    }

    @Nullable
    private ClassDescriptor getOuterClassDescriptor() {
        if (myClass.getParent() instanceof JetClassObject) {
            return null;
        }

        return CodegenUtil.getOuterClassDescriptor(descriptor);
    }

    private void generateDelegatorToConstructorCall(InstructionAdapter iv, ExpressionCodegen codegen, JetCallElement constructorCall,
                                                    ConstructorDescriptor constructorDescriptor,
                                                    ConstructorFrameMap frameMap, int firstSuperArgument) {
        ClassDescriptor classDecl = constructorDescriptor.getContainingDeclaration();

        iv.load(0, TYPE_OBJECT);

        if (classDecl.getContainingDeclaration() instanceof ClassDescriptor) {
            iv.load(frameMap.getOuterThisIndex(), typeMapper.mapType(((ClassDescriptor) descriptor.getContainingDeclaration()).getDefaultType(), OwnerKind.IMPLEMENTATION));
        }

        CallableMethod method = typeMapper.mapToCallableMethod(constructorDescriptor, kind);

        if(myClass instanceof JetObjectDeclaration && superCall instanceof JetDelegatorToSuperCall && ((JetObjectDeclaration) myClass).isObjectLiteral()) {
            ConstructorDescriptor superConstructor = (ConstructorDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET, ((JetDelegatorToSuperCall) superCall).getCalleeExpression().getConstructorReferenceExpression());
            CallableMethod superCallable = typeMapper.mapToCallableMethod(superConstructor, OwnerKind.IMPLEMENTATION);
            int nextVar = firstSuperArgument+1;
            for(Type t : superCallable.getSignature().getAsmMethod().getArgumentTypes()) {
                iv.load(nextVar, t);
                nextVar += t.getSize();
            }
            method.invoke(codegen.v);
        }
        else {
            codegen.invokeMethodWithArguments(method, constructorCall, StackValue.none());
        }
    }

    @Override
    protected void generateDeclaration(PropertyCodegen propertyCodegen, JetDeclaration declaration, FunctionCodegen functionCodegen) {
        if (declaration instanceof JetSecondaryConstructor) {
            generateSecondaryConstructor((JetSecondaryConstructor) declaration);
        }
        else if (declaration instanceof JetClassObject) {
            // done earlier in order to have accessors
        }
        else if (declaration instanceof JetEnumEntry && !((JetEnumEntry) declaration).hasPrimaryConstructor()) {
            String name = declaration.getName();
            final String desc = "L" + typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName() + ";";
            v.newField(declaration, ACC_PUBLIC | ACC_STATIC | ACC_FINAL, name, desc, null, null);
            if (myEnumConstants.isEmpty()) {
                staticInitializerChunks.add(new CodeChunk() {
                    @Override
                    public void generate(InstructionAdapter v) {
                        initializeEnumConstants(v);
                    }
                });
            }
            myEnumConstants.add((JetEnumEntry) declaration);
        }
        else {
            super.generateDeclaration(propertyCodegen, declaration, functionCodegen);
        }
    }

    private final List<JetEnumEntry> myEnumConstants = new ArrayList<JetEnumEntry>();

    private void initializeEnumConstants(InstructionAdapter v) {
        ExpressionCodegen codegen = new ExpressionCodegen(v, new FrameMap(), Type.VOID_TYPE, context, state);
        for (JetEnumEntry enumConstant : myEnumConstants) {
            // TODO type and constructor parameters
            String implClass = typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName();

            final List<JetDelegationSpecifier> delegationSpecifiers = enumConstant.getDelegationSpecifiers();
            if (delegationSpecifiers.size() > 1) {
                throw new UnsupportedOperationException("multiple delegation specifiers for enum constant not supported");
            }

            v.anew(Type.getObjectType(implClass));
            v.dup();

            if (delegationSpecifiers.size() == 1) {
                final JetDelegationSpecifier specifier = delegationSpecifiers.get(0);
                if (specifier instanceof JetDelegatorToSuperCall) {
                    final JetDelegatorToSuperCall superCall = (JetDelegatorToSuperCall) specifier;
                    ConstructorDescriptor constructorDescriptor = (ConstructorDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET, superCall.getCalleeExpression().getConstructorReferenceExpression());
                    CallableMethod method = typeMapper.mapToCallableMethod(constructorDescriptor, OwnerKind.IMPLEMENTATION);
                    codegen.invokeMethodWithArguments(method, superCall, StackValue.none());
                }
                else {
                    throw new UnsupportedOperationException("unsupported type of enum constant initializer: " + specifier);
                }
            }
            else {
                v.invokespecial(implClass, "<init>", "()V");
            }
            v.putstatic(implClass, enumConstant.getName(), "L" + implClass + ";");
        }
    }

    private void generateSecondaryConstructor(JetSecondaryConstructor constructor) {
        ConstructorDescriptor constructorDescriptor = bindingContext.get(BindingContext.CONSTRUCTOR, constructor);
        if (constructorDescriptor == null) {
            throw new UnsupportedOperationException("failed to get descriptor for secondary constructor");
        }
        CallableMethod method = typeMapper.mapToCallableMethod(constructorDescriptor, kind);
        int flags = ACC_PUBLIC; // TODO
        final MethodVisitor mv = v.newMethod(constructor, flags, "<init>", method.getSignature().getAsmMethod().getDescriptor(), null, null);
        if (v.generateCode()) {
            mv.visitCode();

            ConstructorFrameMap frameMap = new ConstructorFrameMap(method, constructorDescriptor, descriptor, kind);

            final InstructionAdapter iv = new InstructionAdapter(mv);
            ExpressionCodegen codegen = new ExpressionCodegen(mv, frameMap, Type.VOID_TYPE, context, state);

            for (JetDelegationSpecifier initializer : constructor.getInitializers()) {
                if (initializer instanceof JetDelegatorToThisCall) {
                    JetDelegatorToThisCall thisCall = (JetDelegatorToThisCall) initializer;
                    DeclarationDescriptor thisDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, thisCall.getThisReference());
                    if (!(thisDescriptor instanceof ConstructorDescriptor)) {
                        throw new UnsupportedOperationException("expected 'this' delegator to resolve to constructor");
                    }
                    generateDelegatorToConstructorCall(iv, codegen, thisCall, (ConstructorDescriptor) thisDescriptor, frameMap, flags);
                }
                else {
                    throw new UnsupportedOperationException("unknown initializer type");
                }
            }

            JetExpression bodyExpression = constructor.getBodyExpression();
            if (bodyExpression != null) {
                codegen.gen(bodyExpression, Type.VOID_TYPE);
            }

            mv.visitInsn(RETURN);
            FunctionCodegen.endVisit(mv, "constructor", null);
        }
    }

    protected void generateInitializers(ExpressionCodegen codegen, InstructionAdapter iv) {
        for (JetDeclaration declaration : myClass.getDeclarations()) {
            if (declaration instanceof JetProperty) {
                final PropertyDescriptor propertyDescriptor = (PropertyDescriptor) bindingContext.get(BindingContext.VARIABLE, declaration);
                if (bindingContext.get(BindingContext.BACKING_FIELD_REQUIRED, propertyDescriptor)) {
                    final JetExpression initializer = ((JetProperty) declaration).getInitializer();
                    if (initializer != null) {
                        CompileTimeConstant<?> compileTimeValue = bindingContext.get(BindingContext.COMPILE_TIME_VALUE, initializer);
                        if(compileTimeValue != null) {
                            assert compileTimeValue != null;
                            Object value = compileTimeValue.getValue();
                            Type type = typeMapper.mapType(propertyDescriptor.getOutType());
                            if(JetTypeMapper.isPrimitive(type)) {
                                if( !propertyDescriptor.getOutType().isNullable() && value instanceof Number) {
                                    if(type == Type.INT_TYPE && ((Number)value).intValue() == 0)
                                        continue;
                                    if(type == Type.BYTE_TYPE && ((Number)value).byteValue() == 0)
                                        continue;
                                    if(type == Type.LONG_TYPE && ((Number)value).longValue() == 0L)
                                        continue;
                                    if(type == Type.SHORT_TYPE && ((Number)value).shortValue() == 0)
                                        continue;
                                    if(type == Type.DOUBLE_TYPE && ((Number)value).doubleValue() == 0d)
                                        continue;
                                    if(type == Type.FLOAT_TYPE && ((Number)value).byteValue() == 0f)
                                        continue;
                                }
                                if(type == Type.BOOLEAN_TYPE && value instanceof Boolean && !((Boolean)value))
                                    continue;
                                if(type == Type.CHAR_TYPE && value instanceof Character && ((Character)value) == 0)
                                    continue;
                            }
                            else {
                                if(value == null)
                                    continue;
                            }
                        }
                        iv.load(0, JetTypeMapper.TYPE_OBJECT);
                        Type type = codegen.expressionType(initializer);
                        if(propertyDescriptor.getOutType().isNullable())
                            type = JetTypeMapper.boxType(type);
                        codegen.gen(initializer, type);
                        // @todo write directly to the field. Fix test excloset.jet::test6
                        String owner = typeMapper.getOwner(propertyDescriptor, OwnerKind.IMPLEMENTATION);
                        StackValue.property(propertyDescriptor.getName(), owner, owner, typeMapper.mapType(propertyDescriptor.getOutType()), false, false, false, null, null, 0).store(iv);
                    }

                }
            }
            else if (declaration instanceof JetClassInitializer) {
                codegen.gen(((JetClassInitializer) declaration).getBody(), Type.VOID_TYPE);
            }
        }
    }

    protected void generateDelegates(JetClass toClass, CodegenContext delegateContext, Set<FunctionDescriptor> overriden) {
        final FunctionCodegen functionCodegen = new FunctionCodegen(delegateContext, v, state);
        final PropertyCodegen propertyCodegen = new PropertyCodegen(delegateContext, v, functionCodegen, state);

        for (JetDeclaration declaration : toClass.getDeclarations()) {
            if (declaration instanceof JetProperty) {
                propertyCodegen.gen((JetProperty) declaration);
            }
            else if (declaration instanceof JetNamedFunction) {
                if (!overriden.contains(bindingContext.get(BindingContext.FUNCTION, declaration))) {
                    functionCodegen.gen((JetNamedFunction) declaration);
                }
            }
        }

        for (JetParameter p : toClass.getPrimaryConstructorParameters()) {
            if (p.getValOrVarNode() != null) {
                PropertyDescriptor propertyDescriptor = bindingContext.get(BindingContext.PRIMARY_CONSTRUCTOR_PARAMETER, p);
                if (propertyDescriptor != null) {
                    propertyCodegen.generateDefaultGetter(propertyDescriptor, ACC_PUBLIC, p);
                    if (propertyDescriptor.isVar()) {
                        propertyCodegen.generateDefaultSetter(propertyDescriptor, ACC_PUBLIC, p);
                    }
                }
            }
        }
    }

    @Nullable
    private JetClassObject getClassObject() {
        return myClass instanceof JetClass ? ((JetClass) myClass).getClassObject() : null;
    }

    private void generateGetTypeInfo() {
        JetType defaultType = descriptor.getDefaultType();
        if(CodegenUtil.requireTypeInfoConstructorArg(defaultType)) {
            if(myClass instanceof JetClass && ((JetClass)myClass).isTrait())
                return;

            if(!CodegenUtil.hasDerivedTypeInfoField(defaultType)) {
                v.newField(myClass, ACC_PROTECTED, JvmAbi.TYPE_INFO_FIELD, "Ljet/TypeInfo;", null, null);

                MethodVisitor mv = v.newMethod(myClass, ACC_PUBLIC, JvmStdlibNames.JET_OBJECT_GET_TYPEINFO_METHOD, "()Ljet/TypeInfo;", null, null);
                if (v.generateCode()) {
                    mv.visitCode();
                    InstructionAdapter iv = new InstructionAdapter(mv);
                    String owner = typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName();
                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    iv.getfield(owner, JvmAbi.TYPE_INFO_FIELD, "Ljet/TypeInfo;");
                    iv.areturn(JetTypeMapper.TYPE_TYPEINFO);
                    FunctionCodegen.endVisit(iv, JvmStdlibNames.JET_OBJECT_GET_TYPEINFO_METHOD, myClass);
                }

                mv = v.newMethod(myClass, ACC_PROTECTED | ACC_FINAL, JvmAbi.SET_TYPE_INFO_METHOD, "(Ljet/TypeInfo;)V", null, null);
                if (v.generateCode()) {
                    mv.visitCode();
                    InstructionAdapter iv = new InstructionAdapter(mv);
                    String owner = typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName();
                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    iv.load(1, JetTypeMapper.TYPE_OBJECT);
                    iv.putfield(owner, JvmAbi.TYPE_INFO_FIELD, "Ljet/TypeInfo;");
                    mv.visitInsn(RETURN);
                    FunctionCodegen.endVisit(iv, JvmAbi.SET_TYPE_INFO_METHOD, myClass);
                }
            }
        }
        else {
            if (!(myClass instanceof JetClass) || !((JetClass) myClass).isTrait()) {
                genGetStaticGetTypeInfoMethod();
            }

            staticTypeInfoField();
        }
    }

    private void genGetStaticGetTypeInfoMethod() {
        final MethodVisitor mv = v.newMethod(myClass, ACC_PUBLIC, JvmStdlibNames.JET_OBJECT_GET_TYPEINFO_METHOD, "()Ljet/TypeInfo;", null, null);
        if (v.generateCode()) {
            mv.visitCode();
            InstructionAdapter v = new InstructionAdapter(mv);
            String owner = typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION).getInternalName();
            v.getstatic(owner, "$staticTypeInfo", "Ljet/TypeInfo;");
            v.areturn(JetTypeMapper.TYPE_TYPEINFO);
            FunctionCodegen.endVisit(v, JvmStdlibNames.JET_OBJECT_GET_TYPEINFO_METHOD, myClass);
        }
    }

    private void staticTypeInfoField() {
        v.newField(myClass, ACC_PUBLIC | ACC_FINAL | ACC_STATIC, "$staticTypeInfo", "Ljet/TypeInfo;", null, null);
        staticInitializerChunks.add(new CodeChunk() {
            @Override
            public void generate(InstructionAdapter v) {
                v.aconst(typeMapper.mapType(descriptor.getDefaultType(), OwnerKind.IMPLEMENTATION));
                v.iconst(0);
                v.invokestatic("jet/TypeInfo", JvmStdlibNames.JET_OBJECT_GET_TYPEINFO_METHOD, "(Ljava/lang/Class;Z)Ljet/TypeInfo;");
                v.putstatic(typeMapper.mapType(descriptor.getDefaultType(), kind).getInternalName(), "$staticTypeInfo", "Ljet/TypeInfo;");
            }
        });
    }


    /**
     * Return pairs of descriptors. First is member of this that should be implemented by delegating to trait,
     * second is member of trait that contain implementation.
     */
    public static List<Pair<CallableMemberDescriptor, CallableMemberDescriptor>> getTraitImplementations(@NotNull ClassDescriptor classDescriptor) {
        List<Pair<CallableMemberDescriptor, CallableMemberDescriptor>> r = Lists.newArrayList();
        
        root:
        for (DeclarationDescriptor decl : classDescriptor.getDefaultType().getMemberScope().getAllDescriptors()) {
            if (!(decl instanceof CallableMemberDescriptor)) {
                continue;
            }

            CallableMemberDescriptor callableMemberDescriptor = (CallableMemberDescriptor) decl;
            if (callableMemberDescriptor.getKind() != CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
                continue;
            }

            Collection<CallableMemberDescriptor> overridenDeclarations = OverridingUtil.getOverridenDeclarations(callableMemberDescriptor);
            for (CallableMemberDescriptor overridenDeclaration : overridenDeclarations) {
                if (overridenDeclaration.getModality() != Modality.ABSTRACT) {
                    if (!CodegenUtil.isInterface(overridenDeclaration.getContainingDeclaration())) {
                        continue root;
                    }
                }
            }
            
            for (CallableMemberDescriptor overridenDeclaration : overridenDeclarations) {
                if (overridenDeclaration.getModality() != Modality.ABSTRACT) {
                    r.add(Pair.create(callableMemberDescriptor, overridenDeclaration));
                }
            }
        }

        return r;
    }

}

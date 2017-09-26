/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.particleframework.aop.writer;

import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.particleframework.aop.HotSwappableInterceptedProxy;
import org.particleframework.aop.Intercepted;
import org.particleframework.aop.InterceptedProxy;
import org.particleframework.aop.Interceptor;
import org.particleframework.aop.internal.InterceptorChain;
import org.particleframework.aop.internal.MethodInterceptorChain;
import org.particleframework.context.BeanContext;
import org.particleframework.core.reflect.ReflectionUtils;
import org.particleframework.inject.BeanDefinition;
import org.particleframework.inject.BeanFactory;
import org.particleframework.inject.ExecutableMethod;
import org.particleframework.inject.writer.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.particleframework.inject.writer.BeanDefinitionWriter.DEFAULT_MAX_STACK;

/**
 * A class that generates AOP proxy classes at compile time
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class AopProxyWriter extends AbstractClassFileWriter implements ProxyingBeanDefinitionVisitor {

    private static final java.lang.reflect.Method METHOD_BEAN_FACTORY_BUILD = ReflectionUtils.getDeclaredMethod(BeanFactory.class, "build", BeanContext.class, BeanDefinition.class)
            .orElseThrow(() -> new IllegalStateException("BeanFactory.build(..) method not found. Incompatible version of Particle on the classpath?"));

    private static final java.lang.reflect.Method METHOD_GET_REQUIRED_METHOD = ReflectionUtils.getDeclaredMethod(BeanDefinition.class, "getRequiredMethod", String.class, Class[].class)
            .orElseThrow(() -> new IllegalStateException("BeanFactory.build(..) method not found. Incompatible version of Particle on the classpath?"));

    private static final java.lang.reflect.Method RESOLVE_INTERCEPTORS_METHOD = ReflectionUtils.getDeclaredMethod(InterceptorChain.class, "resolveInterceptors", AnnotatedElement.class, Interceptor[].class).orElseThrow(() ->
        new IllegalStateException("InterceptorChain.resolveInterceptors(..) method not found. Incompatible version of Particle?")
);

    private static final Constructor CONSTRUCTOR_METHOD_INTERCEPTOR_CHAIN = ReflectionUtils.findConstructor(MethodInterceptorChain.class, Interceptor[].class, Object.class, ExecutableMethod.class, Object[].class).orElseThrow(() ->
            new IllegalStateException("new MethodInterceptorChain(..) constructor not found. Incompatible version of Particle?")
    );

    private static final String FIELD_INTERCEPTORS = "$interceptors";
    private static final String FIELD_PROXY_METHODS = "$proxyMethods";
    private static final Type FIELD_TYPE_PROXY_METHODS = Type.getType(ExecutableMethod[].class);
    private static final Type EXECUTABLE_METHOD_TYPE = Type.getType(ExecutableMethod.class);
    private static final Type INTERCEPTOR_ARRAY_TYPE = Type.getType(Interceptor[].class);
    public static final Type FIELD_TYPE_INTERCEPTORS = Type.getType(Interceptor[][].class);
    public static final Type TYPE_INTERCEPTOR_CHAIN = Type.getType(InterceptorChain.class);
    public static final Type TYPE_METHOD_INTERCEPTOR_CHAIN = Type.getType(MethodInterceptorChain.class);
    public static final String FIELD_PARENT_BEAN = "$PARENT_BEAN";
    public static final String FIELD_TARGET = "$target";
    public static final Type TYPE_BEAN_DEFINITION = Type.getType(BeanDefinition.class);
    public static final String FIELD_READ_WRITE_LOCK = "$target_rwl";
    public static final Type TYPE_READ_WRITE_LOCK = Type.getType(ReentrantReadWriteLock.class);
    public static final String FIELD_READ_LOCK = "$target_rl";
    public static final String FIELD_WRITE_LOCK = "$target_wl";
    public static final Type TYPE_LOCK = Type.getType(Lock.class);

    private final String packageName;
    private final String targetClassShortName;
    private final ClassWriter classWriter;
    private final String targetClassFullName;
    private final String proxyFullName;
    private final BeanDefinitionWriter proxyBeanDefinitionWriter;
    private final String proxyInternalName;
    private final Set<Object> interceptorTypes;
    private final Type proxyType;
    private final boolean hotswap;
    private boolean isProxyTarget;
    private final String parentBeanDefinitionName;


    private MethodVisitor constructorWriter;
    private List<ExecutableMethodWriter> proxiedMethods = new ArrayList<>();
    private Set<MethodRef> proxiedMethodsRefSet = new HashSet<>();
    private List<MethodRef> proxyTargetMethods = new ArrayList<>();
    private int proxyMethodCount = 0;
    private GeneratorAdapter constructorGenerator;
    private int interceptorArgumentIndex;
    private int beanContextArgumentIndex = -1;
    private Map<String, Object> constructorArgumentTypes;
    private Map<String, Object> constructorQualfierTypes;
    private Map<String, List<Object>> constructorGenericTypes;
    private Map<String, Object> constructorNewArgumentTypes;
    /**
     * <p>Constructs a new {@link AopProxyWriter} for the given parent {@link BeanDefinitionWriter} and starting interceptors types.</p>
     *
     * <p>Additional {@link Interceptor} types can be added downstream with {@link #visitInterceptorTypes(Object...)}.</p>
     *
     * @param parent The parent {@link BeanDefinitionWriter}
     * @param interceptorTypes The annotation types of the {@link Interceptor} instances to be injected
     */
    public AopProxyWriter(BeanDefinitionVisitor parent,
                          Object... interceptorTypes) {
        this(parent, false, false, interceptorTypes);
    }
    /**
     * <p>Constructs a new {@link AopProxyWriter} for the given parent {@link BeanDefinitionWriter} and starting interceptors types.</p>
     *
     * <p>Additional {@link Interceptor} types can be added downstream with {@link #visitInterceptorTypes(Object...)}.</p>
     *
     * @param parent The parent {@link BeanDefinitionWriter}
     * @param interceptorTypes The annotation types of the {@link Interceptor} instances to be injected
     */
    public AopProxyWriter(BeanDefinitionVisitor parent,
                          boolean proxyTarget,
                          boolean isHotswap,
                          Object... interceptorTypes) {
        this.isProxyTarget = proxyTarget;
        this.hotswap = isHotswap;
        this.packageName = parent.getPackageName();
        this.targetClassShortName = parent.getBeanSimpleName();
        this.parentBeanDefinitionName = parent.getBeanDefinitionName();
        this.targetClassFullName = packageName + '.' + targetClassShortName;
        this.classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String proxyShortName = targetClassShortName + "$Intercepted";
        this.proxyFullName = packageName + '.' + proxyShortName;
        this.proxyInternalName = getInternalName(this.proxyFullName);
        this.proxyType = getTypeReference(proxyFullName);
        this.interceptorTypes = new HashSet<>(Arrays.asList(interceptorTypes));
        Type scope = parent.getScope();
        String scopeClassName = scope != null ? scope.getClassName() : null;
        this.proxyBeanDefinitionWriter = new BeanDefinitionWriter(packageName, proxyShortName, scopeClassName, parent.isSingleton());
        startClass(classWriter, proxyFullName, getTypeReference(targetClassFullName));
    }

    @Override
    protected void startClass(ClassVisitor classWriter, String className, Type superType) {
        super.startClass(classWriter, className, superType);

        classWriter.visitField(ACC_FINAL | ACC_PRIVATE, FIELD_INTERCEPTORS, FIELD_TYPE_INTERCEPTORS.getDescriptor(), null, null);
        classWriter.visitField(ACC_FINAL | ACC_PRIVATE, FIELD_PROXY_METHODS, FIELD_TYPE_PROXY_METHODS.getDescriptor(), null, null);
    }

    /**
     * @return The bean definition writer for this proxy
     */
    public BeanDefinitionWriter getProxyBeanDefinitionWriter() {
        return proxyBeanDefinitionWriter;
    }

    @Override
    public void visitBeanDefinitionConstructor() {
        visitBeanDefinitionConstructor(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    @Override
    public boolean isSingleton() {
        return proxyBeanDefinitionWriter.isSingleton();
    }

    @Override
    public Type getScope() {
        return proxyBeanDefinitionWriter.getScope();
    }

    @Override
    public String getBeanTypeName() {
        return proxyBeanDefinitionWriter.getBeanTypeName();
    }

    @Override
    public void setValidated(boolean validated) {
        proxyBeanDefinitionWriter.setValidated(validated);
    }

    @Override
    public boolean isValidated() {
        return proxyBeanDefinitionWriter.isValidated();
    }

    @Override
    public String getBeanDefinitionName() {
        return  proxyBeanDefinitionWriter.getBeanDefinitionName();
    }

    /**
     * Visits a constructor with arguments. Either this method or {@link #visitBeanDefinitionConstructor()} should be called at least once
     *
     * @param argumentTypes  The argument names and types. Should be an ordered map should as {@link LinkedHashMap}
     * @param qualifierTypes The argument names and qualifier types. Should be an ordered map should as {@link LinkedHashMap}
     * @param genericTypes   The argument names and generic types. Should be an ordered map should as {@link LinkedHashMap}
     */
    public void visitBeanDefinitionConstructor(Map<String, Object> argumentTypes, Map<String, Object> qualifierTypes, Map<String, List<Object>> genericTypes) {
        this.constructorArgumentTypes = argumentTypes;
        this.constructorQualfierTypes = qualifierTypes;
        this.constructorGenericTypes = genericTypes;
        this.constructorNewArgumentTypes = new LinkedHashMap<>(argumentTypes);
        if(isProxyTarget) {
            this.beanContextArgumentIndex = argumentTypes.size();
            constructorNewArgumentTypes.put("beanContext", BeanContext.class);
        }
        this.interceptorArgumentIndex = constructorNewArgumentTypes.size();
        constructorNewArgumentTypes.put("interceptors", Interceptor[].class);
    }

    /**
     * Visit a method that is to be proxied
     *
     * @param declaringType  The declaring type of the method. Either a Class or a string representing the name of the type
     * @param returnType     The return type of the method. Either a Class or a string representing the name of the type
     * @param methodName     The method name
     * @param argumentTypes  The argument types. Note: an ordered map should be used such as LinkedHashMap. Can be null or empty.
     * @param qualifierTypes The qualifier types of each argument. Can be null.
     * @param genericTypes   The generic types of each argument. Can be null.
     */
    public void visitAroundMethod(Object declaringType,
                                  Object returnType,
                                  List<Object> returnTypeGenericTypes,
                                  String methodName,
                                  Map<String, Object> argumentTypes,
                                  Map<String, Object> qualifierTypes,
                                  Map<String, List<Object>> genericTypes) {

        List<Object> argumentTypeList = new ArrayList<>(argumentTypes.values());
        int argumentCount = argumentTypes.size();
        Type returnTypeObject = getTypeReference(returnType);
        boolean isPrimitive = isPrimitive(returnType);
        boolean isVoidReturn = isPrimitive && returnTypeObject.equals(Type.VOID_TYPE);
        MethodRef methodKey = new MethodRef(methodName, argumentTypeList);
        if(isProxyTarget) {
            // if the target class is being proxied then the method will be looked up from the parent bean definition.
            // Therefore no need to generate a bridge
            if(!proxyTargetMethods.contains(methodKey)) {
                int index = proxyMethodCount++;
                proxyTargetMethods.add(methodKey);
                buildMethodOverride(returnType, methodName, index, argumentTypeList, argumentCount, isVoidReturn);
            }
        }
        else if(!proxiedMethodsRefSet.contains(methodKey)){
            int index = proxyMethodCount++;
            // if the target is not being proxied then we generate a subclass where only the proxied methods are overridden
            // Each overridden method calls super.blah(..) thus maintaining class semantics better and providing smaller more efficient
            // proxies

            String methodProxyShortName = "$$proxy" + index;
            String bridgeName = "$$access" + index;
            String methodExecutorClassName = proxyFullName + methodProxyShortName;

            List<Object> bridgeArguments = new ArrayList<>();
            bridgeArguments.add(proxyFullName);
            bridgeArguments.addAll(argumentTypeList);
            String bridgeDesc = getMethodDescriptor(returnType, bridgeArguments);

            ExecutableMethodWriter executableMethodWriter = new ExecutableMethodWriter(
                    proxyFullName, methodExecutorClassName, methodProxyShortName
            ) {
                @Override
                protected void buildInvokeMethod(Type declaringTypeObject, String methodName, Object returnType, Collection<Object> argumentTypes, MethodVisitor invokeMethodVisitor) {
                    GeneratorAdapter invokeMethodGenerator = new GeneratorAdapter(invokeMethodVisitor, ACC_PUBLIC, METHOD_INVOKE_INTERNAL.getName(), METHOD_INVOKE_INTERNAL.getDescriptor());
                    // load this
                    invokeMethodGenerator.loadThis();
                    // first argument to static bridge is reference to parent
                    invokeMethodGenerator.getField(methodType, FIELD_PARENT, proxyType);
                    // now remaining arguments
                    for (int i = 0; i < argumentTypeList.size(); i++) {
                        invokeMethodGenerator.loadArg(1);
                        invokeMethodGenerator.push(i);
                        invokeMethodGenerator.visitInsn(AALOAD);
                        AopProxyWriter.pushCastToType(invokeMethodVisitor, argumentTypeList.get(i));
                    }
                    invokeMethodGenerator.visitMethodInsn(INVOKESTATIC, proxyInternalName, bridgeName, bridgeDesc, false);
                    if(isVoidReturn) {
                        invokeMethodGenerator.visitInsn(ACONST_NULL);
                    }
                    else {
                        AopProxyWriter.pushBoxPrimitiveIfNecessary(returnType, invokeMethodVisitor);
                    }
                    invokeMethodGenerator.visitInsn(ARETURN);
                    invokeMethodVisitor.visitMaxs(BeanDefinitionWriter.DEFAULT_MAX_STACK, 1);
                    invokeMethodVisitor.visitEnd();
                }
            };
            executableMethodWriter.makeInner(proxyInternalName, classWriter);
            executableMethodWriter.visitMethod(declaringType, returnType, returnTypeGenericTypes, methodName, argumentTypes, qualifierTypes, genericTypes);

            proxiedMethods.add(executableMethodWriter);
            proxiedMethodsRefSet.add(methodKey);
            String overrideDescriptor = buildMethodOverride(returnType, methodName, index, argumentTypeList, argumentCount, isVoidReturn);


            // now build a bridge to invoke the original method

            MethodVisitor bridgeWriter = classWriter.visitMethod(ACC_STATIC | ACC_SYNTHETIC,
                    bridgeName, bridgeDesc, null, null);
            GeneratorAdapter bridgeGenerator = new GeneratorAdapter(bridgeWriter, ACC_STATIC + ACC_SYNTHETIC, bridgeName, bridgeDesc);
            for (int i = 0; i < bridgeArguments.size(); i++) {
                bridgeGenerator.loadArg(i);
            }
            bridgeWriter.visitMethodInsn(INVOKESPECIAL, getInternalName(targetClassFullName), methodName, overrideDescriptor, false);
            pushReturnValue(bridgeWriter, returnType);
            bridgeWriter.visitMaxs(DEFAULT_MAX_STACK, 1);
            bridgeWriter.visitEnd();
        }


    }

    private String buildMethodOverride(Object returnType, String methodName, int index, List<Object> argumentTypeList, int argumentCount, boolean isVoidReturn) {
        // override the original method

        String desc = getMethodDescriptor(returnType, argumentTypeList);
        MethodVisitor overridden = classWriter.visitMethod(ACC_PUBLIC, methodName, desc, null, null);
        GeneratorAdapter overriddenMethodGenerator = new GeneratorAdapter(overridden, ACC_PUBLIC, methodName, desc);

        // store the proxy method instance in a local variable
        // ie ExecutableMethod executableMethod = this.proxyMethods[0];
        overriddenMethodGenerator.loadThis();
        overriddenMethodGenerator.getField(proxyType, FIELD_PROXY_METHODS, FIELD_TYPE_PROXY_METHODS);
        overriddenMethodGenerator.push(index);
        overriddenMethodGenerator.visitInsn(AALOAD);
        int methodProxyVar = overriddenMethodGenerator.newLocal(EXECUTABLE_METHOD_TYPE);
        overriddenMethodGenerator.storeLocal(methodProxyVar);

        // store the interceptors in a local variable
        // ie Interceptor[] interceptors = this.interceptors[0];
        overriddenMethodGenerator.loadThis();
        overriddenMethodGenerator.getField(proxyType, FIELD_INTERCEPTORS, FIELD_TYPE_INTERCEPTORS);
        overriddenMethodGenerator.push(index);
        overriddenMethodGenerator.visitInsn(AALOAD);
        int interceptorsLocalVar = overriddenMethodGenerator.newLocal(INTERCEPTOR_ARRAY_TYPE);
        overriddenMethodGenerator.storeLocal(interceptorsLocalVar);

        // instantiate the MethodInterceptorChain
        // ie InterceptorChain chain = new MethodInterceptorChain(interceptors, this, executableMethod, name);
        overriddenMethodGenerator.newInstance(TYPE_METHOD_INTERCEPTOR_CHAIN);
        overriddenMethodGenerator.dup();

        // first argument: interceptors
        overriddenMethodGenerator.loadLocal(interceptorsLocalVar);

        // second argument: this or target
        overriddenMethodGenerator.loadThis();
        if( isProxyTarget ) {
            if(hotswap) {
               overriddenMethodGenerator.invokeInterface(Type.getType(InterceptedProxy.class), Method.getMethod("java.lang.Object interceptedTarget()") );
            }
            else {
                overriddenMethodGenerator.getField(proxyType, FIELD_TARGET, getTypeReference(targetClassFullName));
            }
        }

        // third argument: the executable method
        overriddenMethodGenerator.loadLocal(methodProxyVar);

        // fourth argument: array of the argument values
        overriddenMethodGenerator.push(argumentCount);
        overriddenMethodGenerator.newArray(Type.getType(Object.class));

        // now pass the remaining arguments from the original method
        for (int i = 0; i < argumentCount; i++) {
            overriddenMethodGenerator.dup();
            Object argType = argumentTypeList.get(i);
            overriddenMethodGenerator.push(i);
            overriddenMethodGenerator.loadArg(i);
            pushBoxPrimitiveIfNecessary(argType, overriddenMethodGenerator);
            overriddenMethodGenerator.visitInsn(AASTORE);
        }
        // invoke MethodInterceptorChain constructor
        overriddenMethodGenerator.invokeConstructor(TYPE_METHOD_INTERCEPTOR_CHAIN, Method.getMethod(CONSTRUCTOR_METHOD_INTERCEPTOR_CHAIN));
        int chainVar = overriddenMethodGenerator.newLocal(TYPE_METHOD_INTERCEPTOR_CHAIN);
        overriddenMethodGenerator.storeLocal(chainVar);
        overriddenMethodGenerator.loadLocal(chainVar);

        overriddenMethodGenerator.visitMethodInsn(INVOKEVIRTUAL, TYPE_INTERCEPTOR_CHAIN.getInternalName(), "proceed", getMethodDescriptor(Object.class.getName()), false);
        if (isVoidReturn) {
            returnVoid(overriddenMethodGenerator);
        }
        else {

            pushCastToType(overriddenMethodGenerator, returnType);
            pushReturnValue(overriddenMethodGenerator, returnType);
        }
        overriddenMethodGenerator.visitMaxs(DEFAULT_MAX_STACK, chainVar);
        overriddenMethodGenerator.visitEnd();
        return desc;
    }

    /**
     * Finalizes the proxy. This method should be called before writing the proxy to disk with {@link #writeTo(File)}
     */
    @Override
    public void visitBeanDefinitionEnd() {
        if (constructorArgumentTypes == null) {
            throw new IllegalStateException("The method visitBeanDefinitionConstructor(..) should be called at least once");
        }
        String constructorDescriptor = getConstructorDescriptor(constructorNewArgumentTypes.values());
        ClassWriter proxyClassWriter = this.classWriter;
        this.constructorWriter = proxyClassWriter.visitMethod(
                ACC_PUBLIC,
                CONSTRUCTOR_NAME,
                constructorDescriptor,
                null,
                null);

        // Add the interceptor @Type(..) annotation
        Type[] interceptorTypes = getObjectTypes(this.interceptorTypes);
        AnnotationVisitor interceptorTypeAnn = constructorWriter.visitParameterAnnotation(
                interceptorArgumentIndex, Type.getDescriptor(org.particleframework.context.annotation.Type.class), true
        ).visitArray("value");
        for (Type interceptorType : interceptorTypes) {
            interceptorTypeAnn.visit(null, interceptorType);
        }
        interceptorTypeAnn.visitEnd();

        this.constructorGenerator = new GeneratorAdapter(constructorWriter, Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, constructorDescriptor);
        GeneratorAdapter proxyConstructorGenerator = this.constructorGenerator;
        proxyConstructorGenerator.loadThis();
        Collection<Object> existingArguments = constructorArgumentTypes.values();
        for (int i = 0; i < existingArguments.size(); i++) {
            proxyConstructorGenerator.loadArg(i);
        }
        String superConstructorDescriptor = getConstructorDescriptor(existingArguments);
        proxyConstructorGenerator.invokeConstructor(getTypeReference(targetClassFullName), new Method(CONSTRUCTOR_NAME, superConstructorDescriptor));
        proxyBeanDefinitionWriter.visitBeanDefinitionConstructor(constructorNewArgumentTypes, constructorQualfierTypes, constructorGenericTypes);

        Class superType = Intercepted.class;
        if(isProxyTarget) {
            superType = hotswap ? HotSwappableInterceptedProxy.class : InterceptedProxy.class;

            // add the $PARENT bean field
            addParentBeanField(proxyClassWriter);

            // add the $target field for the target bean
            Type targetType = getTypeReference(targetClassFullName);
            int modifiers = hotswap ? ACC_PRIVATE : ACC_PRIVATE | ACC_FINAL;
            proxyClassWriter.visitField(
                    modifiers,
                    FIELD_TARGET,
                    targetType.getDescriptor(),
                    null,
                    null
            );
            if(hotswap) {
                // Add ReadWriteLock field
                // private final ReentrantReadWriteLock $target_rwl = new ReentrantReadWriteLock();
                proxyClassWriter.visitField(
                    ACC_PRIVATE | ACC_FINAL,
                        FIELD_READ_WRITE_LOCK,
                        TYPE_READ_WRITE_LOCK.getDescriptor(),
                        null,null
                );
                proxyConstructorGenerator.loadThis();
                proxyConstructorGenerator.newInstance(TYPE_READ_WRITE_LOCK);
                proxyConstructorGenerator.dup();
                proxyConstructorGenerator.invokeConstructor(TYPE_READ_WRITE_LOCK, METHOD_DEFAULT_CONSTRUCTOR );
                proxyConstructorGenerator.putField(proxyType, FIELD_READ_WRITE_LOCK, TYPE_READ_WRITE_LOCK);

                // Add Read Lock field
                // private final Lock $target_rl = $target_rwl.readLock();
                proxyClassWriter.visitField(
                        ACC_PRIVATE | ACC_FINAL,
                        FIELD_READ_LOCK,
                        TYPE_LOCK.getDescriptor(),
                        null,null
                );
                proxyConstructorGenerator.loadThis();
                proxyConstructorGenerator.loadThis();
                proxyConstructorGenerator.getField(proxyType, FIELD_READ_WRITE_LOCK, TYPE_READ_WRITE_LOCK);
                proxyConstructorGenerator.invokeInterface(
                        Type.getType(ReadWriteLock.class),
                        Method.getMethod(Lock.class.getName() + " readLock()")
                );
                proxyConstructorGenerator.putField(proxyType, FIELD_READ_LOCK, TYPE_LOCK );

                // Add Write Lock field
                // private final Lock $target_wl = $target_rwl.writeLock();
                proxyClassWriter.visitField(
                        ACC_PRIVATE | ACC_FINAL,
                        FIELD_WRITE_LOCK,
                        Type.getDescriptor(Lock.class),
                        null,null
                );

                proxyConstructorGenerator.loadThis();
                proxyConstructorGenerator.loadThis();
                proxyConstructorGenerator.getField(proxyType, FIELD_READ_WRITE_LOCK, TYPE_READ_WRITE_LOCK);
                proxyConstructorGenerator.invokeInterface(
                        Type.getType(ReadWriteLock.class),
                        Method.getMethod(Lock.class.getName() + " writeLock()")
                );
                proxyConstructorGenerator.putField(proxyType, FIELD_WRITE_LOCK, TYPE_LOCK );
            }

            // add the logic to create to the bean instance
            proxyConstructorGenerator.loadThis();
            // second argument: the bean definition
            proxyConstructorGenerator.getStatic(
                    proxyType,
                    FIELD_PARENT_BEAN,
                    TYPE_BEAN_DEFINITION
            );
            // first argument: the bean context
            proxyConstructorGenerator.loadArg(beanContextArgumentIndex);
            // second argument: the bean definition
            proxyConstructorGenerator.getStatic(
                    proxyType,
                    FIELD_PARENT_BEAN,
                    TYPE_BEAN_DEFINITION
                    );

            // invoke the build method to build the proxy target
            proxyConstructorGenerator.invokeInterface(Type.getType(BeanFactory.class), Method.getMethod(
                    METHOD_BEAN_FACTORY_BUILD
            ));
            pushCastToType(proxyConstructorGenerator, targetClassFullName);
            proxyConstructorGenerator.putField(proxyType, FIELD_TARGET, targetType);

            // Write the Object interceptedTarget() method
            writeInterceptedTargetMethod(proxyClassWriter, targetType);

            // Write the swap method
            // e. T swap(T newInstance);
            writeSwapMethod(proxyClassWriter, targetType);
        }

        proxyClassWriter.visit(V1_8, ACC_PUBLIC,
                proxyInternalName,
                null,
                getTypeReference(targetClassFullName).getInternalName(),
                new String[]{Type.getInternalName(superType)});



        // set $proxyMethods field
        proxyConstructorGenerator.loadThis();
        proxyConstructorGenerator.push(proxyMethodCount);
        proxyConstructorGenerator.newArray(EXECUTABLE_METHOD_TYPE);
        proxyConstructorGenerator.putField(
                proxyType,
                FIELD_PROXY_METHODS,
                FIELD_TYPE_PROXY_METHODS
        );

        // set $interceptors field
        proxyConstructorGenerator.loadThis();
        proxyConstructorGenerator.push(proxyMethodCount);
        proxyConstructorGenerator.newArray(INTERCEPTOR_ARRAY_TYPE);
        proxyConstructorGenerator.putField(
                proxyType,
                FIELD_INTERCEPTORS,
                FIELD_TYPE_INTERCEPTORS
        );

        // now initialize the held values
        if(isProxyTarget) {
            if(proxyTargetMethods.size() == proxyMethodCount) {

                Iterator<MethodRef> iterator = proxyTargetMethods.iterator();
                for (int i = 0; i < proxyMethodCount; i++) {
                    MethodRef methodRef = iterator.next();

                    // The following will initialize the array of $proxyMethod instances
                    // Eg. this.$proxyMethods[0] = $PARENT_BEAN.getRequiredMethod("test", new Class[]{String.class});

                    proxyConstructorGenerator.loadThis();

                    // Step 1: dereference the array - this.$proxyMethods[0]
                    proxyConstructorGenerator.getField(proxyType, FIELD_PROXY_METHODS, FIELD_TYPE_PROXY_METHODS);
                    proxyConstructorGenerator.push(i);

                    // Step 2: lookup the Method instance from the declaring type
                    // $PARENT_BEAN.getRequiredMethod("test", new Class[]{String.class});
                    proxyConstructorGenerator.getStatic(proxyType,FIELD_PARENT_BEAN, TYPE_BEAN_DEFINITION);

                    pushMethodNameAndTypesArguments(proxyConstructorGenerator, methodRef.name, methodRef.argumentTypes);
                    proxyConstructorGenerator.invokeInterface(
                            TYPE_BEAN_DEFINITION,
                            Method.getMethod(METHOD_GET_REQUIRED_METHOD)
                    );
                    // Step 3: store the result in the array
                    proxyConstructorGenerator.visitInsn(AASTORE);

                    // Step 4: Resolve the interceptors
                    // this.$interceptors[0] = InterceptorChain.resolveInterceptors(this.$proxyMethods[0], var2);
                    pushResolveInterceptorsCall(proxyConstructorGenerator, i);
                }
            }
        }
        else {

            for (int i = 0; i < proxyMethodCount; i++) {

                ExecutableMethodWriter executableMethodWriter = proxiedMethods.get(i);

                // The following will initialize the array of $proxyMethod instances
                // Eg. this.proxyMethods[0] = new $blah0();
                proxyConstructorGenerator.loadThis();
                proxyConstructorGenerator.getField(proxyType, FIELD_PROXY_METHODS, FIELD_TYPE_PROXY_METHODS);
                proxyConstructorGenerator.push(i);
                Type methodType = Type.getObjectType(executableMethodWriter.getInternalName());
                proxyConstructorGenerator.newInstance(methodType);
                proxyConstructorGenerator.dup();
                proxyConstructorGenerator.loadThis();
                proxyConstructorGenerator.invokeConstructor(methodType, new Method(CONSTRUCTOR_NAME, getConstructorDescriptor(proxyFullName)));
                proxyConstructorGenerator.visitInsn(AASTORE);
                pushResolveInterceptorsCall(proxyConstructorGenerator, i);

            }
        }

        constructorWriter.visitInsn(RETURN);
        constructorWriter.visitMaxs(DEFAULT_MAX_STACK, 1);

        this.constructorWriter.visitEnd();
        proxyBeanDefinitionWriter.visitBeanDefinitionEnd();
        proxyClassWriter.visitEnd();
    }

    private void writeSwapMethod(ClassWriter proxyClassWriter, Type targetType) {
        GeneratorAdapter swapGenerator = startPublicMethod(proxyClassWriter, "swap", targetType.getClassName(), targetType.getClassName());
        Label l0 = new Label();
        Label l1 = new Label();
        Label l2 = new Label();
        swapGenerator.visitTryCatchBlock(
                l0,
                l1,
                l2,
                null
        );
        // add write lock
        writeLock(swapGenerator);
        swapGenerator.visitLabel(l0);
        swapGenerator.loadThis();
        swapGenerator.getField(proxyType,FIELD_TARGET, targetType);
        // release write lock
        int localRef = swapGenerator.newLocal(targetType);
        swapGenerator.storeLocal(localRef);

        // assign the new value
        swapGenerator.loadThis();
        swapGenerator.visitVarInsn(ALOAD, 1);
        swapGenerator.putField(proxyType, FIELD_TARGET, targetType);

        swapGenerator.visitLabel(l1);
        writeUnlock(swapGenerator);
        swapGenerator.loadLocal(localRef);
        swapGenerator.returnValue();
        swapGenerator.visitLabel(l2);
        // release write lock in finally
        int var = swapGenerator.newLocal(targetType);
        swapGenerator.storeLocal(var);
        writeUnlock(swapGenerator);
        swapGenerator.loadLocal(var);
        swapGenerator.throwException();

        swapGenerator.visitMaxs(2,3);
        swapGenerator.visitEnd();
    }


    /**
     * Write the proxy to the given compilation directory
     *
     * @param compilationDir The target compilation directory
     * @throws IOException
     */
    @Override
    public void writeTo(File compilationDir) throws IOException {
        accept(newClassWriterOutputVisitor(compilationDir));
    }

    /**
     * Write the class to output via a visitor that manages output destination
     *
     * @param visitor the writer output visitor
     * @throws IOException If an error occurs
     */
    @Override
    public void accept(ClassWriterOutputVisitor visitor) throws IOException {
        proxyBeanDefinitionWriter.accept(visitor);
        try (OutputStream out = visitor.visitClass(proxyFullName)) {
            out.write(classWriter.toByteArray());
            proxiedMethods.forEach((writer) -> {
                try {
                    try (OutputStream outputStream = visitor.visitClass(writer.getClassName())) {
                        outputStream.write(writer.getClassWriter().toByteArray());
                    }
                } catch (Throwable e) {
                    throw new ClassGenerationException("Error generating class: " + writer.getClassName(), e);
                }
            });

        }
    }

    @Override
    public void visitSetterInjectionPoint(Object declaringType, Object qualifierType, boolean requiresReflection, Object fieldType, String fieldName, String setterName, List<Object> genericTypes) {
        proxyBeanDefinitionWriter.visitSetterInjectionPoint(
                declaringType, qualifierType, requiresReflection, fieldType, fieldName, setterName, genericTypes
        );
    }

    @Override
    public void visitSuperType(String name) {
        proxyBeanDefinitionWriter.visitSuperType(name);
    }

    @Override
    public void visitSetterValue(Object declaringType, Object qualifierType, boolean requiresReflection, Object fieldType, String fieldName, String setterName, List<Object> genericTypes, boolean isOptional) {
        proxyBeanDefinitionWriter.visitSetterValue(
                declaringType, qualifierType, requiresReflection, fieldType, fieldName, setterName, genericTypes, isOptional
        );
    }

    @Override
    public void visitPostConstructMethod(Object declaringType, boolean requiresReflection, Object returnType, String methodName, Map<String, Object> argumentTypes, Map<String, Object> qualifierTypes, Map<String, List<Object>> genericTypes) {
        proxyBeanDefinitionWriter.visitPostConstructMethod(
                declaringType,requiresReflection, returnType, methodName, argumentTypes, qualifierTypes, genericTypes
        );
    }

    @Override
    public void visitPreDestroyMethod(Object declaringType, boolean requiresReflection, Object returnType, String methodName, Map<String, Object> argumentTypes, Map<String, Object> qualifierTypes, Map<String, List<Object>> genericTypes) {
        proxyBeanDefinitionWriter.visitPreDestroyMethod(declaringType, requiresReflection,returnType, methodName,argumentTypes, qualifierTypes, genericTypes);
    }

    @Override
    public void visitMethodInjectionPoint(Object declaringType, boolean requiresReflection, Object returnType, String methodName, Map<String, Object> argumentTypes, Map<String, Object> qualifierTypes, Map<String, List<Object>> genericTypes) {
        proxyBeanDefinitionWriter.visitMethodInjectionPoint(declaringType, requiresReflection,returnType, methodName,argumentTypes, qualifierTypes, genericTypes);
    }

    @Override
    public void visitExecutableMethod(Object declaringType, Object returnType, List<Object> returnTypeGenericTypes, String methodName, Map<String, Object> argumentTypes, Map<String, Object> qualifierTypes, Map<String, List<Object>> genericTypes) {
        proxyBeanDefinitionWriter.visitExecutableMethod(
                declaringType,
                returnType,
                returnTypeGenericTypes,
                methodName,
                argumentTypes,
                qualifierTypes,
                genericTypes
        );
    }

    @Override
    public void visitFieldInjectionPoint(Object declaringType, Object qualifierType, boolean requiresReflection, Object fieldType, String fieldName) {
        proxyBeanDefinitionWriter.visitFieldInjectionPoint(declaringType, qualifierType, requiresReflection, fieldType, fieldName);
    }

    @Override
    public void visitFieldValue(Object declaringType, Object qualifierType, boolean requiresReflection, Object fieldType, String fieldName, boolean isOptional) {
        proxyBeanDefinitionWriter.visitFieldValue(declaringType, qualifierType, requiresReflection, fieldType, fieldName, isOptional);
    }

    @Override
    public String getPackageName() {
        return proxyBeanDefinitionWriter.getPackageName();
    }

    @Override
    public String getBeanSimpleName() {
        return proxyBeanDefinitionWriter.getBeanSimpleName();
    }

    @Override
    public String getProxiedTypeName() {
        return targetClassFullName;
    }


    public void visitInterceptorTypes(Object... interceptorTypes) {
        if(interceptorTypes != null) {
            this.interceptorTypes.addAll(Arrays.asList(interceptorTypes));
        }
    }

    protected void addParentBeanField(ClassWriter proxyClassWriter) {
        proxyClassWriter.visitField(
                MODIFIERS_PRIVATE_STATIC_FINAL,
                FIELD_PARENT_BEAN,
                Type.getDescriptor(BeanDefinition.class),
                null,
                null
        );
        GeneratorAdapter staticInitGenerator = visitStaticInitializer(proxyClassWriter);
        Type parentType = getTypeReference(
                parentBeanDefinitionName
        );
        staticInitGenerator.newInstance(parentType);
        staticInitGenerator.dup();
        staticInitGenerator.invokeConstructor(parentType, METHOD_DEFAULT_CONSTRUCTOR);
        staticInitGenerator.putStatic(
                proxyType,
                FIELD_PARENT_BEAN,
                TYPE_BEAN_DEFINITION
        );
        staticInitGenerator.visitInsn(RETURN);
        staticInitGenerator.visitEnd();
        staticInitGenerator.visitMaxs(DEFAULT_MAX_STACK, 1);
    }

    private void readUnlock(GeneratorAdapter interceptedTargetVisitor) {
        invokeMethodOnLock(interceptedTargetVisitor, FIELD_READ_LOCK, Method.getMethod("void unlock()"));
    }

    private void readLock(GeneratorAdapter interceptedTargetVisitor) {
        invokeMethodOnLock(interceptedTargetVisitor, FIELD_READ_LOCK, Method.getMethod("void lock()"));
    }

    private void writeUnlock(GeneratorAdapter interceptedTargetVisitor) {
        invokeMethodOnLock(interceptedTargetVisitor, FIELD_WRITE_LOCK, Method.getMethod("void unlock()"));
    }

    private void writeLock(GeneratorAdapter interceptedTargetVisitor) {
        invokeMethodOnLock(interceptedTargetVisitor, FIELD_WRITE_LOCK, Method.getMethod("void lock()"));
    }

    private void invokeMethodOnLock(GeneratorAdapter interceptedTargetVisitor, String field, Method method) {
        interceptedTargetVisitor.loadThis();
        interceptedTargetVisitor.getField(proxyType, field, TYPE_LOCK);
        interceptedTargetVisitor.invokeInterface(TYPE_LOCK, method);
    }

    private void writeInterceptedTargetMethod(ClassWriter proxyClassWriter, Type targetType) {
        // add interceptedTarget() method
        GeneratorAdapter interceptedTargetVisitor = startPublicMethod(
                proxyClassWriter,
                "interceptedTarget",
                Object.class.getName());
        int localRef = -1;
        Label l1 = null;
        Label l2 = null;
        if(hotswap) {
            Label l0 = new Label();
            l1 = new Label();
            l2 = new Label();
            interceptedTargetVisitor.visitTryCatchBlock(
                    l0,
                    l1,
                    l2,
                    null
            );
            // add read lock
            readLock(interceptedTargetVisitor);
            interceptedTargetVisitor.visitLabel(l0);
        }
        interceptedTargetVisitor.loadThis();
        interceptedTargetVisitor.getField(proxyType,FIELD_TARGET, targetType);
        if(hotswap) {
            // release read lock
            localRef = interceptedTargetVisitor.newLocal(targetType);
            interceptedTargetVisitor.storeLocal(localRef);
            interceptedTargetVisitor.visitLabel(l1);
            readUnlock(interceptedTargetVisitor);
            interceptedTargetVisitor.loadLocal(localRef);
        }
        interceptedTargetVisitor.returnValue();
        if(localRef > -1) {
            interceptedTargetVisitor.visitLabel(l2);
            // release read lock in finally
            int var = interceptedTargetVisitor.newLocal(targetType);
            interceptedTargetVisitor.storeLocal(var);
            readUnlock(interceptedTargetVisitor);
            interceptedTargetVisitor.loadLocal(var);
            interceptedTargetVisitor.throwException();
        }

        interceptedTargetVisitor.visitMaxs(1,2);
        interceptedTargetVisitor.visitEnd();
    }

    private void pushResolveInterceptorsCall(GeneratorAdapter proxyConstructorGenerator, int i) {
        // The following will initialize the array of interceptor instances
        // eg. this.interceptors[0] = InterceptorChain.resolveInterceptors(proxyMethods[0], interceptors);
        proxyConstructorGenerator.loadThis();
        proxyConstructorGenerator.getField(proxyType, FIELD_INTERCEPTORS, FIELD_TYPE_INTERCEPTORS);
        proxyConstructorGenerator.push(i);

        // First argument ie. proxyMethods[0]
        proxyConstructorGenerator.loadThis();
        proxyConstructorGenerator.getField(proxyType, FIELD_PROXY_METHODS, FIELD_TYPE_PROXY_METHODS);
        proxyConstructorGenerator.push(i);
        proxyConstructorGenerator.visitInsn(AALOAD);

        // Second argument ie. interceptors
        proxyConstructorGenerator.loadArg(interceptorArgumentIndex);
        proxyConstructorGenerator.invokeStatic(TYPE_INTERCEPTOR_CHAIN, Method.getMethod(RESOLVE_INTERCEPTORS_METHOD));
        proxyConstructorGenerator.visitInsn(AASTORE);
    }

    private class MethodRef {
        final String name;
        final List<Object> argumentTypes;

        public MethodRef(String name, List<Object> argumentTypes) {
            this.name = name;
            this.argumentTypes = argumentTypes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            MethodRef methodRef = (MethodRef) o;

            if (!name.equals(methodRef.name)) return false;
            return argumentTypes.equals(methodRef.argumentTypes);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + argumentTypes.hashCode();
            return result;
        }
    }
}
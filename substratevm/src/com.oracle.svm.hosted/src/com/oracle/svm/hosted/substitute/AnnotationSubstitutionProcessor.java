/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.substitute;

import static com.oracle.svm.core.SubstrateOptions.VerifyNamingConventions;
import static com.oracle.svm.core.util.UserError.guarantee;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.graalvm.nativeimage.AnnotationAccess;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.hosted.FieldValueTransformer;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.infrastructure.SubstitutionProcessor;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.AnnotateOriginal;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.InjectAccessors;
import com.oracle.svm.core.annotate.KeepOriginal;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;
import com.oracle.svm.core.fieldvaluetransformer.ArrayBaseOffsetFieldValueTransformer;
import com.oracle.svm.core.fieldvaluetransformer.ArrayIndexScaleFieldValueTransformer;
import com.oracle.svm.core.fieldvaluetransformer.ArrayIndexShiftFieldValueTransformer;
import com.oracle.svm.core.fieldvaluetransformer.ConstantValueFieldValueTransformer;
import com.oracle.svm.core.fieldvaluetransformer.FieldOffsetFieldValueTransformer;
import com.oracle.svm.core.fieldvaluetransformer.NewInstanceOfFixedClassFieldValueTransformer;
import com.oracle.svm.core.fieldvaluetransformer.StaticFieldBaseFieldValueTransformer;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.NativeImageGenerator;
import com.oracle.svm.hosted.NativeImageOptions;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.ameta.FieldValueInterceptionSupport;
import com.oracle.svm.hosted.classinitialization.ClassInitializationSupport;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.util.ReflectionUtil;
import com.oracle.svm.util.ReflectionUtil.ReflectionUtilError;

import jdk.internal.reflect.Reflection;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The main substitution processor for Native Image. The annotations supported by this processor
 * are:
 * <ul>
 * <li>{@link TargetClass}</li>
 * <li>{@link Substitute}</li>
 * <li>{@link TargetElement}</li>
 * <li>{@link Alias}</li>
 * <li>{@link AnnotateOriginal}</li>
 * <li>{@link Delete}</li>
 * <li>{@link Inject}</li>
 * <li>{@link InjectAccessors}</li>
 * <li>{@link KeepOriginal}</li>
 * <li>{@link RecomputeFieldValue}</li>
 * </ul>
 * Code tagged with these annotations is preprocessed during Native Image setup when the processor
 * is {@link AnnotationSubstitutionProcessor#init(FieldValueInterceptionSupport) initialized}. Then,
 * hosted code corresponding to the substitution code is intercepted and replaced without modifying
 * the class files during {@link AnalysisUniverse} lookups. See each annotation's JavaDoc for more
 * details, starting with {@link TargetClass}. See also {@link HostedUniverse} for a comprehensive
 * description of the substitution layer.
 */
public class AnnotationSubstitutionProcessor extends SubstitutionProcessor {

    /**
     * The number of array dimensions we create for @{@link Substitute} types, i.e., the maximum
     * array dimension allowed by the JVM spec. For @{@link Alias} types the array substitution
     * mappings are created on demand.
     *
     * @see <a href= "https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.9">
     *      Constraints on Java Virtual Machine Code</a>
     */
    private static final int SUBSTITUTE_ARRAY_DIMENSIONS = 255;

    protected final ImageClassLoader imageClassLoader;
    protected final MetaAccessProvider metaAccess;
    private FieldValueInterceptionSupport fieldValueInterceptionSupport;

    /**
     * Contains all elements marked with {@code @Delete}, regardless of whether they are reported at
     * build time or run time.
     */
    private final Map<Object, Delete> deleteAnnotations;
    private final Map<ResolvedJavaType, ResolvedJavaType> typeSubstitutions;
    private final Map<ResolvedJavaMethod, ResolvedJavaMethod> methodSubstitutions;
    private final Map<ResolvedJavaMethod, ResolvedJavaMethod> polymorphicMethodSubstitutions;
    private final Map<ResolvedJavaField, ResolvedJavaField> fieldSubstitutions;
    private Map<Field, Object> unsafeAccessedFields = new HashMap<>();
    private final ClassInitializationSupport classInitializationSupport;
    private final Set<String> disabledSubstitutions;
    private final boolean reportUnsupportedElementAtRuntime;

    public AnnotationSubstitutionProcessor(ImageClassLoader imageClassLoader, MetaAccessProvider metaAccess, ClassInitializationSupport classInitializationSupport) {
        this.imageClassLoader = imageClassLoader;
        this.metaAccess = metaAccess;
        this.classInitializationSupport = classInitializationSupport;

        deleteAnnotations = new HashMap<>();
        typeSubstitutions = new ConcurrentHashMap<>();
        methodSubstitutions = new ConcurrentHashMap<>();
        polymorphicMethodSubstitutions = new HashMap<>();
        fieldSubstitutions = new ConcurrentHashMap<>();
        disabledSubstitutions = Set.copyOf(SubstrateOptions.DisableSubstitution.getValue().values());
        reportUnsupportedElementAtRuntime = NativeImageOptions.ReportUnsupportedElementsAtRuntime.getValue();
    }

    @Override
    public ResolvedJavaType lookup(ResolvedJavaType type) {
        Delete deleteAnnotation = deleteAnnotations.get(type);
        if (deleteAnnotation != null && !reportUnsupportedElementAtRuntime) {
            throw new DeletedElementException(deleteErrorMessage(type, deleteAnnotation, true));
        }
        ResolvedJavaType substitution = findTypeSubstitution(type);
        if (substitution != null) {
            return substitution;
        }
        return type;
    }

    private ResolvedJavaType findTypeSubstitution(ResolvedJavaType type) {
        ResolvedJavaType substitution = typeSubstitutions.get(type);
        if (substitution != null) {
            return substitution;
        }
        if (type.isArray()) {
            ResolvedJavaType elementalType = type.getElementalType();
            ResolvedJavaType elementalTypeSubstitution = typeSubstitutions.get(elementalType);
            if (elementalTypeSubstitution != null) {
                /*
                 * The elementalType must be an alias type and an alias for an array type of the
                 * requested dimension has not yet been created. The registered substitution is the
                 * original type that the alias is pointing to.
                 */
                int dimension = SubstrateUtil.arrayTypeDimension(type);

                /*
                 * Eagerly register all array types of dimensions up to the required type dimension.
                 * These would be created anyway when iterating through the component types in the
                 * AnalysisType constructor.
                 */
                ResolvedJavaType annotated = elementalType;
                ResolvedJavaType original = elementalTypeSubstitution;
                for (int i = 0; i < dimension; i++) {
                    annotated = annotated.getArrayClass();
                    original = original.getArrayClass();
                    typeSubstitutions.putIfAbsent(annotated, original);
                }

                return original;
            }
        }
        return null;
    }

    @Override
    public ResolvedJavaField lookup(ResolvedJavaField field) {
        Delete deleteAnnotation = deleteAnnotations.get(field);
        if (deleteAnnotation != null && !reportUnsupportedElementAtRuntime) {
            throw new DeletedElementException(deleteErrorMessage(field, deleteAnnotation, true));
        }

        ResolvedJavaField existing = fieldSubstitutions.get(field);
        return existing != null ? existing : field;
    }

    public boolean isDeleted(Field field) {
        return isDeleted(metaAccess.lookupJavaField(field));
    }

    public boolean isDeleted(ResolvedJavaField field) {
        if (deleteAnnotations.get(field) != null) {
            return true;
        }
        if (isAnnotationPresent(field, Delete.class)) {
            return true;
        }
        if (isImplicitlyDeleted(field)) {
            return true;
        }
        return false;
    }

    /**
     * When an entire type is fully substituted, for example when {@link Class} is replaced with
     * {@link DynamicHub}, we replace all fields of the original type. All the fields that are
     * not @{@link Substitute} are implicitly considered as @{@link Delete}. However, not all
     * implicitly deleted fields are present in the {@link #deleteAnnotations} map because when the
     * original class fields are iterated {@link Class#getDeclaredFields()} applies
     * {@link Reflection#filterFields(Class, Field[])} and excludes several fields from reflection
     * access.
     */
    private boolean isImplicitlyDeleted(ResolvedJavaField field) {
        /*
         * If a field's type is fully substituted but the field was not substituted, then it is
         * considered implicitly deleted.
         */
        return typeSubstitutions.get(field.getDeclaringClass()) instanceof SubstitutionType && !fieldSubstitutions.containsKey(field);
    }

    public boolean hasInjectAccessors(ResolvedJavaField field) {
        return isAnnotationPresent(field, InjectAccessors.class);
    }

    private boolean isAnnotationPresent(ResolvedJavaField field, Class<? extends Annotation> annotationClass) {
        ResolvedJavaField substitutionField = fieldSubstitutions.get(field);
        if (substitutionField != null) {
            return AnnotationAccess.isAnnotationPresent(substitutionField, annotationClass);
        }
        return false;
    }

    public boolean isDeleted(Class<?> clazz) {
        return deleteAnnotations.containsKey(metaAccess.lookupJavaType(clazz));
    }

    public Optional<ResolvedJavaField> findSubstitution(ResolvedJavaField field) {
        assert !isDeleted(field) : "Field " + field.format("%H.%n") + "is deleted.";
        return Optional.ofNullable(fieldSubstitutions.get(field));
    }

    public Optional<ResolvedJavaType> findFullSubstitution(ResolvedJavaType type) {
        /*
         * When a type is substituted there is a mapping from the original type to the substitution
         * type (and another mapping from the annotated type to the substitution type).
         */
        ResolvedJavaType subst = findTypeSubstitution(type);
        return (subst instanceof SubstitutionType) ? Optional.of(subst) : Optional.empty();
    }

    public boolean isAliased(ResolvedJavaType type) {
        /*
         * When a type is aliased there is a mapping from the alias type to the original type. If
         * the type is an array type then its alias is constructed on demand, but there should be a
         * mapping from the aliased component type to the original component type.
         */
        if (type instanceof SubstitutionType) {
            return false;
        }
        return typeSubstitutions.containsValue(type) || typeSubstitutions.containsValue(type.getElementalType());
    }

    @Override
    public ResolvedJavaMethod lookup(ResolvedJavaMethod method) {
        Delete deleteAnnotation = deleteAnnotations.get(method);
        if (deleteAnnotation != null && !reportUnsupportedElementAtRuntime) {
            throw new DeletedElementException(deleteErrorMessage(method, deleteAnnotation, true));
        }
        ResolvedJavaMethod substitution = methodSubstitutions.get(method);
        if (substitution != null) {
            return substitution;
        }
        for (ResolvedJavaMethod baseMethod : polymorphicMethodSubstitutions.keySet()) {
            if (method.getDeclaringClass().equals(baseMethod.getDeclaringClass()) && method.getName().equals(baseMethod.getName())) {
                SubstitutionMethod substitutionBaseMethod = (SubstitutionMethod) polymorphicMethodSubstitutions.get(baseMethod);
                if (method.isVarArgs()) {
                    /*
                     * The only version of the polymorphic method that has varargs is the base one.
                     */
                    return substitutionBaseMethod;
                }

                PolymorphicSignatureWrapperMethod wrapperMethod = new PolymorphicSignatureWrapperMethod(substitutionBaseMethod, method);
                SubstitutionMethod substitutionMethod = new SubstitutionMethod(method, wrapperMethod, false, true);
                synchronized (methodSubstitutions) {
                    /*
                     * It may happen that, during analysis, two threads are trying to register the
                     * same variant of a polymorphic method simultaneously. This check ensures that
                     * when this happens, the variant is registered only once and both lookups
                     * return the same substitution.
                     */
                    ResolvedJavaMethod currentSubstitution = methodSubstitutions.get(method);
                    if (currentSubstitution != null) {
                        return currentSubstitution;
                    }
                    register(methodSubstitutions, wrapperMethod, method, substitutionMethod);
                }

                return substitutionMethod;
            }
        }
        return method;
    }

    /**
     * Eagerly register all target fields of recomputed value fields as unsafe accessed.
     */
    public void registerUnsafeAccessedFields(BigBang bb) {
        for (var entry : unsafeAccessedFields.entrySet()) {
            AnalysisField targetField = bb.getMetaAccess().lookupJavaField(entry.getKey());
            assert !AnnotationAccess.isAnnotationPresent(targetField, Delete.class);
            targetField.registerAsUnsafeAccessed(entry.getValue());
        }
        /* Prevent later additions that would go unnoticed. */
        unsafeAccessedFields = null;
    }

    public void init(FieldValueInterceptionSupport newFieldValueInterceptionSupport) {
        /*
         * Cannot set this field in the constructor due to cyclic dependencies between the two
         * classes.
         */
        this.fieldValueInterceptionSupport = newFieldValueInterceptionSupport;

        List<Class<?>> annotatedClasses = findTargetClasses();

        /* Sort by name to make processing order predictable for debugging. */
        annotatedClasses.sort(Comparator.comparing(Class::getName));

        for (Class<?> annotatedClass : annotatedClasses) {
            handleClass(annotatedClass);
        }
    }

    protected List<Class<?>> findTargetClasses() {
        return imageClassLoader.findAnnotatedClasses(TargetClass.class, false);
    }

    protected void handleClass(Class<?> annotatedClass) {
        guarantee(Modifier.isFinal(annotatedClass.getModifiers()) || annotatedClass.isInterface(), "Annotated class must be final: %s", annotatedClass);
        guarantee(annotatedClass.getSuperclass() == Object.class || annotatedClass.isInterface(), "Annotated class must inherit directly from Object: %s", annotatedClass);
        guarantee(annotatedClass.getDeclaringClass() == null || Modifier.isStatic(annotatedClass.getModifiers()),
                        "Annotated class must be a static inner class, or a top-level class: %s", annotatedClass);
        if (!NativeImageGenerator.includedIn(ImageSingletons.lookup(Platform.class), lookupAnnotation(annotatedClass, Platforms.class))) {
            return;
        }

        TargetClass targetClassAnnotation = lookupAnnotation(annotatedClass, TargetClass.class);
        Class<?> originalClass = findTargetClass(annotatedClass, targetClassAnnotation);
        if (originalClass == null) {
            return;
        }

        /*
         * The annotatedClass is never used directly, i.e., never wrapped in an AnalysisType. So we
         * need to ensure manually here that its static initializer runs.
         */
        classInitializationSupport.forceInitializeHosted(annotatedClass, "substitutions are always initialized", false);

        Delete deleteAnnotation = lookupAnnotation(annotatedClass, Delete.class);
        Substitute substituteAnnotation = lookupAnnotation(annotatedClass, Substitute.class);

        int numAnnotations = (deleteAnnotation != null ? 1 : 0) + (substituteAnnotation != null ? 1 : 0);
        guarantee(numAnnotations <= 1, "Only one of @Delete or @Substitute can be used: %s", annotatedClass);

        if (deleteAnnotation != null) {
            handleDeletedClass(originalClass, deleteAnnotation);
        } else if (substituteAnnotation != null) {
            handleSubstitutionClass(annotatedClass, originalClass);
        } else {
            handleAliasClass(annotatedClass, originalClass, targetClassAnnotation);
        }
    }

    private static String substitutionName(Class<?> originalClass) {
        return "Target_" + originalClass.getName().replace('.', '_').replace('$', '_');
    }

    private void handleAliasClass(Class<?> annotatedClass, Class<?> originalClass, TargetClass targetClassAnnotation) {
        if (VerifyNamingConventions.getValue() && targetClassAnnotation.classNameProvider() == TargetClass.NoClassNameProvider.class) {
            String expectedName = substitutionName(originalClass);
            // Checkstyle: allow Class.getSimpleName
            String actualName = annotatedClass.getSimpleName();
            // Checkstyle: disallow Class.getSimpleName
            guarantee(actualName.equals(expectedName) || actualName.startsWith(expectedName + "_"),
                            "Naming convention violation: %s must be named %s or %s_<suffix>", annotatedClass, expectedName, expectedName);
        }

        ResolvedJavaType annotated = metaAccess.lookupJavaType(annotatedClass);
        ResolvedJavaType original = metaAccess.lookupJavaType(originalClass);

        guarantee(!typeSubstitutions.containsKey(annotated), "Already registered: %s", annotated);
        typeSubstitutions.put(annotated, original);

        /* The aliases for array types are registered on demand. */

        for (Method annotatedMethod : annotatedClass.getDeclaredMethods()) {
            handleMethodInAliasClass(annotatedMethod, originalClass);
        }
        for (Constructor<?> annotatedMethod : annotatedClass.getDeclaredConstructors()) {
            handleMethodInAliasClass(annotatedMethod, originalClass);
        }
        for (Field annotatedField : annotatedClass.getDeclaredFields()) {
            handleFieldInAliasClass(annotatedField, originalClass);
        }
    }

    private void handleMethodInAliasClass(Executable annotatedMethod, Class<?> originalClass) {
        if (skipExcludedPlatform(annotatedMethod) || annotatedMethod.isSynthetic()) {
            return;
        }

        Delete deleteAnnotation = lookupAnnotation(annotatedMethod, Delete.class);
        Substitute substituteAnnotation = lookupAnnotation(annotatedMethod, Substitute.class);
        AnnotateOriginal annotateOriginalAnnotation = lookupAnnotation(annotatedMethod, AnnotateOriginal.class);
        Alias aliasAnnotation = lookupAnnotation(annotatedMethod, Alias.class);

        int numAnnotations = (deleteAnnotation != null ? 1 : 0) + (substituteAnnotation != null ? 1 : 0) + (annotateOriginalAnnotation != null ? 1 : 0) + (aliasAnnotation != null ? 1 : 0);
        if (numAnnotations == 0) {
            if (!(annotatedMethod instanceof Constructor) && annotatedMethod.getName().startsWith("lambda$")) {
                String targetClass = annotatedMethod.getDeclaringClass().getName();
                String[] methodNameParts = annotatedMethod.getName().split("[$]");
                String method = methodNameParts.length > 1 ? methodNameParts[1] : annotatedMethod.getName();

                throw UserError.abort("Lambda usage detected in the substitution method: %s#%s. Lambdas are not supported inside" +
                                " substitution methods. To fix the issue, replace the culprit lambda with an equivalent anonymous class.", targetClass, method);
            }
            guarantee(annotatedMethod instanceof Constructor, "One of @Delete, @Substitute, @AnnotateOriginal, or @Alias must be used: %s", annotatedMethod);
            return;
        }
        guarantee(numAnnotations == 1, "Only one of @Delete, @Substitute, @AnnotateOriginal, or @Alias can be used: %s", annotatedMethod);

        ResolvedJavaMethod annotated = metaAccess.lookupJavaMethod(annotatedMethod);
        ResolvedJavaMethod original = findOriginalMethod(annotatedMethod, originalClass);
        if (original == null) {
            /* Optional target that is not present, so nothing to do. */
            return;
        }

        if (!disabledSubstitutions.isEmpty()) {
            /*
             * Substitutions can be disabled on the command line. The three formats to match are
             * specified in the help text of the option DisableSubstitution.
             */
            if (disabledSubstitutions.contains(annotated.format("%H")) ||
                            disabledSubstitutions.contains(annotated.format("%H.%n")) ||
                            disabledSubstitutions.contains(annotated.format("%H.%n(%P)"))) {
                return;
            }
        }

        if (deleteAnnotation != null) {
            if (SubstrateOptions.VerifyNamingConventions.getValue()) {
                int modifiers = original.getModifiers();
                if (Modifier.isProtected(modifiers) || Modifier.isPublic(modifiers)) {
                    String format = "Detected a public or protected method annotated with @Delete: %s. " +
                                    "Such usages of @Delete are not permited since these methods can be called " +
                                    "from third party code and can lead to unsupported features. " +
                                    "Instead the method should be replaced with a @Substitute method and `throw VMError.unsupportedFeature()`.";
                    throw UserError.abort(format, annotatedMethod);
                }
            }
            registerAsDeleted(annotated, original, deleteAnnotation);
        } else if (substituteAnnotation != null) {
            if (AnnotationAccess.isAnnotationPresent(annotated, Uninterruptible.class) && !isEffectivelyFinal(original)) {
                throw UserError.abort("@Uninterruptible may only be combined with @Substitute if the original method is effectively final: %s", annotatedMethod);
            }

            SubstitutionMethod substitution = new SubstitutionMethod(original, annotated, false, true);
            if (substituteAnnotation.polymorphicSignature()) {
                register(polymorphicMethodSubstitutions, annotated, original, substitution);
            }
            register(methodSubstitutions, annotated, original, substitution);
        } else if (annotateOriginalAnnotation != null) {
            if (AnnotationAccess.isAnnotationPresent(annotated, Uninterruptible.class) && !isEffectivelyFinal(original)) {
                throw UserError.abort("@Uninterruptible may only be combined with @AnnotateOriginal if the original method is effectively final: %s", annotatedMethod);
            }

            AnnotatedMethod substitution = new AnnotatedMethod(original, annotated);
            register(methodSubstitutions, annotated, original, substitution);
        } else if (aliasAnnotation != null) {
            register(methodSubstitutions, annotated, original, original);
        }
    }

    private static boolean isEffectivelyFinal(ResolvedJavaMethod original) {
        return original.isPrivate() || original.isStatic() || original.isFinalFlagSet() || original.getDeclaringClass().isFinalFlagSet();
    }

    private boolean skipExcludedPlatform(AnnotatedElement annotatedMethod) {
        return !NativeImageGenerator.includedIn(ImageSingletons.lookup(Platform.class), lookupAnnotation(annotatedMethod, Platforms.class));
    }

    private void handleFieldInAliasClass(Field annotatedField, Class<?> originalClass) {
        if (skipExcludedPlatform(annotatedField) || annotatedField.isSynthetic()) {
            return;
        }

        ResolvedJavaField annotated = metaAccess.lookupJavaField(annotatedField);

        Delete deleteAnnotation = lookupAnnotation(annotatedField, Delete.class);
        Alias aliasAnnotation = lookupAnnotation(annotatedField, Alias.class);
        Inject injectAnnotation = lookupAnnotation(annotatedField, Inject.class);

        int numAnnotations = (deleteAnnotation != null ? 1 : 0) + (aliasAnnotation != null ? 1 : 0) + (injectAnnotation != null ? 1 : 0);
        if (numAnnotations == 0) {
            guarantee(annotatedField.getName().equals("$assertionsDisabled"), "One of @Delete, @Alias, or @Inject must be used: %s", annotatedField);
            /*
             * The field $assertionsDisabled can be present in the original class, but does not have
             * to. We treat it like an optional @Alias fields without field value recomputation.
             */
            ResolvedJavaField original = findOriginalField(annotatedField, originalClass, true);
            if (original != null) {
                register(fieldSubstitutions, annotated, null, original);
            }
            return;
        }
        guarantee(numAnnotations == 1, "Only one of @Delete, @Alias, or @Inject can be used: %s", annotatedField);

        if (injectAnnotation != null) {
            guarantee(!annotated.isStatic(), "@Inject field must not be static: %s", annotated);

            ResolvedJavaField injected = fieldValueRecomputation(originalClass, annotated, annotated, annotatedField);
            register(fieldSubstitutions, annotated, null, injected);

            ResolvedJavaType original = metaAccess.lookupJavaType(originalClass);
            InjectedFieldsType substitution;
            if (typeSubstitutions.get(original) instanceof InjectedFieldsType) {
                substitution = (InjectedFieldsType) typeSubstitutions.get(original);
                register(typeSubstitutions, annotated.getDeclaringClass(), original, substitution);
            } else {
                substitution = new InjectedFieldsType(original);
                register(typeSubstitutions, annotated.getDeclaringClass(), original, substitution);
            }
            substitution.addInjectedField(injected);

        } else {
            ResolvedJavaField original = findOriginalField(annotatedField, originalClass, false);
            if (original == null) {
                return;
            }

            guarantee(annotated.isStatic() == original.isStatic(), "Static modifier mismatch: %s, %s", annotated, original);
            guarantee(annotated.getJavaKind() == original.getJavaKind(), "Type mismatch: %s, %s", annotated, original);

            RecomputeFieldValue recomputeAnnotation = lookupAnnotation(annotatedField, RecomputeFieldValue.class);
            if (Modifier.isStatic(annotatedField.getModifiers()) && (recomputeAnnotation == null || recomputeAnnotation.kind() != RecomputeFieldValue.Kind.FromAlias)) {
                guarantee(hasDefaultValue(annotatedField), "The value assigned to a static @Alias field is ignored unless @RecomputeFieldValue with kind=FromAlias is used: %s", annotated);
            }
            guarantee(!Modifier.isFinal(annotatedField.getModifiers()), "The `final` modifier for the @Alias field is ignored and therefore misleading: %s", annotated);

            if (deleteAnnotation != null) {
                registerAsDeleted(annotated, original, deleteAnnotation);
            } else {
                ResolvedJavaField computedAlias = fieldValueRecomputation(originalClass, original, annotated, annotatedField);

                ResolvedJavaField existingAlias = fieldSubstitutions.get(original);
                ResolvedJavaField alias = computedAlias;
                if (existingAlias != null) {
                    /*
                     * Allow multiple @Alias definitions for the same field as long as only one of
                     * them has a @RecomputeValueField annotation.
                     */
                    if (computedAlias.equals(original) || isCompatible(computedAlias, existingAlias)) {
                        /*
                         * The currently processed field does not have a @RecomputeValueField
                         * annotation. Use whatever alias was registered previously.
                         */
                        alias = existingAlias;
                    } else if (existingAlias.equals(original)) {
                        /*
                         * The alias registered previously does not have a @RecomputeValueField
                         * annotation. We need to patch the previous registration. But we do not
                         * know which annotated field that was, so we need to iterate the whole
                         * field substitution registry and look for the matching value.
                         */
                        fieldSubstitutions.replaceAll((key, value) -> value.equals(existingAlias) ? computedAlias : value);
                    } else {
                        /*
                         * Both the current and the previous registration have
                         * a @RecomputeValueField annotation or there is some other mismatch. Let
                         * the register() call below report an error.
                         */
                    }
                }

                register(fieldSubstitutions, annotated, original, alias);
            }
        }
    }

    private static boolean isCompatible(ResolvedJavaField computedAlias, ResolvedJavaField existingAlias) {
        /* The only use case at the moment are multiple @Alias definitions for a final field. */
        if (computedAlias instanceof AliasField computed && existingAlias instanceof AliasField existing) {
            return computed.original.equals(existing.original) && computed.isFinal == existing.isFinal;
        }
        return false;
    }

    private static boolean hasDefaultValue(Field annotatedField) {
        try {
            annotatedField.setAccessible(true);
            /*
             * We use the automatic widening of primitive types to reduce the number of different
             * types we have to distinguish here.
             */
            if (!annotatedField.getType().isPrimitive()) {
                return annotatedField.get(null) == null;
            } else if (annotatedField.getType() == float.class || annotatedField.getType() == double.class) {
                return annotatedField.getDouble(null) == 0D;
            } else if (annotatedField.getType() == boolean.class) {
                return annotatedField.getBoolean(null) == false;
            } else {
                return annotatedField.getLong(null) == 0L;
            }
        } catch (ReflectiveOperationException ex) {
            throw VMError.shouldNotReachHere(ex);
        }
    }

    private void handleDeletedClass(Class<?> originalClass, Delete deleteAnnotation) {
        if (reportUnsupportedElementAtRuntime) {
            ResolvedJavaType type = metaAccess.lookupJavaType(originalClass);

            try {
                type.link();
            } catch (LinkageError ignored) {
                /*
                 * Ignore any linking errors. A type that cannot be linked doesn't need elements
                 * replaced: it will simply fail at runtime with the same linkage error before
                 * reaching those elements.
                 */
                return;
            }

            /*
             * We register all methods and fields as deleted. That still allows usage of the type in
             * type checks.
             */
            for (ResolvedJavaMethod method : type.getDeclaredMethods()) {
                registerAsDeleted(null, method, deleteAnnotation);
            }
            for (ResolvedJavaMethod constructor : type.getDeclaredConstructors()) {
                registerAsDeleted(null, constructor, deleteAnnotation);
            }
            for (ResolvedJavaField f : type.getInstanceFields(false)) {
                registerAsDeleted(null, f, deleteAnnotation);
            }
            for (ResolvedJavaField f : type.getStaticFields()) {
                registerAsDeleted(null, f, deleteAnnotation);
            }
        }
        deleteAnnotations.put(metaAccess.lookupJavaType(originalClass), deleteAnnotation);
    }

    private void registerAsDeleted(ResolvedJavaMethod annotated, ResolvedJavaMethod original, Delete deleteAnnotation) {
        if (reportUnsupportedElementAtRuntime) {
            register(methodSubstitutions, annotated, original, new DeletedMethod(original, deleteAnnotation));
        }
        deleteAnnotations.put(original, deleteAnnotation);
        deleteAnnotations.put(annotated, deleteAnnotation);

    }

    private void registerAsDeleted(ResolvedJavaField annotated, ResolvedJavaField original, Delete deleteAnnotation) {
        if (reportUnsupportedElementAtRuntime) {
            AnnotatedField annotatedField = new AnnotatedField(original, deleteAnnotation);
            register(fieldSubstitutions, annotated, original, annotatedField);
            fieldValueInterceptionSupport.registerFieldValueTransformer(original, null, new ValueNeverAvailableFieldValueTransformer(annotatedField));
        }
        deleteAnnotations.put(original, deleteAnnotation);
        deleteAnnotations.put(annotated, deleteAnnotation);
    }

    @Delete("The declaring class of this element has been substituted, but this element is not present in the substitution class") //
    static final int SUBSTITUTION_DELETE_HOLDER = 0;

    static final Delete SUBSTITUTION_DELETE;

    static {
        try {
            SUBSTITUTION_DELETE = AnnotationSubstitutionProcessor.class.getDeclaredField("SUBSTITUTION_DELETE_HOLDER").getAnnotation(Delete.class);
        } catch (NoSuchFieldException ex) {
            throw VMError.shouldNotReachHere(ex);
        }
    }

    private void handleSubstitutionClass(Class<?> annotatedClass, Class<?> originalClass) {
        // Not sure what happens if the target class is in a hierarchy - so prohibit that for now.
        guarantee(annotatedClass.isInterface() == originalClass.isInterface(), "if original is interface, target must also be interface: %s", annotatedClass);
        guarantee(originalClass.getSuperclass() == Object.class || originalClass.isInterface(), "target class must inherit directly from Object: %s", originalClass);

        boolean keepOriginalElements = lookupAnnotation(annotatedClass, KeepOriginal.class) != null;
        ResolvedJavaType original = metaAccess.lookupJavaType(originalClass);
        ResolvedJavaType annotated = metaAccess.lookupJavaType(annotatedClass);

        SubstitutionType substitution = new SubstitutionType(original, annotated, true);
        register(typeSubstitutions, annotated, original, substitution);

        for (int i = 1; i <= SUBSTITUTE_ARRAY_DIMENSIONS; i++) {
            original = original.getArrayClass();
            annotated = annotated.getArrayClass();
            SubstitutionType arrayTypeSubstitution = new SubstitutionType(original, annotated, true);
            register(typeSubstitutions, annotated, original, arrayTypeSubstitution);
        }

        for (Method m : annotatedClass.getDeclaredMethods()) {
            handleAnnotatedMethodInSubstitutionClass(m, originalClass);
        }
        for (Constructor<?> c : annotatedClass.getDeclaredConstructors()) {
            handleAnnotatedMethodInSubstitutionClass(c, originalClass);
        }
        for (Method m : originalClass.getDeclaredMethods()) {
            handleOriginalMethodInSubstitutionClass(m, keepOriginalElements);
        }
        for (Constructor<?> c : originalClass.getDeclaredConstructors()) {
            handleOriginalMethodInSubstitutionClass(c, keepOriginalElements);
        }

        for (Field f : annotatedClass.getDeclaredFields()) {
            ResolvedJavaField field = metaAccess.lookupJavaField(f);
            ResolvedJavaField alias = fieldValueRecomputation(annotatedClass, field, field, f);
            if (!alias.equals(field)) {
                ResolvedJavaField originalField = findOriginalField(f, originalClass, true);
                guarantee(originalField == null || !(alias.isFinal() && !originalField.isFinal()), "a non-final field cannot be redeclared as final through substitution: %s", field);
                register(fieldSubstitutions, field, originalField, alias);
            } else {
                handleAnnotatedFieldInSubstitutionClass(f, originalClass);
            }
        }
        for (Field f : originalClass.getDeclaredFields()) {
            handleOriginalFieldInSubstitutionClass(f, keepOriginalElements, substitution);
        }
    }

    private void handleAnnotatedMethodInSubstitutionClass(Executable annotatedMethod, Class<?> originalClass) {
        if (skipExcludedPlatform(annotatedMethod)) {
            return;
        }

        if (annotatedMethod.isSynthetic()) {
            /*
             * Synthetic bridge methods for co-variant return types inherit the annotations. We
             * ignore such methods here, and handleOriginalMethodInSubstitutionClass keeps the
             * original implementation of such methods.
             */
            return;
        }

        Substitute substituteAnnotation = lookupAnnotation(annotatedMethod, Substitute.class);
        KeepOriginal keepOriginalAnnotation = lookupAnnotation(annotatedMethod, KeepOriginal.class);

        int numAnnotations = (substituteAnnotation != null ? 1 : 0) + (keepOriginalAnnotation != null ? 1 : 0);
        if (numAnnotations == 0) {
            /* Unannotated method in substitution class: a regular method, nothing to do. */
            return;
        }
        guarantee(numAnnotations == 1, "only one of @Substitute or @KeepOriginal can be used: %s", annotatedMethod);

        ResolvedJavaMethod annotated = metaAccess.lookupJavaMethod(annotatedMethod);
        ResolvedJavaMethod original = findOriginalMethod(annotatedMethod, originalClass);

        if (original == null) {
            /* Optional target that is not present, so nothing to do. */
        } else if (substituteAnnotation != null) {
            SubstitutionMethod substitution = new SubstitutionMethod(original, annotated, true, true);
            if (substituteAnnotation.polymorphicSignature()) {
                register(polymorphicMethodSubstitutions, annotated, original, substitution);
            }
            register(methodSubstitutions, annotated, original, substitution);
        } else if (keepOriginalAnnotation != null) {
            register(methodSubstitutions, annotated, original, original);
        }
    }

    private void handleAnnotatedFieldInSubstitutionClass(Field annotatedField, Class<?> originalClass) {
        if (skipExcludedPlatform(annotatedField)) {
            return;
        }

        Substitute substituteAnnotation = lookupAnnotation(annotatedField, Substitute.class);

        if (substituteAnnotation == null) {
            /* Unannotated field in substitution class: a regular field, nothing to do. */
            return;
        }

        ResolvedJavaField annotated = metaAccess.lookupJavaField(annotatedField);
        ResolvedJavaField original = findOriginalField(annotatedField, originalClass, false);

        if (original == null) {
            /* Optional target that is not present, so nothing to do. */
        } else {
            register(fieldSubstitutions, annotated, original, new SubstitutionField(original, annotated, true));
        }
    }

    private void handleOriginalMethodInSubstitutionClass(Executable m, boolean keepOriginalElements) {
        ResolvedJavaMethod method = metaAccess.lookupJavaMethod(m);
        if (!methodSubstitutions.containsKey(method)) {
            if (keepOriginalElements || method.isSynthetic()) {
                /*
                 * Synthetic methods are mostly methods generated by javac to access private fields
                 * from inner classes. The naming is not fixed, and it would be tedious anyway to
                 * manually mark such methods as @KeepOriginal. We therefore treat all synthetic
                 * methods as if they were annotated with @KeepOriginal. If the method/field that
                 * the synthetic method is forwarding to is not available, an error message for that
                 * method/field will be produced anyway.
                 *
                 * This also treats synthetic bridge methods as @KeepOriginal, so that
                 * handleAnnotatedMethodInSubstitutionClass does not need to handle them.
                 */
                register(methodSubstitutions, null, method, method);
            } else {
                registerAsDeleted(null, method, SUBSTITUTION_DELETE);
            }
        }
    }

    private void handleOriginalFieldInSubstitutionClass(Field f, boolean keepOriginalElements, SubstitutionType substitution) {
        ResolvedJavaField field = metaAccess.lookupJavaField(f);
        if (!fieldSubstitutions.containsKey(field)) {
            if (keepOriginalElements || field.isSynthetic()) {
                register(fieldSubstitutions, null, field, field);
                if (!field.isStatic()) {
                    substitution.addInstanceField(field);
                }
            } else {
                registerAsDeleted(null, field, SUBSTITUTION_DELETE);
            }
        }
    }

    /**
     * Finds the original name of an {@link AnnotatedElement}.
     *
     * @return the original name, or {@code null} if the element is not {@linkplain #isIncluded
     *         included}.
     */
    public String findOriginalElementName(AnnotatedElement annotatedElement, Class<?> originalClass) {
        TargetElement targetElementAnnotation = lookupAnnotation(annotatedElement, TargetElement.class);
        String originalName = "";
        if (targetElementAnnotation != null) {
            originalName = targetElementAnnotation.name();
            if (!isIncluded(targetElementAnnotation, originalClass, annotatedElement)) {
                return null;
            }
        }

        if (originalName.length() == 0) {
            originalName = ((Member) annotatedElement).getName();
        }
        return originalName;
    }

    private ResolvedJavaMethod findOriginalMethod(Executable annotatedMethod, Class<?> originalClass) {
        String originalName = findOriginalElementName(annotatedMethod, originalClass);
        if (originalName == null) {
            return null;
        }

        try {
            if (annotatedMethod instanceof Method && !originalName.equals(TargetElement.CONSTRUCTOR_NAME)) {
                Class<?>[] originalParams = interceptParameterTypes(annotatedMethod.getParameterTypes());
                Method originalMethod = originalClass.getDeclaredMethod(originalName, originalParams);

                guarantee(Modifier.isStatic(annotatedMethod.getModifiers()) == Modifier.isStatic(originalMethod.getModifiers()), "Static modifier mismatch: %s, %s", annotatedMethod, originalMethod);
                guarantee(getTargetClass(((Method) annotatedMethod).getReturnType()).equals(originalMethod.getReturnType()),
                                "Return type mismatch:%n    %s%n    %s", annotatedMethod, originalMethod);
                return metaAccess.lookupJavaMethod(originalMethod);

            } else {
                guarantee(!Modifier.isStatic(annotatedMethod.getModifiers()), "Constructor Alias method %s must not be static", annotatedMethod);
                Class<?>[] originalParams = interceptParameterTypes(annotatedMethod.getParameterTypes());
                Constructor<?> originalMethod = originalClass.getDeclaredConstructor(originalParams);
                return metaAccess.lookupJavaMethod(originalMethod);
            }

        } catch (NoSuchMethodException ex) {
            throw UserError.abort("Could not find target method: %s", annotatedMethod);
        } catch (LinkageError error) {
            throw UserError.abort("Cannot find %s.%s, %s can not be loaded, due to %s not being available in the classpath. Are you missing a dependency in your classpath?",
                            originalClass.getName(), originalName, originalClass.getName(), error.getMessage());
        }
    }

    private ResolvedJavaField findOriginalField(Field annotatedField, Class<?> originalClass, boolean forceOptional) {
        String originalName = findOriginalElementName(annotatedField, originalClass);
        if (originalName == null) {
            return null;
        }

        try {
            Field originalField = originalClass.getDeclaredField(originalName);

            guarantee(getTargetClass(annotatedField.getType()).equals(originalField.getType()),
                            "Type mismatch:%n    %s %s%n    %s %s", annotatedField.getType(), annotatedField, originalField.getType(), originalField);

            return metaAccess.lookupJavaField(originalField);

        } catch (NoSuchFieldException ex) {
            /*
             * Some fields are hidden from reflection. The set of hidden fields is computed via
             * {sun.reflect,jdk.internal.reflect}.Reflection.fieldFilterMap. Try to find the field
             * via the ResolvedJavaType.
             */
            ResolvedJavaField[] fields;
            if (Modifier.isStatic(annotatedField.getModifiers())) {
                fields = metaAccess.lookupJavaType(originalClass).getStaticFields();
            } else {
                fields = metaAccess.lookupJavaType(originalClass).getInstanceFields(true);
            }
            for (ResolvedJavaField f : fields) {
                if (f.getName().equals(originalName)) {
                    return f;
                }
            }

            guarantee(forceOptional, "could not find target field: %s", annotatedField);
            return null;
        }
    }

    public static boolean isIncluded(TargetElement targetElementAnnotation, Class<?> originalClass, AnnotatedElement annotatedElement) {
        if (targetElementAnnotation == null) {
            return true;
        }

        return SVMHost.evaluateOnlyWith(targetElementAnnotation.onlyWith(), annotatedElement.toString(), originalClass);
    }

    private static <T> void register(Map<T, T> substitutions, T annotated, T original, T target) {
        if (annotated != null) {
            guarantee(!substitutions.containsKey(annotated) || substitutions.get(annotated).equals(original) || substitutions.get(annotated).equals(target),
                            "Substitution: %s -> %s conflicts with previously registered: %s", annotated, target, substitutions.get(annotated));
            substitutions.put(annotated, target);
        }
        if (original != null) {
            guarantee(!substitutions.containsKey(original) || substitutions.get(original).equals(original) || substitutions.get(original).equals(target),
                            "Substitution: %s -> %s conflicts with previously registered: %s", original, target, substitutions.get(original));
            substitutions.put(original, target);
        }
    }

    private ResolvedJavaField fieldValueRecomputation(Class<?> originalClass, ResolvedJavaField original, ResolvedJavaField annotated, Field annotatedField) {
        RecomputeFieldValue recomputeAnnotation = lookupAnnotation(annotatedField, RecomputeFieldValue.class);
        InjectAccessors injectAccessorsAnnotation = lookupAnnotation(annotatedField, InjectAccessors.class);

        int numAnnotations = (recomputeAnnotation != null ? 1 : 0) + (injectAccessorsAnnotation != null ? 1 : 0);
        guarantee(numAnnotations <= 1, "Only one of @RecomputeFieldValue or @InjectAccessors can be used: %s", annotatedField);

        if (injectAccessorsAnnotation != null) {
            AnnotatedField result = new AnnotatedField(original, injectAccessorsAnnotation);
            fieldValueInterceptionSupport.registerFieldValueTransformer(original, null, new ValueNeverAvailableFieldValueTransformer(result));
            return result;
        }
        if (recomputeAnnotation == null && !original.isFinal()) {
            return original;
        }

        RecomputeFieldValue.Kind kind = RecomputeFieldValue.Kind.None;
        Class<?> targetClass = originalClass;
        String targetName = "";
        boolean isFinal = original.isFinal() && annotated.isFinal();

        if (recomputeAnnotation != null) {
            kind = recomputeAnnotation.kind();
            targetName = recomputeAnnotation.name();
            isFinal = recomputeAnnotation.isFinal();
            guarantee(!isFinal || (kind != RecomputeFieldValue.Kind.FieldOffset && kind != RecomputeFieldValue.Kind.TranslateFieldOffset && kind != RecomputeFieldValue.Kind.AtomicFieldUpdaterOffset),
                            "@%s with %s can never be final during analysis: unset isFinal in the annotation on %s",
                            RecomputeFieldValue.class.getSimpleName(), kind, annotated);
            if (recomputeAnnotation.declClass() != RecomputeFieldValue.class) {
                guarantee(recomputeAnnotation.declClassName().isEmpty(), "Both class and class name specified");
                targetClass = recomputeAnnotation.declClass();
            } else if (!recomputeAnnotation.declClassName().isEmpty()) {
                targetClass = imageClassLoader.findClassOrFail(recomputeAnnotation.declClassName());
            }
        }
        Class<?> transformedValueAllowedType = getTargetClass(annotatedField.getType());

        var newTransformer = switch (kind) {
            case None, Manual -> null;
            case Reset -> ConstantValueFieldValueTransformer.defaultValueForField(original);
            case NewInstance -> new NewInstanceOfFixedClassFieldValueTransformer(targetClass, false);
            case NewInstanceWhenNotNull -> new NewInstanceOfFixedClassFieldValueTransformer(targetClass, true);
            case FromAlias -> {
                if (!Modifier.isStatic(annotated.getModifiers())) {
                    throw UserError.abort("Cannot use " + kind + " on non-static alias " + annotated.format("%H.%n"));
                }
                yield new FromAliasFieldValueTransformer(annotated);
            }
            case FieldOffset -> {
                var targetField = getField(annotated, targetClass, targetName);
                unsafeAccessedFields.put(targetField, original);
                yield new FieldOffsetFieldValueTransformer(targetField, original.getType().getJavaKind());
            }
            case StaticFieldBase -> {
                var targetField = getField(annotated, targetClass, targetName);
                if (!Modifier.isStatic(targetField.getModifiers())) {
                    throw UserError.abort("Target field must be static for " + kind + " computation of alias " + annotated.format("%H.%n"));
                }
                yield new StaticFieldBaseFieldValueTransformer(targetField);
            }
            case ArrayBaseOffset ->
                new ArrayBaseOffsetFieldValueTransformer(targetClass, original.getType().getJavaKind());
            case ArrayIndexScale ->
                new ArrayIndexScaleFieldValueTransformer(targetClass, original.getType().getJavaKind());
            case ArrayIndexShift ->
                new ArrayIndexShiftFieldValueTransformer(targetClass, original.getType().getJavaKind());
            case AtomicFieldUpdaterOffset -> new AtomicFieldUpdaterOffsetFieldValueTransformer(original, targetClass);
            case TranslateFieldOffset -> new TranslateFieldOffsetFieldValueTransformer(original, targetClass);
            case Custom -> (FieldValueTransformer) ReflectionUtil.newInstance(targetClass);
        };

        if (newTransformer != null) {
            FieldValueTransformer existingTransformer = fieldValueInterceptionSupport.lookupAlreadyRegisteredTransformer(original);
            if (existingTransformer != null) {
                if (existingTransformer.equals(newTransformer)) {
                    /* Equivalent transformations are allowed, nothing to do. */
                } else {
                    throw UserError.abort("Field value recomputation %s conflicts with an already registered field value transformer.", annotated.format("%H.%n"));
                }
            } else {
                fieldValueInterceptionSupport.registerFieldValueTransformer(original, transformedValueAllowedType, newTransformer);
            }
        }

        return new AliasField(original, annotated, isFinal);
    }

    private static Field getField(ResolvedJavaField annotated, Class<?> targetClass, String targetName) {
        try {
            return ReflectionUtil.lookupField(targetClass, targetName);
        } catch (ReflectionUtilError e) {
            throw UserError.abort("Could not find target field %s.%s for alias %s.", targetClass.getName(), targetName, annotated == null ? null : annotated.format("%H.%n"));
        }
    }

    public Class<?> getTargetClass(Class<?> annotatedClass) {
        Class<?> annotatedBaseClass = annotatedClass;
        int arrayDepth = 0;
        while (annotatedBaseClass.isArray()) {
            arrayDepth++;
            annotatedBaseClass = annotatedBaseClass.getComponentType();
        }

        TargetClass targetClassAnnotation = lookupAnnotation(annotatedBaseClass, TargetClass.class);
        if (targetClassAnnotation == null) {
            /* No annotation found, so return unchanged argument. */
            return annotatedClass;
        }

        Class<?> targetClass = findTargetClass(annotatedBaseClass, targetClassAnnotation);
        for (int i = 0; i < arrayDepth; i++) {
            /*
             * The only way to look up the array class is to instantiate a dummy array and take the
             * class of the array.
             */
            targetClass = Array.newInstance(targetClass, 0).getClass();
        }
        return targetClass;
    }

    Class<?> findTargetClass(Class<?> annotatedBaseClass, TargetClass target) {
        return findTargetClass(annotatedBaseClass, target, true);
    }

    protected Class<?> findTargetClass(Class<?> annotatedBaseClass, TargetClass target, boolean checkOnlyWith) {
        return findTargetClass(TargetClass.class, TargetClass.NoClassNameProvider.class,
                        annotatedBaseClass, target, target.value(), target.className(), target.classNameProvider(), target.innerClass(), target.classLoader(),
                        checkOnlyWith ? target.onlyWith() : null);
    }

    protected <T> Class<?> findTargetClass(Class<T> targetClass, Class<?> noClassNameProviderClass,
                    Class<?> annotatedBaseClass, T target, Class<?> value, String targetClassName, Class<? extends Function<T, String>> classNameProvider, String[] innerClasses,
                    Class<? extends Supplier<ClassLoader>> classloaderSupplier, Class<?>[] onlyWith) {
        Class<?> holder;
        String className;
        ClassLoader suppliedLoader = null;
        if (value != targetClass) {
            guarantee(targetClassName.isEmpty(), "Both class and class name specified for substitution");
            guarantee(classNameProvider == noClassNameProviderClass, "Both class and classNameProvider specified for substitution");
            guarantee(classloaderSupplier == TargetClass.NoClassLoaderProvider.class, "Annotation attribute 'classLoader' requires use of 'className' or 'classNameProvider'");

            holder = value;
            className = holder.getName();
        } else {
            holder = null;
            if (classNameProvider != noClassNameProviderClass) {
                try {
                    className = ReflectionUtil.newInstance(classNameProvider).apply(target);
                } catch (ReflectionUtilError ex) {
                    throw UserError.abort(ex.getCause(), "Cannot instantiate classNameProvider: %s. The class must have a parameterless constructor.", classNameProvider.getTypeName());
                }
            } else {
                guarantee(!targetClassName.isEmpty(), "Neither class, className, nor classNameProvider specified for substitution");
                className = targetClassName;
            }
            if (classloaderSupplier != TargetClass.NoClassLoaderProvider.class) {
                try {
                    suppliedLoader = ReflectionUtil.newInstance(classloaderSupplier).get();
                } catch (ReflectionUtilError ex) {
                    throw UserError.abort(ex.getCause(), "Cannot instantiate classloaderSupplier: %s. The class must have a parameterless constructor.", classloaderSupplier.getTypeName());
                }
            }
        }
        if (onlyWith != null) {
            for (Class<?> onlyWithClass : onlyWith) {
                Object onlyWithProvider;
                try {
                    onlyWithProvider = ReflectionUtil.newInstance(onlyWithClass);
                } catch (ReflectionUtilError ex) {
                    throw UserError.abort(ex.getCause(), "Class specified as onlyWith for %s cannot be loaded or instantiated: %s", annotatedBaseClass.getTypeName(), onlyWithClass.getTypeName());
                }

                boolean onlyWithResult;
                if (onlyWithProvider instanceof BooleanSupplier) {
                    onlyWithResult = ((BooleanSupplier) onlyWithProvider).getAsBoolean();
                } else if (onlyWithProvider instanceof Predicate) {
                    @SuppressWarnings("unchecked")
                    Predicate<String> onlyWithPredicate = (Predicate<String>) onlyWithProvider;
                    onlyWithResult = onlyWithPredicate.test(className);
                } else {
                    throw UserError.abort("Class specified as onlyWith for %s does not implement %s or %s", annotatedBaseClass.getTypeName(),
                                    BooleanSupplier.class.getSimpleName(), Predicate.class.getSimpleName());
                }

                if (!onlyWithResult) {
                    return null;
                }
            }
        }

        if (holder == null) {
            var substitutionsClassLoaders = suppliedLoader != null ? List.of(suppliedLoader) : imageClassLoader.classLoaderSupport.getClassLoaders();
            for (ClassLoader substitutionsClassLoader : substitutionsClassLoaders) {
                try {
                    holder = Class.forName(className, false, substitutionsClassLoader);
                    break;
                } catch (ClassNotFoundException e) {
                    if (substitutionsClassLoader == substitutionsClassLoaders.getLast()) {
                        throw UserError.abort("Substitution target for %s is not loaded. Use field `onlyWith` in the `TargetClass` annotation to make substitution only active when needed.",
                                        annotatedBaseClass.getName());
                    }
                }
            }
        }
        if (innerClasses.length > 0) {
            for (String innerClass : innerClasses) {
                Class<?> prevHolder = holder;
                holder = findInnerClass(prevHolder, innerClass);
                if (holder == null) {
                    throw UserError.abort("Substitution target for %s is invalid as inner class %s in %s can not be found. Make sure that the inner class is present.",
                                    annotatedBaseClass.getName(), innerClass, prevHolder.getName());
                }
            }
        }

        return holder;
    }

    protected static Class<?> findInnerClass(Class<?> outerClass, String innerClassSimpleName) {
        for (Class<?> innerClass : outerClass.getDeclaredClasses()) {
            // Checkstyle: allow Class.getSimpleName
            String simpleName = innerClass.getSimpleName();
            // Checkstyle: disallow Class.getSimpleName
            if (simpleName.equals(innerClassSimpleName)) {
                return innerClass;
            }
        }
        return null;
    }

    private Class<?> interceptParameterType(Class<?> type) {
        TargetClass targetClassAnnotation = lookupAnnotation(type, TargetClass.class);
        if (targetClassAnnotation != null) {
            return findTargetClass(type, targetClassAnnotation);
        }

        if (type.isArray()) {
            Class<?> componentType = type.getComponentType();
            Class<?> componentRet = interceptParameterType(componentType);
            if (!componentRet.equals(componentType)) {
                Object tmp = Array.newInstance(componentRet, 0);
                return tmp.getClass();
            }
        }

        return type;
    }

    private Class<?>[] interceptParameterTypes(Class<?>[] types) {
        for (int i = 0; i < types.length; i++) {
            types[i] = interceptParameterType(types[i]);
        }
        return types;
    }

    protected <T extends Annotation> T lookupAnnotation(AnnotatedElement element, Class<T> annotationClass) {
        assert element instanceof Class || element instanceof Executable || element instanceof Field : element.getClass();
        return AnnotationAccess.getAnnotation(element, annotationClass);
    }

    protected static String deleteErrorMessage(AnnotatedElement element, Delete deleteAnnotation, boolean hosted) {
        return deleteErrorMessage(element, deleteAnnotation.value(), hosted);
    }

    public static String deleteErrorMessage(AnnotatedElement element, String message, boolean hosted) {
        StringBuilder result = new StringBuilder();
        result.append("Unsupported ");
        if (element instanceof ResolvedJavaField) {
            result.append("field ").append(((ResolvedJavaField) element).format("%H.%n"));
        } else if (element instanceof ResolvedJavaMethod) {
            ResolvedJavaMethod method = (ResolvedJavaMethod) element;
            result.append(method.isConstructor() ? "constructor " : "method ");
            result.append(method.format("%H.%n(%p)"));
        } else if (element instanceof ResolvedJavaType) {
            result.append("type ").append(((ResolvedJavaType) element).toJavaName(true));
        } else {
            throw VMError.shouldNotReachHere("Unknown @Delete annotated element " + element);
        }
        result.append(" is reachable");
        if (message != null && !message.isEmpty()) {
            result.append(": ").append(message);
        }
        if (hosted) {
            result.append(System.lineSeparator()).append("To diagnose the issue, you can add the option ").append(
                            SubstrateOptionsParser.commandArgument(NativeImageOptions.ReportUnsupportedElementsAtRuntime, "+")).append(
                                            ". The unsupported element is then reported at run time when it is accessed the first time.");
        }
        return result.toString();
    }
}

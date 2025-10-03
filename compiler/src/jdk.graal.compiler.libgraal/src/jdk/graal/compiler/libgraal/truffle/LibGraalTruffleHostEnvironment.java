/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.libgraal.truffle;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;

import com.oracle.truffle.compiler.HostMethodInfo;
import com.oracle.truffle.compiler.TruffleCompilable;
import com.oracle.truffle.compiler.TruffleCompilerRuntime;

import jdk.graal.compiler.annotation.AnnotationValue;
import jdk.graal.compiler.annotation.AnnotationValueSupport;
import jdk.graal.compiler.core.common.util.MethodKey;
import jdk.graal.compiler.hotspot.CompilationContext;
import jdk.graal.compiler.hotspot.HotSpotGraalServices;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.TruffleElementCache;
import jdk.graal.compiler.truffle.host.TruffleHostEnvironment;
import jdk.graal.compiler.truffle.host.TruffleKnownHostTypes;
import jdk.graal.compiler.truffle.hotspot.HotSpotTruffleCompilerImpl;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

final class LibGraalTruffleHostEnvironment extends TruffleHostEnvironment {

    private final HostMethodInfoCache hostCache = new HostMethodInfoCache();

    LibGraalTruffleHostEnvironment(TruffleCompilerRuntime runtime, MetaAccessProvider metaAccess) {
        super(runtime, metaAccess);
    }

    @Override
    public HostMethodInfo getHostMethodInfo(ResolvedJavaMethod method) {
        return hostCache.get(method);
    }

    @Override
    @SuppressWarnings("try")
    protected TruffleCompilerImpl createCompiler(TruffleCompilable ast) {
        try (CompilationContext compilationContext = HotSpotGraalServices.enterGlobalCompilationContext()) {
            HotSpotTruffleCompilerImpl compiler = HotSpotTruffleCompilerImpl.create(runtime(), LibGraalTruffleHostEnvironment::openTruffleRuntimeScopeImpl);
            compiler.initialize(ast, true);
            return compiler;
        }
    }

    /**
     * Opens a new {@code CanCallTruffleRuntimeScope} instance, enabling VM-to-Java calls for the
     * current compiler thread. This method should be used in conjunction with a try-with-resources
     * statement to ensure the scope is closed appropriately.
     */
    @Override
    public TruffleRuntimeScope openTruffleRuntimeScope() {
        return openTruffleRuntimeScopeImpl();
    }

    static TruffleRuntimeScope openTruffleRuntimeScopeImpl() {
        return TruffleRuntimeScopeImpl.CAN_CALL_JAVA_SCOPE != null ? new TruffleRuntimeScopeImpl() : null;
    }

    final class HostMethodInfoCache extends TruffleElementCache<ResolvedJavaMethod, HostMethodInfo> {

        HostMethodInfoCache() {
            super(HOST_METHOD_CACHE_SIZE); // cache size
        }

        @Override
        protected Object createKey(ResolvedJavaMethod method) {
            /*
             * On libgraal we cannot reference ResolvedJavaMethod as part of a cache as it may
             * become invalid between compilations.
             */
            return new MethodKey(method);
        }

        @Override
        protected HostMethodInfo computeValue(ResolvedJavaMethod method) {
            TruffleKnownHostTypes hostTypes = types();
            Map<ResolvedJavaType, AnnotationValue> annotations = AnnotationValueSupport.getDeclaredAnnotationValues(method);
            boolean isTruffleBoundary = annotations.containsKey(hostTypes.TruffleBoundary);
            boolean isBytecodeInterpreterSwitch = annotations.containsKey(hostTypes.BytecodeInterpreterSwitch);
            boolean isBytecodeInterpreterSwitchBoundary = annotations.containsKey(hostTypes.BytecodeInterpreterSwitchBoundary);
            boolean isInliningCutoff = annotations.containsKey(hostTypes.InliningCutoff);
            boolean isInliningRoot = annotations.containsKey(hostTypes.InliningRoot);
            return new HostMethodInfo(isTruffleBoundary, isBytecodeInterpreterSwitch, isBytecodeInterpreterSwitchBoundary, isInliningCutoff, isInliningRoot);
        }

    }

    private static final class TruffleRuntimeScopeImpl implements TruffleRuntimeScope {

        private static final MethodHandle CAN_CALL_JAVA_SCOPE = findCompilerThreadCanCallJavaScopeConstructor();

        @SuppressWarnings("unchecked")
        private static MethodHandle findCompilerThreadCanCallJavaScopeConstructor() {
            try {
                return MethodHandles.lookup().findConstructor(Class.forName("jdk.vm.ci.hotspot.CompilerThreadCanCallJavaScope"), MethodType.methodType(void.class, boolean.class));
            } catch (ReflectiveOperationException e) {
                throw new InternalError(e);
            }
        }

        private final AutoCloseable impl;

        TruffleRuntimeScopeImpl() {
            try {
                impl = (AutoCloseable) CAN_CALL_JAVA_SCOPE.invoke(true);
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }

        @Override
        public void close() {
            try {
                impl.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}

/*
 * Copyright (c) 2015, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.thread;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.Isolates;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateOptions.ConcealedOptions;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.collections.RingBuffer;
import com.oracle.svm.core.feature.AutomaticallyRegisteredImageSingleton;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.RestrictHeapAccess;
import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.locks.VMCondition;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.core.thread.VMThreads.SafepointBehavior;
import com.oracle.svm.core.thread.VMThreads.StatusSupport;
import com.oracle.svm.core.util.TimeUtils;
import com.oracle.svm.core.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.word.Word;

/**
 * Only one thread at a time can execute {@linkplain VMOperation}s. The execution order of VM
 * operations is not defined (the only exception are recursive VM operations, see below).
 * <p>
 * At the moment, we support the following processing modes:
 * <ul>
 * <li>Temporary VM operation threads: by default VM operations are executed by the application
 * thread that queued the VM operation. For the time of the execution, the application thread holds
 * a lock to guarantee that it is the single temporary VM operation thread.</li>
 * <li>Dedicated VM operation thread: if {@linkplain ConcealedOptions#UseDedicatedVMOperationThread}
 * is enabled, a dedicated VM operation thread is spawned during isolate startup and used for the
 * execution of all VM operations.</li>
 * </ul>
 * <p>
 * It is possible that the execution of a VM operation triggers another VM operation explicitly or
 * implicitly (e.g. a GC). Such recursive VM operations are executed immediately (see
 * {@link #immediateQueues}).
 * <p>
 * If a VM operation was queued successfully, it is guaranteed that the VM operation will get
 * executed at some point in time. This is crucial for {@linkplain NativeVMOperation}s as their
 * mutable state (see {@linkplain NativeVMOperationData}) could be allocated on the stack.
 * <p>
 * To avoid unexpected exceptions, we do the following before queuing and executing a VM operation:
 * <ul>
 * <li>We make the yellow zone of the stack accessible. This avoids {@linkplain StackOverflowError}s
 * (especially if no dedicated VM operation thread is used).</li>
 * <li>We pause recurring callbacks because they can execute arbitrary Java code that can throw
 * exceptions.</li>
 * </ul>
 */
@AutomaticallyRegisteredImageSingleton
public final class VMOperationControl {
    private final VMOperationThread dedicatedVMOperationThread;
    private final WorkQueues mainQueues;
    private final WorkQueues immediateQueues;
    private final OpInProgress inProgress;
    private final VMOpHistory history;

    @Platforms(Platform.HOSTED_ONLY.class)
    VMOperationControl() {
        this.dedicatedVMOperationThread = new VMOperationThread();
        this.mainQueues = new WorkQueues("main", true);
        this.immediateQueues = new WorkQueues("immediate", false);
        this.inProgress = new OpInProgress();
        this.history = new VMOpHistory();
    }

    @Fold
    static VMOperationControl get() {
        return ImageSingletons.lookup(VMOperationControl.class);
    }

    @Fold
    public static boolean useDedicatedVMOperationThread() {
        return SubstrateOptions.AllowVMInternalThreads.getValue() && ConcealedOptions.UseDedicatedVMOperationThread.getValue();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static VMOperationThread getDedicatedVMOperationThread() {
        VMOperationControl control = get();
        assert control.dedicatedVMOperationThread != null;
        return control.dedicatedVMOperationThread;
    }

    public static void startVMOperationThread() {
        assert useDedicatedVMOperationThread();

        VMOperationControl control = get();
        assert control.mainQueues.isEmpty();

        control.dedicatedVMOperationThread.start();
        control.dedicatedVMOperationThread.waitUntilStarted();
    }

    @Uninterruptible(reason = "Executed during teardown after VMThreads#threadExit")
    public static void shutdownAndDetachVMOperationThread() {
        assert useDedicatedVMOperationThread();

        int size = SizeOf.get(NativeVMOperationData.class);
        NativeVMOperationData data = StackValue.get(size);
        UnmanagedMemoryUtil.fill((Pointer) data, Word.unsigned(size), (byte) 0);
        NativeStopVMOperationThread operation = get().stopVMOperationThreadOperation;
        data.setNativeVMOperation(operation);
        /*
         * Note we don't call enqueueFromNonJavaThread b/c this thread still has characteristics of
         * a Java thread even though VMThreads#threadExit has already been called.
         */
        get().mainQueues.enqueueUninterruptibly(operation, data);

        VMThreads.waitInNativeUntilDetached(get().dedicatedVMOperationThread.getIsolateThread());
        assert get().mainQueues.isEmpty();
        assert VMThreads.firstThread().isNull() : "the VM operation thread must detach last";
    }

    private final NativeStopVMOperationThread stopVMOperationThreadOperation = new NativeStopVMOperationThread();

    private static class NativeStopVMOperationThread extends NativeVMOperation {
        NativeStopVMOperationThread() {
            super(VMOperationInfos.get(NativeStopVMOperationThread.class, "Stop VM operation thread", VMOperation.SystemEffect.NONE));
        }

        @Override
        @Uninterruptible(reason = "Executed during teardown after VMThreads#exit")
        protected void operate(NativeVMOperationData data) {
            VMOperationControl.get().dedicatedVMOperationThread.stopped = true;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isDedicatedVMOperationThread() {
        return isDedicatedVMOperationThread(CurrentIsolate.getCurrentThread());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isDedicatedVMOperationThread(IsolateThread thread) {
        if (!useDedicatedVMOperationThread()) {
            return false;
        }
        return thread == get().dedicatedVMOperationThread.getIsolateThread();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean mayExecuteVmOperations() {
        if (useDedicatedVMOperationThread()) {
            return isDedicatedVMOperationThread();
        } else {
            return get().mainQueues.mutex.isOwner();
        }
    }

    public static void printCurrentVMOperation(Log log, boolean allowJavaHeapAccess) {
        /*
         * All reads in this method are racy as the currently executed VM operation could finish and
         * a different VM operation could start. So, the read data is not necessarily consistent.
         */
        VMOperationControl control = get();
        VMOperation op = control.inProgress.operation;
        if (op == null) {
            log.string("No VMOperation in progress").newline();
        } else if (allowJavaHeapAccess) {
            log.string("VMOperation in progress: ").string(op.getName()).indent(true);
            log.string("Safepoint: ").bool(op.getCausesSafepoint()).newline();
            log.string("QueuingThread: ").zhex(control.inProgress.queueingThread).newline();
            log.string("ExecutingThread: ").zhex(control.inProgress.executingThread).newline();
            log.redent(false);
        } else {
            log.string("VMOperation in progress: ").zhex(Word.objectToUntrackedPointer(op)).newline();
        }
    }

    public static void printRecentEvents(Log log, boolean allowJavaHeapAccess) {
        get().history.print(log, allowJavaHeapAccess);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    OpInProgress getInProgress() {
        return inProgress;
    }

    @Uninterruptible(reason = "Set the current VM operation as atomically as possible - this is mainly relevant for deopt test cases")
    void setInProgress(VMOperation operation, IsolateThread queueingThread, IsolateThread executingThread, boolean started) {
        assert operation != null && executingThread.isNonNull() || operation == null && queueingThread.isNull() && executingThread.isNull() && !started;

        if (started) {
            history.add(VMOpStatus.Started, operation, queueingThread, executingThread, inProgress.nestingLevel);
            inProgress.nestingLevel++;
        } else {
            if (inProgress.operation != null) {
                inProgress.nestingLevel--;
                history.add(VMOpStatus.Finished, inProgress.operation, inProgress.queueingThread, inProgress.executingThread, inProgress.nestingLevel);
            }
        }

        inProgress.executingThread = executingThread;
        inProgress.operation = operation;
        inProgress.queueingThread = queueingThread;

        VMOperationListenerSupport.get().vmOperationChanged(operation);
    }

    void enqueue(JavaVMOperation operation) {
        enqueue(operation, Word.nullPointer());
    }

    void enqueue(NativeVMOperationData data) {
        assert data.getNativeVMOperation() != null;
        enqueue(data.getNativeVMOperation(), data);
    }

    @Uninterruptible(reason = "Called from a non-Java thread.")
    void enqueueFromNonJavaThread(NativeVMOperationData data) {
        enqueueFromNonJavaThread(data.getNativeVMOperation(), data);
    }

    /**
     * Enqueues a {@link VMOperation} and returns as soon as the operation was executed.
     */
    private void enqueue(VMOperation operation, NativeVMOperationData data) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        try {
            VMError.guarantee(!SafepointBehavior.ignoresSafepoints(), "could cause deadlocks otherwise");
            log().string("[VMOperationControl.enqueue:").string("  operation: ").string(operation.getName());
            if (mayExecuteVmOperations()) {
                // a recursive VM operation (either triggered implicitly or explicitly) -> execute
                // it right away
                immediateQueues.enqueueAndExecute(operation, data);
            } else if (useDedicatedVMOperationThread()) {
                // a thread queues an operation that the VM operation thread will execute
                assert !isDedicatedVMOperationThread() : "the dedicated VM operation thread must execute and not queue VM operations";
                assert dedicatedVMOperationThread.isRunning() : "must not queue VM operations before the VM operation thread is started or after it is shut down";
                VMThreads.THREAD_MUTEX.guaranteeNotOwner("could result in deadlocks otherwise");
                mainQueues.enqueueAndWait(operation, data);
            } else {
                // use the current thread to execute the operation under a lock
                VMThreads.THREAD_MUTEX.guaranteeNotOwner("could result in deadlocks otherwise");
                mainQueues.enqueueAndExecute(operation, data);
            }
            assert operation.isFinished(data);
            log().string("]").newline();
        } finally {
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    @Uninterruptible(reason = "Called from a non-Java thread.")
    public void enqueueFromNonJavaThread(NativeVMOperation operation, NativeVMOperationData data) {
        assert useDedicatedVMOperationThread();
        assert CurrentIsolate.getCurrentThread().isNull() || StatusSupport.isStatusNativeOrSafepoint() : StatusSupport.getStatusString(CurrentIsolate.getCurrentThread());
        assert dedicatedVMOperationThread.isRunning() : "must not queue VM operations before the VM operation thread is started or after it is shut down";

        mainQueues.enqueueUninterruptibly(operation, data);
    }

    private static void markAsFinished(VMOperation operation, NativeVMOperationData data, VMCondition operationFinished) {
        operation.markAsFinished(data);
        if (operationFinished != null) {
            operationFinished.broadcast();
        }
    }

    /** Check if it is okay for this thread to block. */
    public static void guaranteeOkayToBlock(String message) {
        /*-
         * No Java synchronization must be performed within a VMOperation. Otherwise, one of the
         * following deadlocks could occur:
         *
         * a.
         * - Thread A locks an object
         * - Thread B executes a VMOperation that needs a safepoint.
         * - Thread B tries to lock the same object and is blocked.
         *
         * b.
         * - Thread A locks an object
         * - Thread B executes a VMOperation that does NOT need a safepoint.
         * - Thread B tries to lock the same object and is blocked.
         * - Thread A allocates an object while still holding the lock. The allocation would need
         * to execute a GC but the VM thread is blocked.
         */
        VMOperationControl control = VMOperationControl.get();
        OpInProgress opInProgress = control.getInProgress();
        if (VMOperation.isInProgress(opInProgress)) {
            Log.log().string(message).newline();
            VMError.shouldNotReachHere("Should not reach here: Not okay to block.");
        }
    }

    private static Log log() {
        return SubstrateOptions.TraceVMOperations.getValue() ? Log.log() : Log.noopLog();
    }

    /**
     * A dedicated thread that executes {@link VMOperation}s. If the option
     * {@link ConcealedOptions#UseDedicatedVMOperationThread} is enabled, then this thread is the
     * only one that may initiate a safepoint. Therefore, it never gets blocked at a safepoint.
     */
    public static class VMOperationThread implements Runnable {
        private final Thread thread;
        private volatile IsolateThread isolateThread;
        private boolean stopped;

        @Platforms(Platform.HOSTED_ONLY.class)
        VMOperationThread() {
            thread = new Thread(this, "VMOperationThread");
            thread.setDaemon(true);
        }

        public void start() {
            thread.start();
        }

        @NeverInline("Must not have escape analysis move an allocation into this method")
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while acquiring / holding lock")
        @Override
        public void run() {
            RecurringCallbackSupport.suspendCallbackTimer("VM operation thread must not execute recurring callbacks.");
            this.isolateThread = CurrentIsolate.getCurrentThread();

            VMOperationControl control = VMOperationControl.get();
            WorkQueues queues = control.mainQueues;

            queues.mutex.lock();
            try {
                while (!stopped) {
                    try {
                        queues.waitForWorkAndExecute();
                    } catch (Throwable e) {
                        log().string("[VMOperation.execute caught: ").string(e.getClass().getName()).string("]").newline();
                        throw VMError.shouldNotReachHere(e);
                    }
                }
            } finally {
                queues.mutex.unlock();
                stopped = true;
            }

            /*
             * When this method returns, some more code is executed before the execution really
             * finishes and the thread exits. Therefore, we don't null out the isolateThread because
             * it is possible that the current thread still needs to execute a VM operation.
             */
        }

        public void waitUntilStarted() {
            while (isolateThread.isNull()) {
                Thread.yield();
            }
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public IsolateThread getIsolateThread() {
            return isolateThread;
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public boolean isRunning() {
            return PlatformThreads.isAlive(thread);
        }
    }

    private static final class WorkQueues {
        private final NativeVMOperationQueue nativeNonSafepointOperations;
        private final NativeVMOperationQueue nativeSafepointOperations;
        private final JavaVMOperationQueue javaNonSafepointOperations;
        private final JavaVMOperationQueue javaSafepointOperations;

        /**
         * This mutex is used by the application threads and by the VM operation thread. Java
         * threads may only use normal lock operations with a full transition here. This restriction
         * is necessary to ensure that a VM operation that needs a safepoint can really bring all
         * other threads to a halt, even if those other threads also want to queue VM operations in
         * the meanwhile.
         */
        final VMMutex mutex;
        private final VMCondition operationQueued;
        private final VMCondition operationFinished;

        @Platforms(Platform.HOSTED_ONLY.class)
        WorkQueues(String prefix, boolean needsLocking) {
            this.nativeNonSafepointOperations = new NativeVMOperationQueue(prefix + "NativeNonSafepointOperations");
            this.nativeSafepointOperations = new NativeVMOperationQueue(prefix + "NativeSafepointOperations");
            this.javaNonSafepointOperations = new JavaVMOperationQueue(prefix + "JavaNonSafepointOperations");
            this.javaSafepointOperations = new JavaVMOperationQueue(prefix + "JavaSafepointOperations");
            this.mutex = createMutex(prefix + "VMOperationControlWorkQueue", needsLocking);
            this.operationQueued = createCondition();
            this.operationFinished = createCondition();
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        boolean isEmpty() {
            return nativeNonSafepointOperations.isEmpty() && nativeSafepointOperations.isEmpty() && javaNonSafepointOperations.isEmpty() && javaSafepointOperations.isEmpty();
        }

        void waitForWorkAndExecute() {
            assert isDedicatedVMOperationThread();
            assert mutex != null;
            mutex.guaranteeIsOwner("Must already be locked.");

            while (isEmpty()) {
                operationQueued.block();
            }
            /*
             * The VM operation queue can contain any number of VM operations, even though we do a
             * rather strict locking at the moment. This is because of the following reason: if
             * thread A queues a VM operation and notifies the VM thread that an operation was
             * queued, thread B can queue a second VM operation before the VM operation thread
             * reacts on the notification of thread A.
             */
            executeAllQueuedVMOperations();
        }

        @NeverInline("Must not have escape analysis move an allocation into this method")
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while acquiring / holding lock")
        void enqueueAndWait(VMOperation operation, NativeVMOperationData data) {
            assert useDedicatedVMOperationThread();
            lock();
            try {
                enqueue(operation, data);
                operationQueued.broadcast();
                while (!operation.isFinished(data)) {
                    operationFinished.block();
                }
            } finally {
                unlock();
            }
        }

        @Uninterruptible(reason = "Called from a non-Java thread.")
        void enqueueUninterruptibly(NativeVMOperation operation, NativeVMOperationData data) {
            mutex.lockNoTransitionUnspecifiedOwner();
            try {
                enqueue(operation, data);
                operationQueued.broadcast();
                // do not wait for the VM operation
            } finally {
                mutex.unlockNoTransitionUnspecifiedOwner();
            }
        }

        @NeverInline("Must not have escape analysis move an allocation into this method")
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while acquiring / holding lock")
        void enqueueAndExecute(VMOperation operation, NativeVMOperationData data) {
            lock();
            try {
                enqueue(operation, data);
                executeAllQueuedVMOperations();
            } finally {
                assert isEmpty() : "all queued VM operations must have been processed";
                unlock();
            }
        }

        private void enqueue(VMOperation operation, NativeVMOperationData data) {
            if (operation instanceof JavaVMOperation) {
                enqueue((JavaVMOperation) operation, data);
            } else if (operation instanceof NativeVMOperation) {
                enqueue((NativeVMOperation) operation, data);
            } else {
                VMError.shouldNotReachHereUnexpectedInput(operation); // ExcludeFromJacocoGeneratedReport
            }
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private void enqueue(NativeVMOperation operation, NativeVMOperationData data) {
            assert operation == data.getNativeVMOperation();
            operation.markAsQueued(data);
            if (operation.getCausesSafepoint()) {
                nativeSafepointOperations.push(data);
            } else {
                nativeNonSafepointOperations.push(data);
            }
        }

        private void enqueue(JavaVMOperation operation, NativeVMOperationData data) {
            operation.markAsQueued(data);
            if (operation.getCausesSafepoint()) {
                javaSafepointOperations.push(operation);
            } else {
                javaNonSafepointOperations.push(operation);
            }
        }

        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Allocating could result in unexpected recursive VM operations.")
        private void executeAllQueuedVMOperations() {
            assertIsLocked();

            // Drain the non-safepoint queues.
            drain(nativeNonSafepointOperations);
            drain(javaNonSafepointOperations);

            // Filter operations that need a safepoint but don't have any work to do.
            filterUnnecessary(nativeSafepointOperations);
            filterUnnecessary(javaSafepointOperations);

            // Drain the safepoint queues.
            if (!nativeSafepointOperations.isEmpty() || !javaSafepointOperations.isEmpty()) {
                String safepointReason = null;
                boolean startedSafepoint = false;
                boolean lockedForSafepoint = false;

                Safepoint safepoint = Safepoint.singleton();
                if (!safepoint.isInProgress()) {
                    startedSafepoint = true;
                    safepointReason = getSafepointReason(nativeSafepointOperations, javaSafepointOperations);
                    lockedForSafepoint = safepoint.startSafepoint(safepointReason);
                }

                try {
                    drain(nativeSafepointOperations);
                    drain(javaSafepointOperations);
                } finally {
                    if (startedSafepoint) {
                        safepoint.endSafepoint(lockedForSafepoint);
                    }
                }
            }
        }

        private static String getSafepointReason(NativeVMOperationQueue nativeSafepointOperations, JavaVMOperationQueue javaSafepointOperations) {
            NativeVMOperationData data = nativeSafepointOperations.peek();
            if (data.isNonNull()) {
                return data.getNativeVMOperation().getName();
            } else {
                VMOperation op = javaSafepointOperations.peek();
                assert op != null;
                return op.getName();
            }
        }

        private void drain(NativeVMOperationQueue workQueue) {
            assertIsLocked();
            if (!workQueue.isEmpty()) {
                Log trace = log();
                trace.string("[Worklist.drain:  queue: ").string(workQueue.name);
                while (!workQueue.isEmpty()) {
                    NativeVMOperationData data = workQueue.pop();
                    VMOperation operation = data.getNativeVMOperation();
                    try {
                        operation.execute(data);
                    } finally {
                        markAsFinished(operation, data, operationFinished);
                    }
                }
                trace.string("]").newline();
            }
        }

        private void drain(JavaVMOperationQueue workQueue) {
            assertIsLocked();
            if (!workQueue.isEmpty()) {
                Log trace = log();
                trace.string("[Worklist.drain:  queue: ").string(workQueue.name);
                while (!workQueue.isEmpty()) {
                    JavaVMOperation operation = workQueue.pop();
                    try {
                        operation.execute(Word.nullPointer());
                    } finally {
                        markAsFinished(operation, Word.nullPointer(), operationFinished);
                    }
                }
                trace.string("]").newline();
            }
        }

        private void filterUnnecessary(JavaVMOperationQueue workQueue) {
            Log trace = log();
            JavaVMOperation prev = null;
            JavaVMOperation op = workQueue.peek();
            while (op != null) {
                JavaVMOperation next = op.getNext();
                if (!op.hasWork(Word.nullPointer())) {
                    trace.string("[Skipping unnecessary operation in queue ").string(workQueue.name).string(": ").string(op.getName());
                    workQueue.remove(prev, op);
                    markAsFinished(op, Word.nullPointer(), operationFinished);
                } else {
                    prev = op;
                }
                op = next;
            }
        }

        private void filterUnnecessary(NativeVMOperationQueue workQueue) {
            Log trace = log();
            NativeVMOperationData prev = Word.nullPointer();
            NativeVMOperationData data = workQueue.peek();
            while (data.isNonNull()) {
                NativeVMOperation op = data.getNativeVMOperation();
                NativeVMOperationData next = data.getNext();
                if (!op.hasWork(data)) {
                    trace.string("[Skipping unnecessary operation in queue ").string(workQueue.name).string(": ").string(op.getName());
                    workQueue.remove(prev, data);
                    markAsFinished(op, data, operationFinished);
                } else {
                    prev = data;
                }
                data = next;
            }
        }

        private void lock() {
            if (mutex != null) {
                mutex.lock();
            }
        }

        private void unlock() {
            if (mutex != null) {
                mutex.unlock();
            }
        }

        private void assertIsLocked() {
            if (mutex != null) {
                assert mutex.isOwner() : "must be locked";
            }
        }

        @Platforms(value = Platform.HOSTED_ONLY.class)
        private static VMMutex createMutex(String mutexName, boolean needsLocking) {
            if (needsLocking) {
                return new VMMutex(mutexName);
            }
            return null;
        }

        @Platforms(value = Platform.HOSTED_ONLY.class)
        private VMCondition createCondition() {
            if (mutex != null && useDedicatedVMOperationThread()) {
                return new VMCondition(mutex);
            }
            return null;
        }
    }

    protected abstract static class AllocationFreeQueue<T> {
        final String name;

        AllocationFreeQueue(String name) {
            this.name = name;
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        abstract boolean isEmpty();

        abstract void push(T element);

        abstract T pop();

        abstract T peek();

        abstract void remove(T prev, T remove);
    }

    /**
     * A queue that does not allocate because each element has a next pointer. This queue is
     * <em>not</em> multi-thread safe.
     */
    protected abstract static class JavaAllocationFreeQueue<T extends JavaAllocationFreeQueue.Element<T>> extends AllocationFreeQueue<T> {
        private T head;
        private T tail; // can point to an incorrect value if head is null

        JavaAllocationFreeQueue(String name) {
            super(name);
        }

        @Override
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public boolean isEmpty() {
            return head == null;
        }

        @Override
        public void push(T element) {
            assert element.getNext() == null : "must not already be queued";
            if (head == null) {
                head = element;
            } else {
                tail.setNext(element);
            }
            tail = element;
        }

        @Override
        public T pop() {
            if (head == null) {
                return null;
            }
            T resultElement = head;
            head = resultElement.getNext();
            resultElement.setNext(null);
            return resultElement;
        }

        @Override
        public T peek() {
            return head;
        }

        @Override
        void remove(T prev, T remove) {
            if (prev == null) {
                assert head == remove;
                head = remove.getNext();
                remove.setNext(null);
            } else {
                prev.setNext(remove.getNext());
            }
        }

        /** An element for an allocation-free queue. An element can be in at most one queue. */
        public interface Element<T extends Element<T>> {
            T getNext();

            void setNext(T newNext);
        }
    }

    protected static class JavaVMOperationQueue extends JavaAllocationFreeQueue<JavaVMOperation> {
        JavaVMOperationQueue(String name) {
            super(name);
        }
    }

    /**
     * Same implementation as {@link JavaAllocationFreeQueue} but for elements of type
     * {@link NativeVMOperationData}. We can't reuse the other implementation because we need to use
     * the semantics of {@link Word}.
     */
    protected static class NativeVMOperationQueue extends AllocationFreeQueue<NativeVMOperationData> {
        private NativeVMOperationData head;
        private NativeVMOperationData tail; // can point to an incorrect value if head is null

        NativeVMOperationQueue(String name) {
            super(name);
        }

        @Override
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public boolean isEmpty() {
            return head.isNull();
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        @Override
        public void push(NativeVMOperationData element) {
            assert element.getNext().isNull() : "must not already be queued";
            if (head.isNull()) {
                head = element;
            } else {
                tail.setNext(element);
            }
            tail = element;
        }

        @Override
        public NativeVMOperationData pop() {
            if (head.isNull()) {
                return Word.nullPointer();
            }
            NativeVMOperationData resultElement = head;
            head = resultElement.getNext();
            resultElement.setNext(Word.nullPointer());
            return resultElement;
        }

        @Override
        public NativeVMOperationData peek() {
            return head;
        }

        @Override
        void remove(NativeVMOperationData prev, NativeVMOperationData remove) {
            if (prev.isNull()) {
                assert head == remove;
                head = remove.getNext();
                remove.setNext(Word.nullPointer());
            } else {
                prev.setNext(remove.getNext());
            }
        }
    }

    /**
     * This class holds the information about the {@link VMOperation} that is currently in progress.
     * We use this class to cache all values that another thread might want to query as we must not
     * access the {@link NativeVMOperationData} from another thread (it is allocated in native
     * memory that can be freed when the operation finishes).
     */
    protected static class OpInProgress {
        VMOperation operation;
        IsolateThread queueingThread;
        IsolateThread executingThread;
        int nestingLevel;

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public VMOperation getOperation() {
            return operation;
        }

        public IsolateThread getQueuingThread() {
            return queueingThread;
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public IsolateThread getExecutingThread() {
            return executingThread;
        }
    }

    /**
     * This code is used for printing a VM operation history when printing diagnostics. As other
     * threads may still execute VM operations and as we don't want to use any locks (we don't know
     * the state the VM is in when printing diagnostics), all the logic in here is racy by design.
     * It can happen that the races cause an inconsistent output but the races must not result in
     * any crashes.
     */
    private static class VMOpHistory {
        private final RingBuffer<VMOpStatusChange> history;
        private static final RingBuffer.Consumer<VMOpStatusChange> PRINT_WITH_JAVA_HEAP_DATA = VMOpHistory::printEntryWithJavaHeapData;
        private static final RingBuffer.Consumer<VMOpStatusChange> PRINT_WITHOUT_JAVA_HEAP_DATA = VMOpHistory::printEntryWithoutJavaHeapData;

        @Platforms(Platform.HOSTED_ONLY.class)
        VMOpHistory() {
            history = new RingBuffer<>(SubstrateOptions.DiagnosticBufferSize.getValue(), VMOpStatusChange::new);
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public void add(VMOpStatus status, VMOperation operation, IsolateThread queueingThread, IsolateThread executingThread, int nestingLevel) {
            assert Heap.getHeap().isInImageHeap(status);
            VMOpStatusChange entry = history.next();
            entry.uptimeMillis = Isolates.getUptimeMillis();
            entry.status = status;
            entry.name = operation.getName();
            entry.causesSafepoint = operation.getCausesSafepoint();
            entry.queueingThread = queueingThread;
            entry.executingThread = executingThread;
            entry.nestingLevel = nestingLevel;
            entry.safepointId = Safepoint.singleton().getSafepointId();
        }

        public void print(Log log, boolean allowJavaHeapAccess) {
            log.string("The ").signed(history.size()).string(" most recent VM operation status changes:").indent(true);
            history.foreach(log, allowJavaHeapAccess ? PRINT_WITH_JAVA_HEAP_DATA : PRINT_WITHOUT_JAVA_HEAP_DATA);
            log.indent(false);
        }

        private static void printEntryWithJavaHeapData(Object context, VMOpStatusChange entry) {
            printEntry(context, entry, true);
        }

        private static void printEntryWithoutJavaHeapData(Object context, VMOpStatusChange entry) {
            printEntry(context, entry, false);
        }

        private static void printEntry(Object context, VMOpStatusChange entry, boolean allowJavaHeapAccess) {
            Log log = (Log) context;
            entry.print(log, allowJavaHeapAccess);
        }
    }

    private enum VMOpStatus {
        Started,
        Continued,
        Finished
    }

    /**
     * Holds information about a VM operation status change. We must not store any reference to the
     * {@link VMOperation} as this could be a memory leak (some VM operations may reference large
     * data structures).
     */
    private static class VMOpStatusChange {
        long uptimeMillis;
        VMOpStatus status;
        String name;
        boolean causesSafepoint;
        IsolateThread queueingThread;
        IsolateThread executingThread;
        int nestingLevel;
        UnsignedWord safepointId;

        @Platforms(Platform.HOSTED_ONLY.class)
        VMOpStatusChange() {
        }

        void print(Log log, boolean allowJavaHeapAccess) {
            VMOpStatus localStatus = status;
            if (localStatus != null) {
                log.rational(uptimeMillis, TimeUtils.millisPerSecond, 3).string("s - ").spaces(nestingLevel * 2).string(localStatus.name());
                if (allowJavaHeapAccess) {
                    log.string(" ").string(name);
                }
                log.string(" (safepoint: ").bool(causesSafepoint)
                                .string(", queueingThread: ").zhex(queueingThread)
                                .string(", executingThread: ").zhex(executingThread)
                                .string(", safepointId: ").unsigned(safepointId)
                                .string(")").newline();
            }
        }
    }
}

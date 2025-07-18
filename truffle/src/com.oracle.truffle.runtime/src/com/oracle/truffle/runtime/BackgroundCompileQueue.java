/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.runtime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The compilation queue accepts compilation requests, and schedules compilations.
 *
 * The current queuing policy is to first schedule all the first tier compilation requests, and only
 * handle second tier compilation requests when there are no first tier compilations left. Between
 * the compilation requests of the same optimization tier, the queuing policy is FIFO
 * (first-in-first-out).
 *
 * Note that all the compilation requests are second tier when the multi-tier option is turned off.
 */
public class BackgroundCompileQueue {

    protected final OptimizedTruffleRuntime runtime;
    private final AtomicLong idCounter;
    private volatile ThreadPoolExecutor compilationExecutorService;
    private volatile BlockingQueue<Runnable> compilationQueue;
    private boolean shutdown = false;
    private long delayMillis;

    public BackgroundCompileQueue(OptimizedTruffleRuntime runtime) {
        this.runtime = runtime;
        this.idCounter = new AtomicLong();
    }

    // Largest i such that 2^i <= n.
    private static int log2(int n) {
        assert n > 0;
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    private ExecutorService getExecutorService(OptimizedCallTarget callTarget) {
        ExecutorService service = this.compilationExecutorService;
        if (service != null) {
            return service;
        }
        synchronized (this) {
            service = this.compilationExecutorService;
            if (service != null) {
                return service;
            }
            if (shutdown) {
                throw new RejectedExecutionException("The BackgroundCompileQueue is shutdown");
            }

            // NOTE: The value from the first Engine compiling wins for now
            this.delayMillis = callTarget.getOptionValue(OptimizedRuntimeOptions.EncodedGraphCachePurgeDelay);

            // NOTE: the value from the first Engine compiling wins for now
            int threads = callTarget.getOptionValue(OptimizedRuntimeOptions.CompilerThreads);
            if (threads == 0) {
                // Old behavior, use either 1 or 2 compiler threads.
                int availableProcessors = Runtime.getRuntime().availableProcessors();
                if (availableProcessors >= 4) {
                    threads = 2;
                }
            } else if (threads < 0) {
                // Scale compiler threads depending on how many processors are available.
                int availableProcessors = Runtime.getRuntime().availableProcessors();

                // @formatter:off
                // compilerThreads = Math.min(availableProcessors / 4 + loglogCPU)
                // Produces reasonable values for common core/thread counts (with HotSpot numbers for reference):
                // cores=2  threads=4  compilerThreads=2  (HotSpot=3:  C1=1 C2=2)
                // cores=4  threads=8  compilerThreads=3  (HotSpot=4:  C1=1 C2=3)
                // cores=6  threads=12 compilerThreads=4  (HotSpot=4:  C1=1 C2=3)
                // cores=8  threads=16 compilerThreads=6  (HotSpot=12: C1=4 C2=8)
                // cores=10 threads=20 compilerThreads=7  (HotSpot=12: C1=4 C2=8)
                // cores=12 threads=24 compilerThreads=8  (HotSpot=12: C1=4 C2=8)
                // cores=16 threads=32 compilerThreads=10 (HotSpot=15: C1=5 C2=10)
                // cores=18 threads=36 compilerThreads=11 (HotSpot=15: C1=5 C2=10)
                // cores=24 threads=48 compilerThreads=14 (HotSpot=15: C1=5 C2=10)
                // cores=28 threads=56 compilerThreads=16 (HotSpot=15: C1=5 C2=10)
                // cores=32 threads=64 compilerThreads=18 (HotSpot=18: C1=6 C2=12)
                // cores=36 threads=72 compilerThreads=20 (HotSpot=18: C1=6 C2=12)
                // @formatter:on
                int logCPU = log2(availableProcessors);
                int loglogCPU = log2(Math.max(logCPU, 1));
                threads = Math.min(availableProcessors / 4 + loglogCPU, 16); // capped at 16
            }
            threads = Math.max(1, threads);

            ThreadFactory factory = newThreadFactory("TruffleCompilerThread", callTarget);

            long compilerIdleDelay = runtime.getCompilerIdleDelay(callTarget);
            long keepAliveTime = compilerIdleDelay >= 0 ? compilerIdleDelay : 0;

            this.compilationQueue = createQueue(callTarget, threads);
            ThreadPoolExecutor threadPoolExecutor = new TruffleThreadPoolExecutor(threads, threads,
                            keepAliveTime, TimeUnit.MILLISECONDS,
                            compilationQueue, factory);

            if (compilerIdleDelay > 0) {
                // There are two mechanisms to signal idleness: if core threads can timeout, then
                // the notification is triggered by TruffleCompilerThreadFactory,
                // otherwise, via IdlingBlockingQueue.take.
                threadPoolExecutor.allowCoreThreadTimeOut(true);
            }

            return compilationExecutorService = threadPoolExecutor;
        }
    }

    private BlockingQueue<Runnable> createQueue(OptimizedCallTarget callTarget, int threads) {
        if (callTarget.getOptionValue(OptimizedRuntimeOptions.TraversingCompilationQueue)) {
            if (callTarget.getOptionValue(OptimizedRuntimeOptions.DynamicCompilationThresholds) && callTarget.getOptionValue(OptimizedRuntimeOptions.BackgroundCompilation)) {
                double minScale = callTarget.getOptionValue(OptimizedRuntimeOptions.DynamicCompilationThresholdsMinScale);
                int minNormalLoad = callTarget.getOptionValue(OptimizedRuntimeOptions.DynamicCompilationThresholdsMinNormalLoad);
                int maxNormalLoad = callTarget.getOptionValue(OptimizedRuntimeOptions.DynamicCompilationThresholdsMaxNormalLoad);
                return new DynamicThresholdsQueue(threads, minScale, minNormalLoad, maxNormalLoad, new IdlingLinkedBlockingDeque<>());
            } else {
                return new TraversingBlockingQueue(new IdlingLinkedBlockingDeque<>());
            }
        } else {
            return new IdlingPriorityBlockingQueue<>();
        }
    }

    @SuppressWarnings("unused")
    protected ThreadFactory newThreadFactory(String threadNamePrefix, OptimizedCallTarget callTarget) {
        return new TruffleCompilerThreadFactory(threadNamePrefix, runtime);
    }

    private CompilationTask submitTask(CompilationTask compilationTask) {
        compilationTask.setFuture(getExecutorService(compilationTask.targetRef.get()).submit(compilationTask));
        return compilationTask;
    }

    public CompilationTask submitCompilation(Priority priority, OptimizedCallTarget target) {
        final WeakReference<OptimizedCallTarget> targetReference = new WeakReference<>(target);
        CompilationTask compilationTask = CompilationTask.createCompilationTask(priority, targetReference, nextId());
        return submitTask(compilationTask);
    }

    public CompilationTask submitInitialization(OptimizedCallTarget target, Consumer<CompilationTask> action) {
        final WeakReference<OptimizedCallTarget> targetReference = new WeakReference<>(target);
        CompilationTask initializationTask = CompilationTask.createInitializationTask(targetReference, action);
        return submitTask(initializationTask);
    }

    private long nextId() {
        return idCounter.getAndIncrement();
    }

    public int getQueueSize() {
        final ThreadPoolExecutor threadPool = compilationExecutorService;
        if (threadPool != null) {
            return threadPool.getQueue().size();
        } else {
            return 0;
        }
    }

    /**
     * Return call targets waiting in queue. This does not include call targets currently being
     * compiled. If {@code engine} is {@code null}, the call targets for all engines are returned,
     * otherwise only the call targets belonging to {@code engine} will be returned.
     */
    public Collection<OptimizedCallTarget> getQueuedTargets(EngineData engine) {
        BlockingQueue<Runnable> queue = this.compilationQueue;
        if (queue == null) {
            // queue not initialized
            return Collections.emptyList();
        }
        List<OptimizedCallTarget> queuedTargets = new ArrayList<>();
        CompilationTask.ExecutorServiceWrapper[] array = queue.toArray(new CompilationTask.ExecutorServiceWrapper[0]);
        for (CompilationTask.ExecutorServiceWrapper wrapper : array) {
            OptimizedCallTarget target = wrapper.compileTask.targetRef.get();
            if (target != null && (engine == null || target.engine == engine)) {
                queuedTargets.add(target);
            }
        }
        return Collections.unmodifiableCollection(queuedTargets);
    }

    public void shutdownAndAwaitTermination(long timeout) {
        final ExecutorService threadPool;
        synchronized (this) {
            threadPool = compilationExecutorService;
            if (threadPool == null) {
                shutdown = true;
                return;
            }
        }
        threadPool.shutdownNow();
        try {
            threadPool.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Could not terminate compiler threads. Check if there are runaway compilations that don't handle Thread#interrupt.", e);
        }
    }

    /**
     * Called when a compiler thread becomes idle for more than {@code delayMillis}.
     */
    protected void notifyIdleCompilerThread() {
        // nop
    }

    static class Priority {

        public static final Priority INITIALIZATION = new Priority(0, Tier.INITIALIZATION);
        final Tier tier;
        final int value;

        Priority(int value, Tier tier) {
            this.value = value;
            this.tier = tier;
        }

        public enum Tier {
            INITIALIZATION,
            FIRST,
            LAST
        }

    }

    private final class TruffleThreadPoolExecutor extends ThreadPoolExecutor {
        private TruffleThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
        }

        @Override
        @SuppressWarnings({"unchecked"})
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            return (RunnableFuture<T>) new CompilationTask.ExecutorServiceWrapper((CompilationTask) callable);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            ThreadFactory threadFactory = getThreadFactory();
            if (threadFactory instanceof JoinableThreadFactory) {
                return ((JoinableThreadFactory) threadFactory).joinOtherThreads(timeout, unit);
            } else {
                return super.awaitTermination(timeout, unit);
            }
        }
    }

    public interface JoinableThreadFactory extends ThreadFactory {
        /**
         * Join all but the current thread. If the current thread belongs to this thread factory,
         * its interrupted status is just cleared instead of joining it.
         */
        boolean joinOtherThreads(long timeout, TimeUnit unit) throws InterruptedException;
    }

    private final class TruffleCompilerThreadFactory implements JoinableThreadFactory {
        private final String namePrefix;
        private final OptimizedTruffleRuntime runtime;
        private final Set<Thread> threads = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

        TruffleCompilerThreadFactory(final String namePrefix, OptimizedTruffleRuntime runtime) {
            this.namePrefix = namePrefix;
            this.runtime = runtime;
        }

        @SuppressWarnings("deprecation")
        @Override
        public Thread newThread(Runnable r) {
            final Thread t = new Thread(r) {
                @SuppressWarnings("try")
                @Override
                public void run() {
                    setContextClassLoader(getClass().getClassLoader());
                    try (AutoCloseable compilerThreadScope = runtime.openCompilerThreadScope();
                                    AutoCloseable polyglotThreadScope = OptimizedRuntimeAccessor.ENGINE.createPolyglotThreadScope()) {
                        super.run();
                        if (compilationExecutorService.allowsCoreThreadTimeOut()) {
                            // If core threads are always kept alive (no timeout), the
                            // IdlingPriorityBlockingQueue.take mechanism is used instead.
                            notifyIdleCompilerThread();
                        }
                    } catch (Exception e) {
                        throw new InternalError(e);
                    }
                }
            };
            t.setName(namePrefix + "-" + t.getId());
            t.setPriority(Thread.MAX_PRIORITY);
            t.setDaemon(true);
            threads.add(t);
            return t;
        }

        @Override
        public boolean joinOtherThreads(long timeout, TimeUnit unit) throws InterruptedException {
            long timeoutNanos = unit.toNanos(timeout);
            synchronized (threads) {
                if (threads.contains(Thread.currentThread())) {
                    // clear interrupt status
                    Thread.interrupted();
                }
                for (Thread thread : threads) {
                    if (thread == Thread.currentThread()) {
                        continue;
                    }
                    long joinStart = System.nanoTime();
                    TimeUnit.NANOSECONDS.timedJoin(thread, timeoutNanos);
                    long joinEnd = System.nanoTime();
                    timeoutNanos -= (joinEnd - joinStart);
                    if (timeoutNanos <= 0) {
                        return false;
                    }
                }
                return true;
            }
        }
    }

    /**
     * {@link PriorityBlockingQueue} with idling notification.
     *
     * <p>
     * The idling notification is triggered when a compiler thread remains idle more than
     * {@code delayMillis}.
     *
     * There are no guarantees on which thread will run the {@code onIdleDelayed} hook. Note that,
     * starved threads can also trigger the notification, even if the compile queue is not idle
     * during the delay period, the idling criteria is thread-based, not queue-based.
     */
    @SuppressWarnings("serial")
    private final class IdlingPriorityBlockingQueue<E> extends PriorityBlockingQueue<E> {
        @Override
        public E take() throws InterruptedException {
            while (!compilationExecutorService.allowsCoreThreadTimeOut()) {
                E elem = poll(delayMillis, TimeUnit.MILLISECONDS);
                if (elem == null) {
                    notifyIdleCompilerThread();
                } else {
                    return elem;
                }
            }
            // Fallback to blocking version.
            return super.take();
        }
    }

    /**
     * {@link LinkedBlockingDeque} with idling notification.
     *
     * <p>
     * The idling notification is triggered when a compiler thread remains idle more than
     * {@code delayMillis} milliseconds.
     *
     * There are no guarantees on which thread will run the {@code onIdleDelayed} hook. Note that,
     * starved threads can also trigger the notification, even if the compile queue is not idle
     * during the delay period, the idling criteria is thread-based, not queue-based.
     */
    @SuppressWarnings("serial")
    private final class IdlingLinkedBlockingDeque<E> extends LinkedBlockingDeque<E> {
        @Override
        public E takeFirst() throws InterruptedException {
            while (!compilationExecutorService.allowsCoreThreadTimeOut()) {
                E elem = poll(delayMillis, TimeUnit.MILLISECONDS);
                if (elem == null) {
                    notifyIdleCompilerThread();
                } else {
                    return elem;
                }
            }
            // Fallback to blocking version.
            return super.take();
        }

        @Override
        public E pollFirst(long timeout, TimeUnit unit) throws InterruptedException {
            long timeoutMillis = unit.toMillis(timeout);
            if (timeoutMillis < delayMillis) {
                return super.pollFirst(timeout, unit);
            }
            while (timeoutMillis > delayMillis) {
                E elem = super.pollFirst(delayMillis, TimeUnit.MILLISECONDS);
                if (elem == null) {
                    notifyIdleCompilerThread();
                } else {
                    return elem;
                }
                timeoutMillis -= delayMillis;
            }
            return super.pollFirst(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }
}

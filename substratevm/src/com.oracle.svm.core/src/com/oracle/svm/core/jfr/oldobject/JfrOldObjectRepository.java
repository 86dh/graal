/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, 2023, Red Hat Inc. All rights reserved.
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

package com.oracle.svm.core.jfr.oldobject;

import jdk.graal.compiler.word.Word;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.jfr.JfrBuffer;
import com.oracle.svm.core.jfr.JfrBufferAccess;
import com.oracle.svm.core.jfr.JfrBufferType;
import com.oracle.svm.core.jfr.JfrChunkWriter;
import com.oracle.svm.core.jfr.JfrNativeEventWriter;
import com.oracle.svm.core.jfr.JfrNativeEventWriterData;
import com.oracle.svm.core.jfr.JfrNativeEventWriterDataAccess;
import com.oracle.svm.core.jfr.JfrRepository;
import com.oracle.svm.core.jfr.JfrType;
import com.oracle.svm.core.jfr.SubstrateJVM;
import com.oracle.svm.core.jfr.traceid.JfrTraceIdEpoch;
import com.oracle.svm.core.locks.VMMutex;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.word.WordFactory;

public final class JfrOldObjectRepository implements JfrRepository {

    private final VMMutex mutex;
    private final JfrOldObjectEpochData epochData0;
    private final JfrOldObjectEpochData epochData1;
    private final JfrOldObjectDescriptionWriter descriptionWriter;
    private long nextId = 1;

    @Platforms(Platform.HOSTED_ONLY.class)
    public JfrOldObjectRepository() {
        this.mutex = new VMMutex("jfrOldObjectRepository");
        this.epochData0 = new JfrOldObjectEpochData();
        this.epochData1 = new JfrOldObjectEpochData();
        this.descriptionWriter = new JfrOldObjectDescriptionWriter();
    }

    public void teardown() {
        epochData0.teardown();
        epochData1.teardown();
    }

    @Uninterruptible(reason = "Locking without transition and result is only valid until epoch changes.", callerMustBe = true)
    public long serializeOldObject(Object obj) {
        mutex.lockNoTransition();
        try {
            JfrOldObjectEpochData epochData = getEpochData(false);
            if (epochData.buffer.isNull()) {
                epochData.buffer = JfrBufferAccess.allocate(JfrBufferType.C_HEAP);
            }

            final Word pointer = Word.objectToUntrackedPointer(obj);
            JfrNativeEventWriterData data = StackValue.get(JfrNativeEventWriterData.class);
            JfrNativeEventWriterDataAccess.initialize(data, epochData.buffer);
            final long objectId = nextId;
            JfrNativeEventWriter.putLong(data, objectId);
            nextId++;
            JfrNativeEventWriter.putLong(data, pointer.rawValue());
            JfrNativeEventWriter.putLong(data, SubstrateJVM.getTypeRepository().getClassId(obj.getClass()));
            writeDescription(obj, data);
            // todo parent address (path-to-gc-roots)
            JfrNativeEventWriter.putLong(data, WordFactory.zero().rawValue());
            if (!JfrNativeEventWriter.commit(data)) {
                return 0L;
            }

            epochData.unflushedEntries++;
            /* The buffer may have been replaced with a new one. */
            epochData.buffer = data.getJfrBuffer();
            return objectId;
        } finally {
            mutex.unlock();
        }
    }

    @Uninterruptible(reason = "Locking without transition and result is only valid until epoch changes.", callerMustBe = true)
    private void writeDescription(Object obj, JfrNativeEventWriterData data) {
        if (obj instanceof ThreadGroup) {
            String prefix = "Thread Group: ";
            String threadGroupName = ((ThreadGroup) obj).getName();
            descriptionWriter.write(prefix);
            descriptionWriter.write(threadGroupName);
            descriptionWriter.finish(data);
            return;
        }
        if (obj instanceof Thread) {
            String prefix = "Thread Name: ";
            String threadName = ((Thread) obj).getName();
            descriptionWriter.write(prefix);
            descriptionWriter.write(threadName);
            descriptionWriter.finish(data);
            return;
        }
        if (obj instanceof Class) {
            String prefix = "Class Name: ";
            String className = ((Class<?>) obj).getName();
            descriptionWriter.write(prefix);
            descriptionWriter.write(className);
            descriptionWriter.finish(data);
            return;
        }

        // Size description not implemented since that relies on runtime reflection.
        JfrNativeEventWriter.putLong(data, 0L);
    }

    @Override
    @Uninterruptible(reason = "Locking without transition requires that the whole critical section is uninterruptible.")
    public int write(JfrChunkWriter writer, boolean flushpoint) {
        mutex.lockNoTransition();
        try {
            JfrOldObjectEpochData epochData = getEpochData(true);
            int count = epochData.unflushedEntries;
            if (count != 0) {
                writer.writeCompressedLong(JfrType.OldObject.getId());
                writer.writeCompressedInt(count);
                writer.write(epochData.buffer);
            }
            epochData.clear();
            return count == 0 ? EMPTY : NON_EMPTY;
        } finally {
            mutex.unlock();
        }
    }

    @Uninterruptible(reason = "Result is only valid until epoch changes.", callerMustBe = true)
    private JfrOldObjectEpochData getEpochData(boolean previousEpoch) {
        boolean epoch = previousEpoch ? JfrTraceIdEpoch.getInstance().previousEpoch() : JfrTraceIdEpoch.getInstance().currentEpoch();
        return epoch ? epochData0 : epochData1;
    }

    private static class JfrOldObjectEpochData {
        private int unflushedEntries;
        private JfrBuffer buffer;

        @Platforms(Platform.HOSTED_ONLY.class)
        JfrOldObjectEpochData() {
            this.unflushedEntries = 0;
        }

        @Uninterruptible(reason = "May write current epoch data.")
        void clear() {
            unflushedEntries = 0;
            JfrBufferAccess.reinitialize(buffer);
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        void teardown() {
            unflushedEntries = 0;
            JfrBufferAccess.free(buffer);
            buffer = WordFactory.nullPointer();
        }
    }
}

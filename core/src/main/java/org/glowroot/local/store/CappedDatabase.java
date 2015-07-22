/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.local.store;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Charsets;
import com.google.common.base.Ticker;
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;
import com.google.common.io.CountingOutputStream;
import com.google.common.primitives.Longs;
import com.ning.compress.lzf.LZFInputStream;
import com.ning.compress.lzf.LZFOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ChunkSource;
import org.glowroot.markers.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class CappedDatabase {

    private static final Logger logger = LoggerFactory.getLogger(CappedDatabase.class);

    private final File file;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private final CappedDatabaseOutputStream out;
    private final Thread shutdownHookThread;
    @GuardedBy("lock")
    private RandomAccessFile inFile;
    private volatile boolean closing = false;

    private final Ticker ticker;
    private final Map<String, CappedDatabaseStats> statsByType = Maps.newHashMap();

    CappedDatabase(File file, int requestedSizeKb, Ticker ticker) throws IOException {
        this.file = file;
        this.ticker = ticker;
        out = new CappedDatabaseOutputStream(file, requestedSizeKb);
        inFile = new RandomAccessFile(file, "r");
        shutdownHookThread = new ShutdownHookThread();
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }

    long write(final CharSource charSource, String type) throws IOException {
        return write(type, new Copier() {
            @Override
            public void copyTo(Writer writer) throws IOException {
                charSource.copyTo(writer);
            }
        });
    }

    long write(final ChunkSource charSource, String type) throws IOException {
        return write(type, new Copier() {
            @Override
            public void copyTo(Writer writer) throws IOException {
                charSource.copyTo(writer);
            }
        });
    }

    CappedDatabaseStats getStats(String type) {
        CappedDatabaseStats stats = statsByType.get(type);
        if (stats == null) {
            return new CappedDatabaseStats();
        }
        return stats;
    }

    private long write(String type, Copier copier) throws IOException {
        synchronized (lock) {
            if (closing) {
                return -1;
            }
            long startTick = ticker.read();
            out.startBlock();
            NonClosingCountingOutputStream countingStreamAfterCompression =
                    new NonClosingCountingOutputStream(out);
            CountingOutputStream countingStreamBeforeCompression =
                    new CountingOutputStream(new LZFOutputStream(countingStreamAfterCompression));
            Writer compressedWriter =
                    new OutputStreamWriter(countingStreamBeforeCompression, Charsets.UTF_8);
            copier.copyTo(compressedWriter);
            compressedWriter.close();
            long endTick = ticker.read();
            CappedDatabaseStats stats = statsByType.get(type);
            if (stats == null) {
                stats = new CappedDatabaseStats();
                statsByType.put(type, stats);
            }
            stats.record(countingStreamBeforeCompression.getCount(),
                    countingStreamAfterCompression.getCount(),
                    NANOSECONDS.toMicros(endTick - startTick));
            return out.endBlock();
        }
    }

    CharSource read(long cappedId, String overwrittenResponse) {
        if (cappedId >= out.getCurrIndex()) {
            // this can happen when the glowroot folder is copied for analysis without shutting down
            // the JVM and glowroot.capped.db is copied first, then new data is written to
            // glowroot.capped.db and the new capped ids are written to glowroot.h2.db and then
            // glowroot.h2.db is copied with capped ids that do not exist in the copied
            // glowroot.capped.db
            return CharSource.wrap(overwrittenResponse);
        }
        return new CappedBlockCharSource(cappedId, overwrittenResponse);
    }

    boolean isExpired(long cappedId) {
        return out.isOverwritten(cappedId);
    }

    long getSmallestNonExpiredId() {
        return out.getSmallestNonOverwrittenId();
    }

    public void resize(int newSizeKb) throws IOException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            inFile.close();
            out.resize(newSizeKb);
            inFile = new RandomAccessFile(file, "r");
        }
    }

    @OnlyUsedByTests
    void close() throws IOException {
        synchronized (lock) {
            closing = true;
            out.close();
            inFile.close();
        }
        Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
    }

    private class CappedBlockCharSource extends CharSource {

        private final long cappedId;
        private final String overwrittenResponse;

        private CappedBlockCharSource(long cappedId, String overwrittenResponse) {
            this.cappedId = cappedId;
            this.overwrittenResponse = overwrittenResponse;
        }

        @Override
        public Reader openStream() throws IOException {
            if (out.isOverwritten(cappedId)) {
                return CharSource.wrap(overwrittenResponse).openStream();
            }
            // it's important to wrap CappedBlockInputStream in a BufferedInputStream to prevent
            // lots of small reads from the underlying RandomAccessFile
            final int bufferSize = 32768;
            return new InputStreamReader(new LZFInputStream(new BufferedInputStream(
                    new CappedBlockInputStream(cappedId), bufferSize)), Charsets.UTF_8);
        }
    }

    private class CappedBlockInputStream extends InputStream {

        private final long cappedId;
        private long blockLength = -1;
        private long blockIndex;

        private CappedBlockInputStream(long cappedId) {
            this.cappedId = cappedId;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            if (blockIndex == blockLength) {
                return -1;
            }
            synchronized (lock) {
                if (out.isOverwritten(cappedId)) {
                    throw new IOException("Block rolled over mid-read");
                }
                if (blockLength == -1) {
                    long filePosition = out.convertToFilePosition(cappedId);
                    inFile.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + filePosition);
                    blockLength = inFile.readLong();
                }
                long filePosition = out.convertToFilePosition(cappedId
                        + CappedDatabaseOutputStream.BLOCK_HEADER_SKIP_BYTES + blockIndex);
                inFile.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + filePosition);
                long blockRemaining = blockLength - blockIndex;
                long fileRemaining = out.getSizeKb() * 1024L - filePosition;
                int numToRead = (int) Longs.min(len, blockRemaining, fileRemaining);
                inFile.readFully(bytes, off, numToRead);
                blockIndex += numToRead;
                return numToRead;
            }
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            // this is never called since CappedBlockInputStream is always wrapped in a
            // BufferedInputStream
            return read(bytes, 0, bytes.length);
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException(
                    "CappedBlockInputStream should always be wrapped in a BufferedInputStream");
        }
    }

    private class ShutdownHookThread extends Thread {
        @Override
        public void run() {
            try {
                // update flag outside of lock in case there is a backlog of threads already
                // waiting on the lock (once the flag is set, any threads in the backlog that
                // haven't acquired the lock will abort quickly once they do obtain the lock)
                closing = true;
                synchronized (lock) {
                    out.close();
                    inFile.close();
                }
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }

    private interface Copier {
        public void copyTo(Writer writer) throws IOException;
    }

    private static class NonClosingCountingOutputStream extends FilterOutputStream {

        private long count;

        private NonClosingCountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void close() {}

        private long getCount() {
            return count;
        }
    }
}

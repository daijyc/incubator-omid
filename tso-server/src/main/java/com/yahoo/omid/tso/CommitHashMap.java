/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class stores the mapping between start and commit timestamps and between modified row and commit timestamp.
 * 
 * Both mappings are represented as a long->long mapping, each of them implemented using a single long []
 * 
 * For a map of size N we create an array of size 2*N and store the keys on even indexes and values on odd indexes.
 * 
 * Each time an entry is removed, we update the largestDeletedTimestamp if the entry's commit timestamp is greater than
 * this value.
 * 
 * Rationale: we want queries to be fast and touch as least memory regions as possible
 * 
 * TODO: improve garbage collection, right now an entry is picked at random (by hash) which could cause the eviction of
 * a very recent timestamp
 */

class CommitHashMap {

    private static final Logger LOG = LoggerFactory.getLogger(CommitHashMap.class);


    private long largestDeletedTimestamp;
    private final Cache startCommitMapping;
    private final Cache rowsCommitMapping;

    private AtomicLong addHalfAbortedCount = new AtomicLong(0);
    private AtomicLong removeHalfAbortedCount = new AtomicLong(0);

    /**
     * Constructs a new, empty hashtable with a default size of 1000
     */
    public CommitHashMap(long largestDeletedTimestamp) {
        this(1000, largestDeletedTimestamp);
    }

    /**
     * Constructs a new, empty hashtable with the specified size
     * 
     * @param size
     *            the initial size of the hashtable.
     * @throws IllegalArgumentException
     *             if the size is less than zero.
     */
    public CommitHashMap(int size, long largestDeletedTimestamp) {
        if (size < 0) {
            throw new IllegalArgumentException("Illegal size: " + size);
        }

        this.startCommitMapping = new LongCache(size, 16);
        this.rowsCommitMapping = new LongCache(size, 32);
        this.largestDeletedTimestamp = largestDeletedTimestamp;
    }

    public long getLatestWriteForRow(long hash) {
        return rowsCommitMapping.get(hash);
    }

    public long putLatestWriteForRow(long hash, long commitTimestamp) {
        return rowsCommitMapping.set(hash, commitTimestamp);
    }

    public long getCommittedTimestamp(long startTimestamp) {
        return startCommitMapping.get(startTimestamp);
    }

    public long setCommittedTimestamp(long startTimestamp, long commitTimestamp) {
        return startCommitMapping.set(startTimestamp, commitTimestamp);
    }

    // set of half aborted transactions
    // TODO: set the initial capacity in a smarter way
    Set<AbortedTransaction> halfAborted = Collections.newSetFromMap(new ConcurrentHashMap<AbortedTransaction, Boolean>(
            10000));

    private AtomicLong abortedSnapshot = new AtomicLong();

    long getAndIncrementAbortedSnapshot() {
        return abortedSnapshot.getAndIncrement();
    }

    // add a new half aborted transaction
    void setHalfAborted(long startTimestamp) {
        long currentVal = addHalfAbortedCount.incrementAndGet();
        if ((currentVal % 10000) == 0) {
            LOG.debug("addHalfAbortedCount value" + currentVal);
        }
        halfAborted.add(new AbortedTransaction(startTimestamp, abortedSnapshot.get()));
    }

    // call when a half aborted transaction is fully aborted
    void setFullAborted(long startTimestamp) {
        long currentVal = removeHalfAbortedCount.incrementAndGet();
        if ((currentVal % 10000) == 0) {
            LOG.debug("removeHalfAbortedCount value" + currentVal);
        }
        if (!halfAborted.remove(new AbortedTransaction(startTimestamp, 0))) {
            LOG.error("TX not found! Cannot clean stuff for transaction with ST: " + startTimestamp);
        }
    }

    // query to see if a transaction is half aborted
    boolean isHalfAborted(long startTimestamp) {
        return halfAborted.contains(new AbortedTransaction(startTimestamp, 0));
    }
}
package org.apache.cassandra.io;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.io.Closeable;
import java.io.IOException;
import java.io.IOError;
import java.util.*;

import org.apache.log4j.Logger;

import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.ColumnKey;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;

public class CompactionIterator extends AbstractIterator<SliceBuffer> implements Closeable
{
    private static Logger logger = Logger.getLogger(CompactionIterator.class);

    /**
     * Shared file buffer size for all input SSTables.
     * TODO: make configurable
     */
    public static final int TOTAL_FILE_BUFFER_BYTES = 1 << 22;

    private final int gcBefore;
    private final boolean major;

    /**
     * The comparator for all SSTables we are compacting.
     */
    private final ColumnKey.Comparator comparator;
    /**
     * Scanners are kept sorted by the key of the Slice they are positioned at.
     */
    private final PriorityQueue<SSTableScanner> scanners;

    /**
     * List of Metadata and Column entries. Metadata entries apply to all columns up
     * to the next Metadata entry. See BufferEntry.
     *
     * NB: This buffer is the source of the majority of memory usage for compactions.
     * Its maximum size in bytes is roughly equal to:
     * (CompactionManager.maxCompactThreshold * SSTable.TARGET_MAX_SLICE_BYTES)
     *
     * The LinkedList is a natural fit for merge sort because it provides random
     * insertion performance, and allows constant time removal from the head.
     */
    private LinkedList<BufferEntry> mergeBuff;
    /**
     * Whenever a Metadata entry reaches the
     * front of the merge buffer, a new output slice is created, which contains all
     * of the columns leading up to the next Metadata entry, or up to
     * SSTable.TOTAL_MAX_SLICE_BYTES.
     */
    private SliceBuffer outslice = null;

    /**
     * TODO: add a range-based filter like #607, but use it to seek() on the Scanners.
     */
    public CompactionIterator(Collection<SSTableReader> sstables, int gcBefore, boolean major) throws IOException
    {
        super();
        assert !sstables.isEmpty();

        this.gcBefore = gcBefore;
        this.major = major;

        // fields shared for all sstables
        SSTableReader head = sstables.iterator().next();
        this.comparator = head.getComparator();

        // open all scanners
        int bufferPer = TOTAL_FILE_BUFFER_BYTES / sstables.size();
        this.scanners = new PriorityQueue<SSTableScanner>(sstables.size());
        for (SSTableReader sstable : sstables)
            this.scanners.add(sstable.getScanner(bufferPer));
        this.mergeBuff = new LinkedList<BufferEntry>();
    }

    /**
     * Merges the given slice and its columns into the merge buffer: thanks to
     * LinkedList and ListIterator, merging happens in place. Metadata for the slice
     * acts as the head of the input list, causing it to apply to the tailing items.
     */
    void mergeToBuffer(Slice slice, List<Column> rhs)
    {
        ListIterator<BufferEntry> buffiter = mergeBuff.listIterator();
        Iterator<Column> rhsiter = rhs.iterator();

        BufferEntry buffcur = buffiter.hasNext() ? buffiter.next() : null;
        // Metadata for the slice as header
        BufferEntry rhscur = new MetadataEntry(slice.key, slice.meta);
        while (buffcur != null && rhscur != null)
        {
            // compare the heads
            int comp = buffcur.compareTo(rhscur);
            if (comp < 0)
                // merge buffer contains smaller entry
                buffcur = null;
            else if (comp == 0)
            {
                // buffcur and rhscur have equal keys and types: resolve and replace buffcur
                buffcur = resolveEntries(buffcur, rhscur);
                buffiter.set(buffcur);
                rhscur = null;
            }
            else // buffcur > rhscur
            {
                // insert smaller entry from rhs before buffcur in the merge buffer
                buffiter.set(rhscur);
                buffiter.add(buffcur);
                buffiter.previous();
                rhscur = null;
            }

            if (buffcur == null && buffiter.hasNext())
                buffcur = buffiter.next();
            if (rhscur == null && rhsiter.hasNext())
            {
                Column column = rhsiter.next();
                rhscur = new ColumnEntry(slice.key.withName(column.name()),
                                         column);
            }
        }

        // add the remainder of rhs to the end of the merge buffer
        if (rhscur != null)
            mergeBuff.add(rhscur);
        while(rhsiter.hasNext())
        {
            Column column = rhsiter.next();
            mergeBuff.add(new ColumnEntry(slice.key.withName(column.name()),
                                          column));
        }
        
        logger.trace("Added " + (rhs.size()+1) + " items to merge buffer. Contains " +
            mergeBuff.size()); // FIXME
    }

    /**
     * Resolves two BufferEntries of the same type with equal keys against one another.
     *
     * @return The resulting BufferEntry, which will never be null because parent
     * tombstone garbage collection happens as we pop entries from the merge buffer.
     */
    BufferEntry resolveEntries(BufferEntry lhs, BufferEntry rhs)
    {
        assert lhs.getClass() == rhs.getClass();

        if (lhs instanceof MetadataEntry)
        {
            Slice.Metadata lhsmeta = ((MetadataEntry)lhs).meta;
            Slice.Metadata rhsmeta = ((MetadataEntry)rhs).meta;
            // maximum deletion times at each parent level win
            return new MetadataEntry(lhs.key,
                                     Slice.Metadata.resolve(lhsmeta, rhsmeta));
        }
        // else instanceof ColumnEntry

        Column lhscol = ((ColumnEntry)lhs).column;
        Column rhscol = ((ColumnEntry)rhs).column;
        // highest priority wins
        return lhscol.comparePriority(rhscol) <= 0 ? rhs : lhs;
    }

    /**
     * Ensure that the minimum keys from all Scanners have been added to the merge buffer.
     * In the best case (since all lists are sorted) this involves a single comparison
     * of the head of the merge buffer to the head of the scanner priorityq. In the worst
     * case, it requires scanners.size() comparisons.
     *
     * @return False if the merge buffer and all Scanners are empty.
     */
    boolean ensureMergeBuffer()
    {
        // select the minimum key
        ColumnKey minimum;
        if (mergeBuff.isEmpty())
        {
            if (scanners.isEmpty())
                // the merge buffer and scanner queue are empty. we're done!
                return false;
            minimum = scanners.peek().get().key;
        }
        else
            minimum = mergeBuff.peek().key;

        // remove any scanners with keys less than or equal to the minimum
        List<SSTableScanner> selected = null;
        while (!scanners.isEmpty() && comparator.compare(scanners.peek().get().key, minimum) <= 0)
        {
            if (selected == null)
                // lazily create list of scanners
                selected = new LinkedList<SSTableScanner>();
            selected.add(scanners.poll());
        }
        if (selected == null)
            // merge buffer contains minimum key
            return true;

        // for each of the minimum slices
        for (SSTableScanner scanner : selected)
        {
            try
            {
                // merge the first slice to the merge buffer
                mergeToBuffer(scanner.get(), scanner.getBuffer());

                // skip to the next slice
                if (scanner.next())
                    // has more slices: add back to the queue to reprioritize
                    scanners.add(scanner);
                else
                    scanner.close();
            }
            catch (IOException e)
            {
                // FIXME: the iterator interface sucks for IO
                throw new IOError(e);
            }
        }
        return true;
    }

    /**
     * First, guarantees that the minimum keys for this iteration are contained in the
     * merge buffer.
     *
     * Then, while maintaining that guarantee, pops from the head of the merge
     * buffer into an output slice, while applying deletion metadata and garbage
     * collecting tombstones.
     *
     * @return The next SliceBuffer for this iterator.
     */
    @Override
    public SliceBuffer computeNext()
    {
        while (ensureMergeBuffer())
        {
            BufferEntry entry = mergeBuff.poll();
            if (entry instanceof MetadataEntry)
            {
                // metadata marks the beginning of a new slice
                MetadataEntry mentry = (MetadataEntry)entry;
                SliceBuffer oldslice = outslice;
                outslice = new SliceBuffer(mentry.key, mentry.meta);
                if (oldslice != null && !oldslice.isDeleted(major, gcBefore))
                    // return the finished slice
                    return oldslice;
                continue;
            }

            // else, ColumnEntry to add to the current slice
            ColumnEntry centry = (ColumnEntry)entry;
            if (!centry.column.isDeleted(outslice.meta, major, gcBefore))
                // add if metadata does not indicate that it should be removed
                outslice.columns.add(centry.column);

            // TODO: need to check TARGET_MAX_SLICE_BYTES here, and artificially
            // split the slice to prevent it from becoming too large
        }

        if (outslice != null && !outslice.isDeleted(major, gcBefore))
        {
            // return the final slice
            SliceBuffer oldslice = outslice;
            outslice = null;
            return oldslice;
        }

        // no more columns
        return endOfData();
    }

    public void close() throws IOException
    {
        IOException e = null;
        for (SSTableScanner scanner : scanners)
        {
            try
            {
                scanner.close();
            }
            catch (IOException ie)
            {
                e = ie;
            }
        }
        // we can only rethrow one exception, but we want to close all scanners
        if (e != null)
            throw e;
    }

    /**
     * Represents a tuple of (ColumnKey, (Metadata or Column)). Metadata entries in
     * the merge buffer play a similar role to the one they play on disk: they apply
     * metadata to all columns up to the next Metadata entry.
     *
     * A piece of Metadata with a key equal to the key of a column Column should sort
     * before the Column, in order to apply to it. For example, a piece of Metadata
     * representing a tombstone and beginning at the same key as a Column should apply
     * to and delete the Column.
     */
    abstract class BufferEntry implements Comparable<BufferEntry>
    {
        public final ColumnKey key;
        protected BufferEntry(ColumnKey key)
        {
            this.key = key;
        }
            
        public int compareTo(BufferEntry that)
        {
            int comparison = comparator.compare(this.key, that.key);
            if (comparison != 0)
                return comparison;
            if (this.getClass().equals(that.getClass()))
                return 0;
            // sort Metadata first
            return this instanceof MetadataEntry ? -1 : 1;
        }
    
        public String toString()
        {
            return "<" + this.getClass().getName() + " " + key.dk + ">";
        }
    }

    final class MetadataEntry extends BufferEntry
    {
        public final Slice.Metadata meta;
        public MetadataEntry(ColumnKey key, Slice.Metadata meta)
        {
            super(key);
            this.meta = meta;
        }
    }

    final class ColumnEntry extends BufferEntry
    {
        public final Column column;
        public ColumnEntry(ColumnKey key, Column column)
        {
            super(key);
            this.column = column;
        }
    }
}

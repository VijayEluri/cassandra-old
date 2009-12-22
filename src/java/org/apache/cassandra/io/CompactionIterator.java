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
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ColumnKey;
import org.apache.cassandra.utils.Pair;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

public class CompactionIterator extends AbstractIterator<CompactionSlice> implements Closeable
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
     * The comparator and columnDepth, which should be the same for all SSTables we
     * are compacting.
     */
    private final ColumnKey.Comparator comparator;
    private final int columnDepth;
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
     * (CompactionManager.maxCompactThreshold * SSTableWriter.TARGET_MAX_SLICE_BYTES)
     *
     * The LinkedList is a natural fit for merge sort because it provides random
     * insertion performance, and allows constant time removal from the head.
     */
    private LinkedList<BufferEntry> mergeBuff;

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
        this.columnDepth = head.getColumnDepth();

        // open all scanners
        int bufferPer = TOTAL_FILE_BUFFER_BYTES / sstables.size();
        this.scanners = new PriorityQueue<SSTableScanner>(sstables.size());
        for (SSTableReader sstable : sstables)
            this.scanners.add(sstable.getScanner(bufferPer));
        this.mergeBuff = new ArrayDeque<CompactionSlice>();
    }

    /**
     * Merges the given slice and its columns into the merge buffer: thanks to
     * LinkedList and ListIterator, merging happens in place. Metadata for the slice
     * acts as the head of the input list, causing it to apply to the tailing items.
     */
    void mergeToBuffer(Slice slice, List<Column> rhs)
    {
        ListIterator<BufferEntry> buffiter = mergeBuff.listIterator();
        Iterator<Column> rhsiter = columns.iterator();

        BufferEntry buffcur = buffiter.hasNext() ? buffiter.next() : null;
        // Metadata for the slice as header
        BufferEntry rhscur = new MetadataEntry(slice.currentKey, slice.meta);
        while (buffcur != null && rhscur != null)
        {
            // compare the heads
            int comp = buffcur.compareTo(rhscur);
            if (comp < 0)
                // merge buffer contains smaller entry
                buffcur = buffiter.next();
            else if (comp == 0)
            {
                // buffcur and rhscur have equal keys: resolve them and replace buffcur
                buffcur = resolve(buffcur, rhscur);
                buffiter.set(buffcur);
            }
            else // buffcur > rhscur
            {
                // insert smaller entry from rhs at buffcur's position in merge buffer
                // FIXME: need listIterator docs here: call next afterward?
                buffiter.set(new ColumnEntry(slice.currentKey.withName(column.name()),
                                             column));
                // add the entry we replaced after the smaller entry
                buffiter.add(buffcur);
            }
        }
        // add the remainder of the rhs to the end of the merge buffer
        while (rhsiter.hasNext())
            mergeBuff.add(rhsiter.next());
        // else, all items have already been merged
        
        logger.trace("Added " + rhs.size() + " items to merge buffer. Contains " +
            mergeBuff.size()); // FIXME
    }

    /**
     * Resolves two BufferEntries of the same type with equal keys against one another.
     *
     * @return The resulting BufferEntry, which will never be null because parent
     * tombstone garbage collection happens as we pop entries from the merge buffer.
     */
    BufferEntry resolve(BufferEntry lhs, BufferEntry rhs)
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

        // FIXME
        /*
        assert rows.size() > 0;
        DataOutputBuffer buffer = new DataOutputBuffer();
        DecoratedKey key = rows.get(0).getKey();

        ColumnFamily cf = null;
        try
        {
            if (rows.size() > 1 || major)
            {
                for (Pair<DecoratedKey,ColumnFamily> row : rows)
                {
                    ColumnFamily thisCF;
                    try
                    {
                        thisCF = row.getColumnFamily();
                    }
                    catch (IOException e)
                    {
                        logger.error("Skipping row " + key + " in " + row.getPath(), e);
                        continue;
                    }
                    if (cf == null)
                    {
                        cf = thisCF;
                    }
                    else
                    {
                        cf.addAll(thisCF);
                    }
                }
                ColumnFamily cfPurged = major ? ColumnFamilyStore.removeDeleted(cf, gcBefore) : cf;
                if (cfPurged == null)
                    return null;
                ColumnFamily.serializer().serializeWithIndexes(cfPurged, buffer);
                cf = cfPurged;
            }
            else
            {
                assert rows.size() == 1;
                try
                {
                    rows.get(0).echoData(buffer);
                    cf = rows.get(0).getColumnFamily();
                }
                catch (IOException e)
                {
                    throw new IOError(e);
                }
            }
        }
        finally
        {
            rows.clear();
        }
        return new CompactedRow(key, buffer, cf);
        */
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
            minimum = scanners.peek().get().currentKey;
        }
        else
            minimum = mergeBuff.peek().key;

        // select any scanners with keys less than or equal to the minimum
        List<SSTableScanner> selected = null;
        while (!scanners.isEmpty() && comparator.compare(scanners.peek().get().currentKey, minimum) <= 0)
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
            // merge the first slice to the merge buffer
            mergeToBuff(scanner.get(), scanner.getColumns());

            // skip to the next slice
            if (scanner.next())
                // has more slices: reprioritize
                scanners.add(scanner);
        }
        return true;
    }

    /**
     * First, guarantees that the minimum keys for this iteration are contained in the
     * merge buffer. Then, while maintaining that guarantee, pops from the head of the
     * merge buffer, and apples tombstones until a valid column is found and returned.
     *
     * @return The next CompactionSlice for this iterator.
     */
    @Override
    public CompactionSlice computeNext()
    {
        /**
         * Whenever a Metadata entry reaches the
         * front of the merge buffer, a new output slice is created, which contains all
         * of the columns leading up to the next Metadata entry, or up to
         * (FIXME: move value elsewhere) SSTableWriter.TOTAL_MAX_SLICE_BYTES.
         *
         * FIXME
         */
        CompactionSlice outslice = null;
        if (ensureMergeBuffer())
        {

        }
        while (ensureMergeBuffer())
        {
            BufferEntry entry = mergeBuff.poll();
            if (entry instanceof ColumnEntry)
            {
                ColumnEntry colentry = (ColumnEntry)entry;
                assert outslice != null : "Slices must always contain metadata.";

                // FIXME: tombstone logic

                return outslice;
            }

            // popped a Metadata object: apply to columns that follow
            Slice.Metadata newmeta = 
            outmeta = ((MetadataEntry)entry).meta;
            //
            continue;
        }

        

        // no more columns
        return endOfData();
    }

    public void close() throws IOException
    {
        for (SSTableScanner scanner : scanners)
            scanner.close();
    }

    /**
     * Extends Slice to add a list of Columns.
     */
    public static final class CompactionSlice extends Slice
    {
        public final List<Column> columns;

        public CompactionSlice(ColumnKey key, Slice.Metadata meta, List<Column> columns)
        {                   
            this.key = key;
            this.meta = meta;
            this.columns = columns;
        }

        /**
         * Digest the parent portion of the key, the metadata and the content of each
         * column sequentially.
         *
         * NB: A sequence of columns with the same parents and metadata should always
         * result in the same digest, no matter how it is split.
         */
        public byte[] digest()
        {
            throw new RuntimeException("Not implemented."); // FIXME
        }
    }

    /**
     * Represents a tuple of (ColumnKey, (Metadata or Column)). Metadata entries in
     * the merge buffer play a similar role to the one they play on disk: they apply
     * metadata to all columns up to the next Metadata entry.
     *
     * A piece of Metadata with a key equal to the key of a column Column should sort
     * before the Column, in order to apply to it. For example, a piece of Metadata
     * representing a tombstone and beginning at the same key as a Column should apply
     * to and delete the Column during resolve().
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
    }

    class MetadataEntry extends BufferEntry
    {
        public final Slice.Metadata meta;
        public MetadataEntry(ColumnKey key, Slice.Metadata meta)
        {
            super(key);
            this.meta = meta;
        }
    }

    class ColumnEntry extends BufferEntry
    {
        public final Column column;
        public ColumnEntry(ColumnKey key, Column column)
        {
            super(key);
            this.column = column;
        }
    }
}

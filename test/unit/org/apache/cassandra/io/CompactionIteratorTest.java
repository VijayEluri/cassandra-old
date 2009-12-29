/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.cassandra.io;

import java.io.*;
import java.util.*;

import org.junit.Test;
import static org.junit.Assert.*;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.db.*;
import org.apache.cassandra.service.StorageService;

public class CompactionIteratorTest extends CleanupHelper
{
    public static final ColumnKey.Comparator COMPARATOR =
        ColumnKey.getComparator(SSTableUtils.TABLENAME, SSTableUtils.CFNAME);

    /**
     * Write N SSTables containing identical keys, but with the last containing
     * winning timestamps and values.
     *
     * Note: does not test compaction of metadata or GC.
     */
    @Test
    public void testCompactionMajor() throws IOException {
        final int numsstables = 2;
        final List<SSTableReader> readers = new ArrayList<SSTableReader>();

        TreeMap<ColumnKey, Column> map = new TreeMap<ColumnKey, Column>(COMPARATOR);
        for (int sstable = 0; sstable < numsstables; sstable++)
        {
            // the map for each sstable overwrites the previous
            for (int i = 0; i < 1000; i++)
            {
                byte[] name = Integer.toString(i).getBytes();
                ColumnKey key = new ColumnKey(StorageService.getPartitioner().decorateKey(Integer.toString(i)),
                                              name);
                map.put(key, new Column(name,
                                        ("Avinash Lakshman is a good man: " + i).getBytes(),
                                        // last sstable wins
                                        System.currentTimeMillis() + sstable));
            }

            // write
            readers.add(SSTableUtils.writeRawSSTable(SSTableUtils.TABLENAME,
                                                     SSTableUtils.CFNAME, map));
        }

        // compact the tables, and confirm that the output matches the last table
        long start = System.nanoTime();
        CompactionIterator ci = new CompactionIterator(readers, 0, true);
        Iterator<Map.Entry<ColumnKey, Column>> eiter = map.entrySet().iterator();
        while (ci.hasNext())
        {
            CompactionSlice slice = ci.next();
            for (Column col : slice.columns)
            {
                Map.Entry<ColumnKey, Column> entry = eiter.next();

                assert COMPARATOR.compare(entry.getKey(), slice.key, 0) == 0 :
                    "Slice key should share dk with column.";
                assert COMPARATOR.compareAt(entry.getValue().name(), col.name(), 1) == 0 :
                    "Column names should match.";
            }
        }
        System.out.println((System.nanoTime() - start) + "ns to compact " + readers);
        ci.close();

        assert !eiter.hasNext() : "Iterator contained : " + toString(eiter);
    }

    String toString(Iterator<?> i)
    {
        StringBuilder buff = new StringBuilder();
        while (i.hasNext())
            buff.append(i.next().toString() + ", ");
        return buff.toString();
    }
}

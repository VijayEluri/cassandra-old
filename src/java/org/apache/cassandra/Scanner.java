/**
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
 */

package org.apache.cassandra;

import java.io.*;
import java.util.Iterator;

import org.apache.cassandra.db.ColumnKey;
import org.apache.cassandra.db.filter.QueryFilter;

/**
 * A Scanner is an Iterator for reading slices in sorted order.
 */
public interface Scanner extends Iterator<ASlice>,Closeable
{
    /**
     * @return The Comparator for the returned Slices.
     */
    public ColumnKey.Comparator comparator();

    /**
     * @param filter A filter to be applied to the columns in returned Slices. Slices which have all of their columns
     * filtered out will still be returned in case the metadata needs to be applied elsewhere. To filter out entire
     * rows, it's more efficient to use an external filter like FilteredScanner, which seeks on the underlying scanner.
     */
    public void setColumnFilter(QueryFilter filter);

    /**
     * @return The approximate number of bytes remaining in the Scanner.
     */
    public long getBytesRemaining();

    /**
     * Releases any resources associated with this scanner.
     */
    public void close() throws IOException;
}

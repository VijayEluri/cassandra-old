/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.config;

import org.apache.cassandra.db.marshal.AbstractType;

public class CFMetaData
{
    public String tableName;            // name of table which has this column family
    public String cfName;               // name of the column family
    public String columnType;           // type: super, standard, etc.
    public AbstractType comparator;       // name sorted, time stamp sorted etc.
    public AbstractType subcolumnComparator; // like comparator, for supercolumns
    public String comment; // for humans only
    public int mergeFactor;             // tunable for read vs write performance

    // a quick and dirty pretty printer for describing the column family...
    public String pretty()
    {
        return tableName + "." + cfName + "\n"
               + "Column Family Type: " + columnType + "\n"
               + "Columns Sorted By: " + comparator + "\n";
    }
}

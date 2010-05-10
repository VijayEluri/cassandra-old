package org.apache.cassandra.db.filter;
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


import java.util.*;

import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.marshal.AbstractType;

public class NameMatchFilter implements IFilter<byte[]>
{
    private final Comparator<byte[]> comp;
    public final byte[] name;

    public NameMatchFilter(Comparator<byte[]> comp, byte[] name)
    {
        this.comp = comp;
        this.name = name;
    }

    @Override
    public MatchResult<byte[]> matchesBetween(byte[] begin, byte[] end)
    {
        if (comp.compare(end, name) < 0)
            // positioned before our name: instruct the scanner to seek forward
            return MatchResult.<byte[]>get(false, MatchResult.OP_SEEK, name);
        if (comp.compare(name, begin) < 0)
            // positioned after our name: indicate that we are finished
            return MatchResult.NOMATCH_DONE;
        return MatchResult.MATCH_CONT;
    }

    @Override
    public boolean matches(byte[] name)
    {
        return comp.compare(this.name, name) == 0;
    }

    @Override
    public String toString()
    {
        return "#<NameMatchFilter " + name + ">";
    }
}

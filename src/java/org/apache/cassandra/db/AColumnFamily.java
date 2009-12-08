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

package org.apache.cassandra.db;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.ICompactSerializer2;

public abstract class AColumnFamily
{
    /* The column serializer for all column families. Create based on config. */
    private static final ColumnFamilySerializer serializer_ = new ColumnFamilySerializer();
    public static final short utfPrefix_ = 2;   

    private static final Map<String, String> columnTypes_ = new HashMap<String, String>();
    static
    {
        /* TODO: These are the various column types. Hard coded for now. */
        columnTypes_.put("Standard", "Standard");
        columnTypes_.put("Super", "Super");
    }

    public static ColumnFamilySerializer serializer()
    {
        return serializer_;
    }

    public static String getColumnType(String key)
    {
    	if ( key == null )
    		return columnTypes_.get("Standard");
    	return columnTypes_.get(key);
    }

    public final String type;
    public final String name;

    private final transient ICompactSerializer2<IColumn> columnSerializer_;

    public AColumnFamily(String cfName, String columnType, ICompactSerializer2<IColumn> columnSerializer)
    {
        name = cfName;
        type = columnType;
        columnSerializer_ = columnSerializer;
    }

    /**
     * @return 'this' object if it is already mutable: otherwise, a mutable clone.
     */
    public abstract ColumnFamily asMutable();

    /**
     * @return A mutable, deep clone of this CF.
     */
    public ColumnFamily cloneMe()
    {
        ColumnFamily cf = cloneShallow();
        cf.columns_ = cloneColumns();
    	return cf;
    }

    /**
     * @return A mutable, shallow (no columns) clone of this CF.
     */
    public ColumnFamily cloneShallow()
    {
        ColumnFamily cf = new ColumnFamily(name, type, getComparator(), getSubComparator());
        cf.setMarkedForDeleteAt(getMarkedForDeleteAt());
        cf.setLocalDeletionTime(getLocalDeletionTime());
        return cf;
    }

    protected AbstractType getSubComparator()
    {
        return (columnSerializer_ instanceof SuperColumnSerializer) ? ((SuperColumnSerializer)columnSerializer_).getComparator() : null;
    }

    public ICompactSerializer2<IColumn> getColumnSerializer()
    {
    	return columnSerializer_;
    }

    public abstract int size();

    int getColumnCount()
    {
    	int count = 0;
        if(!isSuper())
        {
            count = getColumns().size();
        }
        else
        {
            for(IColumn column : getColumns())
            {
                count += column.getObjectCount();
            }
        }
    	return count;
    }

    public boolean isSuper()
    {
        return type.equals("Super");
    }

    /*
     * This function will calculate the difference between 2 column families.
     * The external input is assumed to be a superset of internal.
     */
    public ColumnFamily diff(AColumnFamily cfComposite)
    {
    	ColumnFamily cfDiff = new ColumnFamily(cfComposite.name, cfComposite.type, getComparator(), getSubComparator());
        if (cfComposite.getMarkedForDeleteAt() > getMarkedForDeleteAt())
        {
            cfDiff.delete(cfComposite.getLocalDeletionTime(), cfComposite.getMarkedForDeleteAt());
        }

        // (don't need to worry about cfNew containing IColumns that are shadowed by
        // the delete tombstone, since cfNew was generated by CF.resolve, which
        // takes care of those for us.)
        // TODO: perform N merge, rather than Nlog(N) iterate and lookup
        for (IColumn columnExternal : cfComposite.getColumns())
        {
            byte[] cName = columnExternal.name();
            IColumn columnInternal = getColumn(cName);
            if (columnInternal == null)
            {
                cfDiff.addColumn(columnExternal);
            }
            else
            {
                IColumn columnDiff = columnInternal.diff(columnExternal);
                if (columnDiff != null)
                {
                    cfDiff.addColumn(columnDiff);
                }
            }
        }

        if (!cfDiff.getColumns().isEmpty() || cfDiff.isMarkedForDelete())
        	return cfDiff;
        else
        	return null;
    }

    public int hashCode()
    {
        return name.hashCode();
    }

    public boolean equals(Object o)
    {
        if ( !(o instanceof AColumnFamily) )
            return false;
        AColumnFamily cf = (AColumnFamily)o;
        return name.equals(cf.name);
    }

    public String toString()
    {
    	StringBuilder sb = new StringBuilder();
        sb.append("ColumnFamily(");
    	sb.append(name);

        if (isMarkedForDelete()) {
            sb.append(" -delete at " + getMarkedForDeleteAt() + "-");
        }

    	sb.append(" [");
        sb.append(getComparator().getColumnsString(getColumns()));
        sb.append("])");

    	return sb.toString();
    }

    public static byte[] digest(AColumnFamily cf)
    {
        MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance("MD5");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new AssertionError(e);
        }
        if (cf != null)
            cf.updateDigest(digest);

        return digest.digest();
    }

    public void updateDigest(MessageDigest digest)
    {
        for (IColumn column : getColumns())
        {
            column.updateDigest(digest);
        }
    }

    /**
     * @return An optionally mutable sorted collection of the columns in this family.
     */
    public abstract Collection<IColumn> getColumns();

    /**
     * @return The column with given name, or null if it does not exist.
     */
    public abstract IColumn getColumn(byte[] name);

    /**
     * @return A mutable clone of the columns in this family.
     */
    protected abstract ConcurrentSkipListMap<byte[],IColumn> cloneColumns();

    public boolean isMarkedForDelete()
    {
        return getMarkedForDeleteAt() > Long.MIN_VALUE;
    }

    public abstract long getMarkedForDeleteAt();
    public abstract int getLocalDeletionTime();

    public abstract AbstractType getComparator();

    String getComparatorName()
    {
        return getComparator().getClass().getCanonicalName();
    }

    String getSubComparatorName()
    {
        AbstractType subcolumnComparator = getSubComparator();
        return subcolumnComparator == null ? "" : subcolumnComparator.getClass().getCanonicalName();
    }

    public int serializedSize()
    {
        int subtotal = 4 * IColumn.UtfPrefix_ + name.length() + type.length() +  getComparatorName().length() + getSubComparatorName().length() + 4 + 8 + 4;
        for (IColumn column : getColumns())
        {
            subtotal += column.serializedSize();
        }
        return subtotal;
    }

    public static AbstractType getComparatorFor(String table, String columnFamilyName, byte[] superColumnName)
    {
        return superColumnName == null
               ? DatabaseDescriptor.getComparator(table, columnFamilyName)
               : DatabaseDescriptor.getSubComparator(table, columnFamilyName);
    }

    public static AColumnFamily diff(AColumnFamily cf1, AColumnFamily cf2)
    {
        if (cf1 == null)
            return cf2;
        return cf1.diff(cf2);
    }

    public static AColumnFamily resolve(ColumnFamily cf1, AColumnFamily cf2)
    {
        if (cf1 == null)
            return cf2;
        cf1.resolve(cf2);
        return cf1;
    }
}

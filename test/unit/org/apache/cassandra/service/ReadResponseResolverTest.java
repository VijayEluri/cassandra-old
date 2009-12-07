package org.apache.cassandra.service;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.apache.cassandra.db.AColumnFamily;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.Row;
import static org.apache.cassandra.db.TableTest.assertColumns;
import static org.apache.cassandra.Util.column;
import static junit.framework.Assert.assertNull;

public class ReadResponseResolverTest
{
    @Test
    public void testResolveSupersetNewer()
    {
        List<AColumnFamily> cfs = new ArrayList<AColumnFamily>();
        ColumnFamily cf1 = ColumnFamily.create("Keyspace1", "Standard1");
        cf1.addColumn(column("c1", "v1", 0));
        cfs.add(cf1);

        ColumnFamily cf2 = ColumnFamily.create("Keyspace1", "Standard1");
        cf2.addColumn(column("c1", "v2", 1));
        cfs.add(cf2);

        ColumnFamily resolved = ReadResponseResolver.resolveSuperset(cfs);
        assertColumns(resolved, "c1");
        assertColumns(ColumnFamily.diff(cf1, resolved), "c1");
        assertNull(ColumnFamily.diff(cf2, resolved));
    }

    @Test
    public void testResolveSupersetDisjoint()
    {
        List<AColumnFamily> cfs = new ArrayList<AColumnFamily>();
        ColumnFamily cf1 = ColumnFamily.create("Keyspace1", "Standard1");
        cf1.addColumn(column("c1", "v1", 0));
        cfs.add(cf1);

        ColumnFamily cf2 = ColumnFamily.create("Keyspace1", "Standard1");
        cf2.addColumn(column("c2", "v2", 1));
        cfs.add(cf2);

        ColumnFamily resolved = ReadResponseResolver.resolveSuperset(cfs);
        assertColumns(resolved, "c1", "c2");
        assertColumns(ColumnFamily.diff(cf1, resolved), "c2");
        assertColumns(ColumnFamily.diff(cf2, resolved), "c1");
    }

    @Test
    public void testResolveSupersetNullOne()
    {
        ColumnFamily cf2 = ColumnFamily.create("Keyspace1", "Standard1");
        cf2.addColumn(column("c2", "v2", 1));

        ColumnFamily resolved = ReadResponseResolver.resolveSuperset(Arrays.asList(null, (AColumnFamily)cf2));
        assertColumns(resolved, "c2");
        assertColumns(ColumnFamily.diff(null, resolved), "c2");
        assertNull(ColumnFamily.diff(cf2, resolved));
    }

    @Test
    public void testResolveSupersetNullTwo()
    {
        ColumnFamily cf1 = ColumnFamily.create("Keyspace1", "Standard1");
        cf1.addColumn(column("c1", "v1", 0));

        ColumnFamily resolved = ReadResponseResolver.resolveSuperset(Arrays.asList((AColumnFamily)cf1, null));
        assertColumns(resolved, "c1");
        assertNull(ColumnFamily.diff(cf1, resolved));
        assertColumns(ColumnFamily.diff(null, resolved), "c1");
    }

    @Test
    public void testResolveSupersetNullBoth()
    {
        assertNull(ReadResponseResolver.resolveSuperset(Arrays.<AColumnFamily>asList(null, null)));
    }
}

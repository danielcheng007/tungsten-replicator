/**
 * Tungsten: An Application Server for uni/cluster.
 * Copyright (C) 2014 Continuent Inc.
 * Contact: tungsten@continuent.org
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of version 2 of the GNU General Public License as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA
 *
 * Initial developer(s): Robert Hodges
 * Contributor(s): 
 */

package com.continuent.tungsten.replicator.applier.batch;

import java.io.File;
import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.apache.log4j.Logger;
import org.junit.Test;

import com.continuent.tungsten.common.config.TungstenProperties;
import com.continuent.tungsten.replicator.conf.ReplicatorMonitor;
import com.continuent.tungsten.replicator.conf.ReplicatorRuntime;
import com.continuent.tungsten.replicator.event.EventGenerationHelper;
import com.continuent.tungsten.replicator.event.ReplDBMSEvent;
import com.continuent.tungsten.replicator.event.ReplDBMSHeader;
import com.continuent.tungsten.replicator.management.MockEventDispatcher;
import com.continuent.tungsten.replicator.management.MockOpenReplicatorContext;
import com.continuent.tungsten.replicator.pipeline.Pipeline;
import com.continuent.tungsten.replicator.storage.InMemoryQueueStore;

/**
 * Tests functioning of batch apply using a file data source. This enables the
 * test to exercise major features of the applier including CSV writing and load
 * script execution.
 */
public class TestBatchApply
{
    private static Logger         logger         = Logger.getLogger(TestBatchApply.class);

    // private static Logger logger = Logger.getLogger(TestBatchApply.class);
    private BatchApplyHelper      helper         = new BatchApplyHelper();
    private EventGenerationHelper eventGenerator = new EventGenerationHelper();

    // Pipeline variables.
    ReplicatorRuntime             runtime;
    Pipeline                      pipeline;

    /**
     * Test setting up a batch applier and shutting down again.
     */
    @Test
    public void testBatchStartStop() throws Exception
    {
        String service = "testBatchStartStop";
        File testDir = helper.prepareTestDir(service);
        TungstenProperties config = helper.generateBatchApplyProps(testDir,
                service, false);
        configureAndStartPipeline(config);
        pipeline.shutdown(false);
        pipeline.release(runtime);
    }

    /**
     * Validate that we can set up a batch applier, write a single row to
     * output, then find that row in the CSV data afterwards. After shutdown we
     * should also see that all methods of the load script were called.
     */
    @Test
    public void testBatchSimpleApply() throws Exception
    {
        // Create the pipeline.
        String service = "testBatchSimpleApply";
        File testDir = helper.prepareTestDir(service);
        TungstenProperties config = helper.generateBatchApplyProps(testDir,
                service, false);
        configureAndStartPipeline(config);

        // Find the store.
        InMemoryQueueStore queue = (InMemoryQueueStore) pipeline
                .getStore("queue");

        // Add a single transaction.
        String names[] = new String[2];
        Integer values[] = new Integer[2];
        for (int i = 0; i < names.length; i++)
        {
            names[i] = "data-" + i;
            values[i] = i;
        }
        ReplDBMSEvent anEvent = eventGenerator.eventFromRowInsert(0, "schema",
                "table", names, values, 0, true);
        queue.put(anEvent);

        // Wait for the transaction to be committed.
        Future<ReplDBMSHeader> wait = pipeline.watchForCommittedSequenceNumber(
                0, false);
        ReplDBMSHeader lastEvent = wait.get(10, TimeUnit.SECONDS);
        Assert.assertEquals("Expected 1 sequence number", 0,
                lastEvent.getSeqno());

        // Shutdown.
        pipeline.shutdown(false);
        pipeline.release(runtime);

        // Confirm that all methods on the load script were called.
        helper.assertFileExistence(testDir, "prepare.stat",
                "File generated by prepare call");
        helper.assertFileExistence(testDir, "begin.stat",
                "File generated by begin call");
        helper.assertFileExistence(testDir, "apply.stat",
                "File generated by apply call");
        helper.assertFileExistence(testDir, "commit.stat",
                "File generated by commit call");
        helper.assertFileExistence(testDir, "release.stat",
                "File generated by release call");
    }

    /**
     * Validate that that we can configure a partitioner to divide CSV output
     * files by the hour in which transactions commit. The case works by adding
     * inputs with commit timestamps set an hour apart that should force data to
     * partition as many fields as there are transactions.
     */
    @Test
    public void testBatchPartitionedApply() throws Exception
    {
        // Create the pipeline. We set options to partition data by
        // hour on the tungsten_commit_timestamp column.
        String service = "testBatchPartitionedApply";
        File testDir = helper.prepareTestDir(service);
        TungstenProperties config = helper.generateBatchApplyProps(testDir,
                service, false);
        config.set("replicator.applier.batch-applier.partitionBy",
                "tungsten_commit_timestamp");
        config.set("replicator.applier.batch-applier.partitionByClass",
                DateTimeValuePartitioner.class.getName());
        config.set("replicator.applier.batch-applier.partitionByFormat",
                "'commit_date='yyyy-MM-dd'-commit_hour='HH");
        configureAndStartPipeline(config);

        // Find the store.
        InMemoryQueueStore queue = (InMemoryQueueStore) pipeline
                .getStore("queue");

        // Add three transactions with commit times an hour apart from each
        // other.
        long startTime = System.currentTimeMillis();
        for (int t = 0; t < 3; t++)
        {
            // Create commit timestamp with 1 hour offset for each succeeding
            // transaction.
            long commitTime = startTime + (t * 3600 * 1000);
            Timestamp commitTimestamp = new Timestamp(commitTime);

            // Generate and enqueue a
            String names[] = new String[2];
            Integer values[] = new Integer[2];
            for (int i = 0; i < names.length; i++)
            {
                // Generate
                names[i] = "data-" + t + "-" + i;
                values[i] = i;
            }
            ReplDBMSEvent anEvent = eventGenerator.eventFromRowInsert(t,
                    "schema", "table", names, values, 0, true, commitTimestamp);
            queue.put(anEvent);
        }

        // Wait for the transaction to be committed.
        Future<ReplDBMSHeader> wait = pipeline.watchForCommittedSequenceNumber(
                2, false);
        ReplDBMSHeader lastEvent = wait.get(10, TimeUnit.SECONDS);
        Assert.assertEquals("Expected end seqno", 2, lastEvent.getSeqno());

        // Confirm that there are three output files with expected
        // partition names.
        List<String> partitionNames = new LinkedList<String>();
        for (String child : testDir.list())
        {
            if (child.contains("commit_date=") && child.contains("commit_hour"))
            {
                partitionNames.add(child);
                logger.info("Found partition name:" + child);
            }
        }
        Assert.assertEquals("Checking for expected number of CSV partitions",
                3, partitionNames.size());

        // Shutdown.
        pipeline.shutdown(false);
        pipeline.release(runtime);
    }

    /**
     * Create runtime and start the pipeline.
     */
    private void configureAndStartPipeline(TungstenProperties config)
            throws Exception
    {
        ReplicatorRuntime runtime = new ReplicatorRuntime(config,
                new MockOpenReplicatorContext(),
                ReplicatorMonitor.getInstance());
        runtime.configure();
        runtime.prepare();
        pipeline = runtime.getPipeline();
        pipeline.start(new MockEventDispatcher());
    }
}
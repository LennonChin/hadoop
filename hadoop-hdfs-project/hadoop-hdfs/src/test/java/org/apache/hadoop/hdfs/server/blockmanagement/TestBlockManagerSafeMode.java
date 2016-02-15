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
package org.apache.hadoop.hdfs.server.blockmanagement;

import com.google.common.base.Supplier;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs.BlockReportReplica;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManagerSafeMode.BMSafeModeStatus;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NamenodeRole;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.test.GenericTestUtils;

import org.junit.Before;
import org.junit.Test;

import org.mockito.internal.util.reflection.Whitebox;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * This test is for testing {@link BlockManagerSafeMode} package local APIs.
 *
 * They use heavily mocked objects, treating the {@link BlockManagerSafeMode}
 * as white-box. Tests are light-weight thus no multi-thread scenario or real
 * mini-cluster is tested.
 *
 * @see org.apache.hadoop.hdfs.TestSafeMode
 * @see org.apache.hadoop.hdfs.server.namenode.ha.TestHASafeMode
 * @see org.apache.hadoop.hdfs.TestSafeModeWithStripedFile
 */
public class TestBlockManagerSafeMode {
  private static final int DATANODE_NUM = 3;
  private static final long BLOCK_TOTAL = 10;
  private static final double THRESHOLD = 0.99;
  private static final long BLOCK_THRESHOLD = (long)(BLOCK_TOTAL * THRESHOLD);
  private static final int EXTENSION = 1000; // 1 second

  private BlockManager bm;
  private DatanodeManager dn;
  private BlockManagerSafeMode bmSafeMode;

  /**
   * Set up the mock context.
   *
   * - extension is always needed (default period is {@link #EXTENSION} ms
   * - datanode threshold is always reached via mock
   * - safe block is 0 and it needs {@link #BLOCK_THRESHOLD} to reach threshold
   * - write/read lock is always held by current thread
   *
   * @throws IOException
   */
  @Before
  public void setupMockCluster() throws IOException {
    Configuration conf = new HdfsConfiguration();
    conf.setDouble(DFSConfigKeys.DFS_NAMENODE_SAFEMODE_THRESHOLD_PCT_KEY,
        THRESHOLD);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_SAFEMODE_EXTENSION_KEY,
        EXTENSION);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_SAFEMODE_MIN_DATANODES_KEY,
        DATANODE_NUM);

    FSNamesystem fsn = mock(FSNamesystem.class);
    doReturn(true).when(fsn).hasWriteLock();
    doReturn(true).when(fsn).hasReadLock();
    doReturn(true).when(fsn).isRunning();
    NameNode.initMetrics(conf, NamenodeRole.NAMENODE);

    bm = spy(new BlockManager(fsn, false, conf));
    doReturn(true).when(bm).isGenStampInFuture(any(Block.class));
    dn = spy(bm.getDatanodeManager());
    Whitebox.setInternalState(bm, "datanodeManager", dn);
    // the datanode threshold is always met
    when(dn.getNumLiveDataNodes()).thenReturn(DATANODE_NUM);

    bmSafeMode = new BlockManagerSafeMode(bm, fsn, false, conf);
  }

  /**
   * Test set block total.
   *
   * The block total is set which will call checkSafeMode for the first time
   * and bmSafeMode transfers from OFF to PENDING_THRESHOLD status
   */
  @Test(timeout = 30000)
  public void testInitialize() {
    assertFalse("Block manager should not be in safe mode at beginning.",
        bmSafeMode.isInSafeMode());
    bmSafeMode.activate(BLOCK_TOTAL);
    assertEquals(BMSafeModeStatus.PENDING_THRESHOLD, getSafeModeStatus());
    assertTrue(bmSafeMode.isInSafeMode());
  }

  /**
   * Test the state machine transition.
   */
  @Test(timeout = 30000)
  public void testCheckSafeMode() {
    bmSafeMode.activate(BLOCK_TOTAL);

    // stays in PENDING_THRESHOLD: pending block threshold
    setSafeModeStatus(BMSafeModeStatus.PENDING_THRESHOLD);
    for (long i = 0; i < BLOCK_THRESHOLD; i++) {
      setBlockSafe(i);
      bmSafeMode.checkSafeMode();
      assertEquals(BMSafeModeStatus.PENDING_THRESHOLD, getSafeModeStatus());
    }

    // PENDING_THRESHOLD -> EXTENSION
    Whitebox.setInternalState(bmSafeMode, "extension", Integer.MAX_VALUE);
    setSafeModeStatus(BMSafeModeStatus.PENDING_THRESHOLD);
    setBlockSafe(BLOCK_THRESHOLD);
    bmSafeMode.checkSafeMode();
    assertEquals(BMSafeModeStatus.EXTENSION, getSafeModeStatus());

    // PENDING_THRESHOLD -> OFF
    Whitebox.setInternalState(bmSafeMode, "extension", 0);
    setSafeModeStatus(BMSafeModeStatus.PENDING_THRESHOLD);
    setBlockSafe(BLOCK_THRESHOLD);
    bmSafeMode.checkSafeMode();
    assertEquals(BMSafeModeStatus.OFF, getSafeModeStatus());

    // stays in EXTENSION
    setBlockSafe(0);
    setSafeModeStatus(BMSafeModeStatus.EXTENSION);
    Whitebox.setInternalState(bmSafeMode, "extension", 0);
    bmSafeMode.checkSafeMode();
    assertEquals(BMSafeModeStatus.EXTENSION, getSafeModeStatus());

    // stays in EXTENSION: pending extension period
    Whitebox.setInternalState(bmSafeMode, "extension", Integer.MAX_VALUE);
    setSafeModeStatus(BMSafeModeStatus.EXTENSION);
    setBlockSafe(BLOCK_THRESHOLD);
    bmSafeMode.checkSafeMode();
    assertEquals(BMSafeModeStatus.EXTENSION, getSafeModeStatus());
  }

  /**
   * Test that the block safe increases up to block threshold.
   *
   * Once the block threshold is reached, the block manger leaves safe mode and
   * increment will be a no-op.
   * The safe mode status lifecycle: OFF -> PENDING_THRESHOLD -> OFF
   */
  @Test(timeout = 30000)
  public void testIncrementSafeBlockCount() {
    bmSafeMode.activate(BLOCK_TOTAL);
    Whitebox.setInternalState(bmSafeMode, "extension", 0);

    for (long i = 1; i <= BLOCK_TOTAL; i++) {
      BlockInfo blockInfo = mock(BlockInfo.class);
      doReturn(false).when(blockInfo).isStriped();
      bmSafeMode.incrementSafeBlockCount(1, blockInfo);
      if (i < BLOCK_THRESHOLD) {
        assertEquals(i, getblockSafe());
        assertTrue(bmSafeMode.isInSafeMode());
      } else {
        // block manager leaves safe mode if block threshold is met
        assertFalse(bmSafeMode.isInSafeMode());
        // the increment will be a no-op if safe mode is OFF
        assertEquals(BLOCK_THRESHOLD, getblockSafe());
      }
    }
  }

  /**
   * Test that the block safe increases up to block threshold.
   *
   * Once the block threshold is reached, the block manger leaves safe mode and
   * increment will be a no-op.
   * The safe mode status lifecycle: OFF -> PENDING_THRESHOLD -> EXTENSION-> OFF
   */
  @Test(timeout = 30000)
  public void testIncrementSafeBlockCountWithExtension() throws Exception {
    bmSafeMode.activate(BLOCK_TOTAL);

    for (long i = 1; i <= BLOCK_TOTAL; i++) {
      BlockInfo blockInfo = mock(BlockInfo.class);
      doReturn(false).when(blockInfo).isStriped();
      bmSafeMode.incrementSafeBlockCount(1, blockInfo);
      if (i < BLOCK_THRESHOLD) {
        assertTrue(bmSafeMode.isInSafeMode());
      }
    }
    waitForExtensionPeriod();
    assertFalse(bmSafeMode.isInSafeMode());
  }

  /**
   * Test that the block safe decreases the block safe.
   *
   * The block manager stays in safe mode.
   * The safe mode status lifecycle: OFF -> PENDING_THRESHOLD
   */
  @Test(timeout = 30000)
  public void testDecrementSafeBlockCount() {
    bmSafeMode.activate(BLOCK_TOTAL);
    Whitebox.setInternalState(bmSafeMode, "extension", 0);

    mockBlockManagerForBlockSafeDecrement();
    setBlockSafe(BLOCK_THRESHOLD);
    for (long i = BLOCK_THRESHOLD; i > 0; i--) {
      BlockInfo blockInfo = mock(BlockInfo.class);
      bmSafeMode.decrementSafeBlockCount(blockInfo);

      assertEquals(i - 1, getblockSafe());
      assertTrue(bmSafeMode.isInSafeMode());
    }
  }

  /**
   * Test when the block safe increment and decrement interleave.
   *
   * Both the increment and decrement will be a no-op if the safe mode is OFF.
   * The safe mode status lifecycle: OFF -> PENDING_THRESHOLD -> OFF
   */
  @Test(timeout = 30000)
  public void testIncrementAndDecrementSafeBlockCount() {
    bmSafeMode.activate(BLOCK_TOTAL);
    Whitebox.setInternalState(bmSafeMode, "extension", 0);

    mockBlockManagerForBlockSafeDecrement();
    for (long i = 1; i <= BLOCK_TOTAL; i++) {
      BlockInfo blockInfo = mock(BlockInfo.class);
      doReturn(false).when(blockInfo).isStriped();

      bmSafeMode.incrementSafeBlockCount(1, blockInfo);
      bmSafeMode.decrementSafeBlockCount(blockInfo);
      bmSafeMode.incrementSafeBlockCount(1, blockInfo);

      if (i < BLOCK_THRESHOLD) {
        assertEquals(i, getblockSafe());
        assertTrue(bmSafeMode.isInSafeMode());
      } else {
        // block manager leaves safe mode if block threshold is met
        assertEquals(BLOCK_THRESHOLD, getblockSafe());
        assertFalse(bmSafeMode.isInSafeMode());
      }
    }
  }

  /**
   * Test the safe mode monitor.
   *
   * The monitor will make block manager leave the safe mode after  extension
   * period.
   */
  @Test(timeout = 30000)
  public void testSafeModeMonitor() throws Exception {
    bmSafeMode.activate(BLOCK_TOTAL);

    setBlockSafe(BLOCK_THRESHOLD);
    // PENDING_THRESHOLD -> EXTENSION
    bmSafeMode.checkSafeMode();

    assertTrue(bmSafeMode.isInSafeMode());
    waitForExtensionPeriod();
    assertFalse(bmSafeMode.isInSafeMode());
  }

  /**
   * Test block manager won't leave safe mode if datanode threshold is not met.
   */
  @Test(timeout = 30000)
  public void testDatanodeThreshodShouldBeMet() throws Exception {
    bmSafeMode.activate(BLOCK_TOTAL);

    // All datanode have not registered yet.
    when(dn.getNumLiveDataNodes()).thenReturn(1);
    setBlockSafe(BLOCK_THRESHOLD);
    bmSafeMode.checkSafeMode();
    assertTrue(bmSafeMode.isInSafeMode());

    // The datanode number reaches threshold after all data nodes register
    when(dn.getNumLiveDataNodes()).thenReturn(DATANODE_NUM);
    bmSafeMode.checkSafeMode();
    waitForExtensionPeriod();
    assertFalse(bmSafeMode.isInSafeMode());
  }

  /**
   * Test block manager won't leave safe mode if there are blocks with
   * generation stamp (GS) in future.
   */
  @Test(timeout = 30000)
  public void testStayInSafeModeWhenBytesInFuture() throws Exception {
    bmSafeMode.activate(BLOCK_TOTAL);

    // Inject blocks with future GS
    injectBlocksWithFugureGS(100L);
    assertEquals(100L, bmSafeMode.getBytesInFuture());

    // safe blocks are enough
   setBlockSafe(BLOCK_THRESHOLD);

    // PENDING_THRESHOLD -> EXTENSION
    bmSafeMode.checkSafeMode();

    assertFalse("Shouldn't leave safe mode in case of blocks with future GS! ",
        bmSafeMode.leaveSafeMode(false));
    assertTrue("Leaving safe mode forcefully should succeed regardless of " +
        "blocks with future GS.", bmSafeMode.leaveSafeMode(true));
    assertEquals("Number of blocks with future GS should have been cleared " +
        "after leaving safe mode", 0L, bmSafeMode.getBytesInFuture());
    assertTrue("Leaving safe mode should succeed after blocks with future GS " +
        "are cleared.", bmSafeMode.leaveSafeMode(false));
  }

  /**
   * Test get safe mode tip.
   */
  @Test(timeout = 30000)
  public void testGetSafeModeTip() throws Exception {
    bmSafeMode.activate(BLOCK_TOTAL);
    String tip = bmSafeMode.getSafeModeTip();
    assertTrue(tip.contains(
        String.format(
            "The reported blocks %d needs additional %d blocks to reach the " +
                "threshold %.4f of total blocks %d.%n",
            0, BLOCK_THRESHOLD, THRESHOLD, BLOCK_TOTAL)));
    assertTrue(tip.contains(
        String.format("The number of live datanodes %d has reached the " +
            "minimum number %d. ", dn.getNumLiveDataNodes(), DATANODE_NUM)));
    assertTrue(tip.contains("Safe mode will be turned off automatically once " +
        "the thresholds have been reached."));

    // safe blocks are enough
    setBlockSafe(BLOCK_THRESHOLD);
    bmSafeMode.checkSafeMode();
    tip = bmSafeMode.getSafeModeTip();
    assertTrue(tip.contains(
        String.format("The reported blocks %d has reached the threshold"
                + " %.4f of total blocks %d. ",
            getblockSafe(), THRESHOLD, BLOCK_TOTAL)));
    assertTrue(tip.contains(
        String.format("The number of live datanodes %d has reached the " +
            "minimum number %d. ", dn.getNumLiveDataNodes(), DATANODE_NUM)));
    assertTrue(tip.contains("In safe mode extension. Safe mode will be turned" +
        " off automatically in"));

    waitForExtensionPeriod();
    tip = bmSafeMode.getSafeModeTip();
    assertTrue(tip.contains(
        String.format("The reported blocks %d has reached the threshold"
                + " %.4f of total blocks %d. ",
            getblockSafe(), THRESHOLD, BLOCK_TOTAL)));
    assertTrue(tip.contains(
        String.format("The number of live datanodes %d has reached the " +
            "minimum number %d. ", dn.getNumLiveDataNodes(), DATANODE_NUM)));
    assertTrue(tip.contains("Safe mode will be turned off automatically soon"));
  }

  /**
   * Test get safe mode tip in case of blocks with future GS.
   */
  @Test(timeout = 30000)
  public void testGetSafeModeTipForBlocksWithFutureGS() throws Exception {
    bmSafeMode.activate(BLOCK_TOTAL);

    injectBlocksWithFugureGS(40L);
    String tip = bmSafeMode.getSafeModeTip();
    assertTrue(tip.contains(
        String.format(
            "The reported blocks %d needs additional %d blocks to reach the " +
                "threshold %.4f of total blocks %d.%n",
            0, BLOCK_THRESHOLD, THRESHOLD, BLOCK_TOTAL)));
    assertTrue(tip.contains(
        "Name node detected blocks with generation stamps " +
            "in future. This means that Name node metadata is inconsistent. " +
            "This can happen if Name node metadata files have been manually " +
            "replaced. Exiting safe mode will cause loss of " +
            40 + " byte(s). Please restart name node with " +
            "right metadata or use \"hdfs dfsadmin -safemode forceExit\" " +
            "if you are certain that the NameNode was started with the " +
            "correct FsImage and edit logs. If you encountered this during " +
            "a rollback, it is safe to exit with -safemode forceExit."
    ));
    assertFalse(tip.contains("Safe mode will be turned off"));

    // blocks with future GS were already injected before.
    setBlockSafe(BLOCK_THRESHOLD);
    tip = bmSafeMode.getSafeModeTip();
    assertTrue(tip.contains(
        String.format("The reported blocks %d has reached the threshold"
                + " %.4f of total blocks %d. ",
            getblockSafe(), THRESHOLD, BLOCK_TOTAL)));
    assertTrue(tip.contains(
        "Name node detected blocks with generation stamps " +
            "in future. This means that Name node metadata is inconsistent. " +
            "This can happen if Name node metadata files have been manually " +
            "replaced. Exiting safe mode will cause loss of " +
            40 + " byte(s). Please restart name node with " +
            "right metadata or use \"hdfs dfsadmin -safemode forceExit\" " +
            "if you are certain that the NameNode was started with the " +
            "correct FsImage and edit logs. If you encountered this during " +
            "a rollback, it is safe to exit with -safemode forceExit."
    ));
    assertFalse(tip.contains("Safe mode will be turned off"));
  }

  /**
   * Mock block manager internal state for decrement safe block.
   */
  private void mockBlockManagerForBlockSafeDecrement() {
    BlockInfo storedBlock = mock(BlockInfo.class);
    when(storedBlock.isComplete()).thenReturn(true);
    doReturn(storedBlock).when(bm).getStoredBlock(any(Block.class));
    NumberReplicas numberReplicas = mock(NumberReplicas.class);
    when(numberReplicas.liveReplicas()).thenReturn(0);
    doReturn(numberReplicas).when(bm).countNodes(any(Block.class));
  }

  /**
   * Wait the bmSafeMode monitor for the extension period.
   * @throws InterruptedIOException
   * @throws TimeoutException
   */
  private void waitForExtensionPeriod() throws Exception{
    assertEquals(BMSafeModeStatus.EXTENSION, getSafeModeStatus());

    GenericTestUtils.waitFor(new Supplier<Boolean>() {
        @Override
        public Boolean get() {
          return getSafeModeStatus() != BMSafeModeStatus.EXTENSION;
        }
    }, EXTENSION / 10, EXTENSION * 2);
  }

  private void injectBlocksWithFugureGS(long numBytesInFuture) {
    BlockReportReplica brr = mock(BlockReportReplica.class);
    when(brr.getBytesOnDisk()).thenReturn(numBytesInFuture);
    bmSafeMode.checkBlocksWithFutureGS(brr);
  }

  private void setSafeModeStatus(BMSafeModeStatus status) {
    Whitebox.setInternalState(bmSafeMode, "status", status);
  }

  private BMSafeModeStatus getSafeModeStatus() {
    return (BMSafeModeStatus)Whitebox.getInternalState(bmSafeMode, "status");
  }

  private void setBlockSafe(long blockSafe) {
    Whitebox.setInternalState(bmSafeMode, "blockSafe", blockSafe);
  }

  private long getblockSafe() {
    return (long)Whitebox.getInternalState(bmSafeMode, "blockSafe");
  }
}

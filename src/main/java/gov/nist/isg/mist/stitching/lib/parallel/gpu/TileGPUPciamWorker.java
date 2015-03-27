// ================================================================
//
// Disclaimer: IMPORTANT: This software was developed at the National
// Institute of Standards and Technology by employees of the Federal
// Government in the course of their official duties. Pursuant to
// title 17 Section 105 of the United States Code this software is not
// subject to copyright protection and is in the public domain. This
// is an experimental system. NIST assumes no responsibility
// whatsoever for its use by other parties, and makes no guarantees,
// expressed or implied, about its quality, reliability, or any other
// characteristic. We would appreciate acknowledgement if the software
// is used. This software can be redistributed and/or modified freely
// provided that any derivative works bear some notice that they are
// derived from it, and any modified versions bear some notice that
// they have been modified.
//
// ================================================================

// ================================================================
//
// Author: tjb3
// Date: Apr 11, 2014 12:28:44 PM EST
//
// Time-stamp: <Apr 11, 2014 12:28:44 PM tjb3>
//
//
// ================================================================

package gov.nist.isg.mist.stitching.lib.parallel.gpu;

import gov.nist.isg.mist.stitching.gui.executor.StitchingExecutor;
import gov.nist.isg.mist.stitching.lib.log.Debug;
import gov.nist.isg.mist.stitching.lib.log.Debug.DebugType;
import gov.nist.isg.mist.stitching.lib.log.Log;
import gov.nist.isg.mist.stitching.lib.log.Log.LogType;
import jcuda.Sizeof;
import jcuda.driver.*;
import gov.nist.isg.mist.stitching.lib.imagetile.ImageTile;
import gov.nist.isg.mist.stitching.lib.imagetile.Stitching;
import gov.nist.isg.mist.stitching.lib.imagetile.jcuda.CudaImageTile;
import gov.nist.isg.mist.stitching.lib.imagetile.jcuda.CudaStitching;
import gov.nist.isg.mist.stitching.lib.imagetile.memory.TileWorkerMemory;
import gov.nist.isg.mist.stitching.lib.parallel.common.StitchingTask;
import gov.nist.isg.mist.stitching.lib.parallel.common.StitchingTask.TaskType;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Class that computes the PCIAM (phase correlation image alignment method) of a tile on the GPU.
 * One thread per GPU is used.
 * 
 * @author Tim Blattner
 * @version 1.0
 * @param <T>
 */
public class TileGPUPciamWorker<T> implements Runnable {

  private PriorityBlockingQueue<StitchingTask<T>> workQueue;
  private PriorityBlockingQueue<StitchingTask<T>> bkQueue;
  private PriorityBlockingQueue<StitchingTask<T>> ccfQueue;

  private static boolean bkDone = false;

  private TileWorkerMemory memory;

  private CUstream stream;

  private CUcontext context;
  private CUdevice device;

  private int devID;
  private int threadID;

  private CUcontext[] peerContexts;
  private int[] peerDevIds;

  private HashSet<Integer> nonPeerDevIds;
  private HashMap<Integer, CUcontext> peerContextMap;
  private CUdeviceptr devMem;

  private StitchingExecutor executor;

  private ImageTile<T> initTile;
  
  private volatile boolean isCancelled;


  /**
   * Initializes a tile worker pool for computing PCIAM and FFT computations
   * 
   * @param workQueue the work queue
   * @param bkQueue the bookkeeper queue
   * @param ccfQueue the CCF queue
   * @param memory the tile worker memory
   * @param initTile the initial tile
   * @param devID the device ID associated with this thread
   * @param threadID the thread ID associated with this thread
   * @param context the context associated with this thread
   * @param peerContexts the array of all contexts used
   * @param peerDevIds the array of all device IDs used
   */
  public TileGPUPciamWorker(PriorityBlockingQueue<StitchingTask<T>> workQueue,
      PriorityBlockingQueue<StitchingTask<T>> bkQueue, PriorityBlockingQueue<StitchingTask<T>> ccfQueue,
      TileWorkerMemory memory,
      ImageTile<T> initTile, int devID, int threadID, CUcontext context, CUcontext[] peerContexts,
      int[] peerDevIds) {
    bkDone = false;
    this.memory = memory;
    this.workQueue = workQueue;
    this.bkQueue = bkQueue;
    this.ccfQueue = ccfQueue;
    this.context = context;
    this.device = new CUdevice();
    JCudaDriver.cuDeviceGet(this.device, devID);
    this.devID = devID;
    this.threadID = threadID;
    this.peerContexts = peerContexts;
    this.peerDevIds = peerDevIds;
    this.nonPeerDevIds = new HashSet<Integer>();
    this.devMem = new CUdeviceptr();
    this.peerContextMap = new HashMap<Integer, CUcontext>();
    this.isCancelled = false;
    this.initTile = initTile;
  }

  @Override
  public void run() {
    JCudaDriver.cuCtxSetCurrent(this.context);

    // Allocate extra device memory for non peer-to-peer copy
    int res = JCudaDriver.cuMemAlloc(this.devMem, CudaImageTile.fftSize * Sizeof.DOUBLE * 2);      
    checkCudaOutOfMemoryError(res);
    
    // Allocate phase correlation matrix memory
    CUdeviceptr pcm = new CUdeviceptr();
    res = JCudaDriver.cuMemAlloc(pcm, this.initTile.getWidth() * this.initTile.getHeight() * Sizeof.DOUBLE);    
    checkCudaOutOfMemoryError(res);
    
    this.stream = new CUstream();
    JCudaDriver.cuStreamCreate(this.stream, CUstream_flags.CU_STREAM_DEFAULT);
    CudaImageTile.bindBwdPlanToStream(this.stream, this.threadID);        
    
    CUdevice dev = new CUdevice();
    JCudaDriver.cuDeviceGet(dev, this.devID);

    // Create lookup table for which peer GPUs have peer to peer copy
    if (this.peerContexts != null) {
      for (int i = 0; i < this.peerContexts.length; i++) {
        CUcontext ctx = this.peerContexts[i];
        if (ctx != this.context) {
          CUdevice peerDev = new CUdevice();
          JCudaDriver.cuDeviceGet(peerDev, this.peerDevIds[i]);
          int[] canAccessPeer = new int[] {0};
          JCudaDriver.cuDeviceCanAccessPeer(canAccessPeer, dev, peerDev);

          if (canAccessPeer[0] == 0) {
            this.nonPeerDevIds.add(this.peerDevIds[i]);
            this.peerContextMap.put(this.peerDevIds[i], ctx);

          } else {
            JCudaDriver.cuCtxEnablePeerAccess(ctx, 0);
          }
        }

      }
    }

    
    try {
      while (!this.isCancelled && (!bkDone || this.workQueue.size() > 0)) {
        StitchingTask<T> task = this.workQueue.take();

        Debug.msg(DebugType.VERBOSE,
            "WP Task acquired: " + task.getTask() + "  size: " + this.workQueue.size());
        if (task.getTask() == TaskType.PCIAM_NORTH || task.getTask() == TaskType.PCIAM_WEST) {
          ImageTile<T> tile = task.getTile();
          ImageTile<T> neighbor = task.getNeighbor();


          int neighborDev = neighbor.getDev();
          int tileDev = tile.getDev();

          // Check if neighbor memory is on another GPU and if
          // peer to peer is not available
          if (neighborDev != tileDev && this.nonPeerDevIds.contains(neighborDev)) {
            CudaImageTile cudaTile = (CudaImageTile) neighbor;
            CUdeviceptr fft = cudaTile.getFft();

            // copy device to device
            JCudaDriver.cuMemcpyPeerAsync(this.devMem, this.context, fft,
                this.peerContextMap.get(cudaTile.getDev()), CudaImageTile.fftSize * Sizeof.DOUBLE * 2,
                this.stream);

            CudaStitching.peakCorrelationMatrix(this.devMem, (CudaImageTile) tile, pcm, this.memory, this.stream,
                this.threadID);
          } else {
            CudaStitching.peakCorrelationMatrix((CudaImageTile) neighbor, (CudaImageTile) tile,
                pcm, this.memory, this.stream, this.threadID);
          }

          
          int[] indices;
          indices =
              CudaStitching.multiPeakCorrelationMatrixIndices(pcm, Stitching.NUM_PEAKS,
                  tile.getWidth(), tile.getHeight(), this.memory, this.stream, this.threadID);
                   
          task.setTask(TaskType.BK_CHECK_MEM);
          this.bkQueue.put(task);

          StitchingTask<T> ccfTask =
              new StitchingTask<T>(tile, neighbor, indices, this.devID, this.threadID,
                  TaskType.CCF);
          this.ccfQueue.put(ccfTask);

        } else if (task.getTask() == TaskType.BK_DONE) {
          synchronized (this) {
            bkDone = true;
          }
        } else if (task.getTask() == TaskType.CANCELLED) {
          this.isCancelled = true;
        }

      }

      Debug.msg(DebugType.HELPFUL, "PCIAM Done");

      // Signal other workers that things are done
      this.workQueue.put(new StitchingTask<T>(null, null, TaskType.SENTINEL));
     
    } catch (InterruptedException e) {
      Log.msg(LogType.MANDATORY, "Interrupted PCIAM worker");
    }


    if (this.stream != null) {
       JCudaDriver.cuStreamDestroy(this.stream);
    }

    if (this.devMem != null)
      JCudaDriver.cuMemFree(this.devMem);
    
    if (pcm != null)
      JCudaDriver.cuMemFree(pcm);

  }

  /**
   * Cancels the task
   */
  public void cancel() {
    this.workQueue.put(new StitchingTask<T>(null, null, TaskType.CANCELLED));
  }
  
  private void checkCudaOutOfMemoryError(int res)
  {
    if (res == CUresult.CUDA_ERROR_OUT_OF_MEMORY) {
      Log.msg(LogType.MANDATORY, "Error: Insufficient graphics memory to complete stitching.");
      if (!GraphicsEnvironment.isHeadless()) {
        JOptionPane.showMessageDialog(null,
            "Error: Insufficient graphics memory to complete stitching.");
      }

      if (this.executor == null)
        System.exit(1);
      else
        this.executor.cancelExecution();
      
    }

    
  }

}
/*
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

package org.apache.uima.aae.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.uima.UIMAFramework;
import org.apache.uima.aae.UIMAEE_Constants;
import org.apache.uima.aae.delegate.Delegate;
import org.apache.uima.aae.monitor.statistics.AEMetrics;
import org.apache.uima.aae.monitor.statistics.AnalysisEnginePerformanceMetrics;
import org.apache.uima.flow.FinalStep;
import org.apache.uima.util.Level;

public class LocalCache extends ConcurrentHashMap<String, LocalCache.CasStateEntry> {
  private static final long serialVersionUID = 1L;

  private static final Class CLASS_NAME = LocalCache.class;

  private AnalysisEngineController controller;

  public LocalCache(AnalysisEngineController aController) {
    controller = aController;
  }

  public CasStateEntry createCasStateEntry(String aCasReferenceId) {
    CasStateEntry entry = new CasStateEntry(aCasReferenceId);
    super.put(aCasReferenceId, entry);
    Collections.synchronizedCollection(entry.getAEPerformanceList());
    return entry;
  }

  public CasStateEntry lookupEntry(String aCasReferenceId) {
    if (super.containsKey(aCasReferenceId)) {
      return super.get(aCasReferenceId);
    }
    return null;
  }

  public String lookupInputCasReferenceId(String aCasReferenceId) {
    String parentCasReferenceId = null;
    if (this.containsKey(aCasReferenceId)) {
      CasStateEntry entry = (CasStateEntry) get(aCasReferenceId);
      if (entry != null && entry.isSubordinate()) {
        // recursively call each parent until we get to the top of the
        // Cas hierarchy
        parentCasReferenceId = lookupInputCasReferenceId(entry.getParentCasReferenceId());
      } else {
        return aCasReferenceId;
      }
    }
    return parentCasReferenceId;
  }

  public String lookupInputCasReferenceId(CasStateEntry entry) {
    String parentCasReferenceId = null;
    if (entry.isSubordinate()) {
      // recursively call each parent until we get to the top of the
      // Cas hierarchy
      parentCasReferenceId = lookupInputCasReferenceId((CasStateEntry) get(entry
              .getParentCasReferenceId()));
    } else {
      return entry.getCasReferenceId();
    }
    return parentCasReferenceId;
  }
  public void dumpContents() {
    dumpContents(true);
  }
  public synchronized void dumpContents(boolean dump2Stdout) {
    int count = 0;
    if (UIMAFramework.getLogger().isLoggable(Level.FINEST)) {
      StringBuffer sb = new StringBuffer("\n");

      for (Iterator it = entrySet().iterator(); it.hasNext();) {
        Map.Entry entry = (Map.Entry) it.next();
        CasStateEntry casStateEntry = (CasStateEntry) entry.getValue();
        if (casStateEntry == null) {
          continue;
        }
        count++;
        if (casStateEntry.isSubordinate()) {
          sb.append(entry.getKey() + " Number Of Child CASes In Play:"
                  + casStateEntry.getSubordinateCasInPlayCount() + " Parent CAS id:"
                  + casStateEntry.getParentCasReferenceId());
        } else {
          sb.append(entry.getKey() + " *** Input CAS. Number Of Child CASes In Play:"
                  + casStateEntry.getSubordinateCasInPlayCount());
        }
        if (casStateEntry.isWaitingForRelease()) {
          sb.append(" <<< Reached Final State in Controller:" + controller.getComponentName());
        }
        sb.append("\n");
      }
      UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINEST, CLASS_NAME.getName(), "dumpContents",
              UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE, "UIMAEE_show_cache_entry_key__FINEST",
              new Object[] { controller.getComponentName(), count, sb.toString() });
      if ( dump2Stdout ) {
        System.out.println(sb.toString());
      }
      sb.setLength(0);
    } else if (UIMAFramework.getLogger().isLoggable(Level.FINE)) {
      int inFinalState = 0;

      for (Iterator it = entrySet().iterator(); it.hasNext();) {
        Map.Entry entry = (Map.Entry) it.next();
        CasStateEntry casStateEntry = (CasStateEntry) entry.getValue();

        count++;
        if (casStateEntry != null && casStateEntry.isWaitingForRelease()) {
          inFinalState++;
        }
      }
      UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINE, CLASS_NAME.getName(), "dumpContents",
              UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE, "UIMAEE_show_abbrev_cache_stats___FINE",
              new Object[] { controller.getComponentName(), count, inFinalState });

    }

  }

  public synchronized void remove(String aCasReferenceId) {
    if (aCasReferenceId != null && containsKey(aCasReferenceId)) {

      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINE)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINE, getClass().getName(), "remove",
                UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_remove_cache_entry_for_cas__FINE", new Object[] { aCasReferenceId });
      }
      super.remove(aCasReferenceId);
      this.notifyAll();
    } else if (aCasReferenceId == null) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINE)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINE, getClass().getName(), "remove",
                UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_cas_is_null_remove_from_cache_failed__FINE");
      }
    } else {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINE)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINE, getClass().getName(), "remove",
                UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_cas_is_invalid_remove_from_cache_failed__FINE",
                new Object[] { aCasReferenceId });
      }
    }
  }

  public CasStateEntry getTopCasAncestor(String casReferenceId) throws Exception {
    if (!containsKey(casReferenceId)) {
      return null;
    }
    CasStateEntry casStateEntry = lookupEntry(casReferenceId);
    if (casStateEntry.isSubordinate()) {
      // Recurse until the top CAS reference Id is found
      return getTopCasAncestor(casStateEntry.getParentCasReferenceId());
    }
    // Return the top ancestor CAS id
    return casStateEntry;
  }

  public static class CasStateEntry {
	private String casReferenceId;
	// id of a parent CAS 
    private String parentCasReferenceId;
    // stores the id of an input CAS sent by the client. This is used
    // to identify client endpoint if the service is a CM.
    private String inputCasReferenceId;
    
    private volatile boolean waitingForChildren; // true if in FinalState and still has children in play
    
    private volatile boolean waitingForRealease;

    private volatile boolean pendingReply;

    private volatile boolean subordinateCAS;

    private volatile boolean replyReceived;

    private volatile boolean failed;

    private volatile boolean dropped;

    private List<Throwable> exceptionList = new ArrayList<Throwable>();

    private FinalStep step;

    private int state;

    private int subordinateCasInPlayCount;

    private Object childCountMux = new Object();

    private long seqNo;
    
    private int numberOfParallelDelegates = 1;

    private Delegate lastDelegate = null;

    private int howManyDelegatesResponded = 0;

    private Endpoint freeCasNotificationEndpoint;

    // client endpoint where the input CAS will be returned
    private Endpoint clientEndpoint;

    private volatile boolean deliveryToClientFailed;
    
    private String hostIpProcessingCAS;
    
    //  Binary semaphore used to make sure that child CASes obtain their Flow objects
    //  before the parent CAS is handled by the Flow Controller. The single permit
    //  is acquired by a child CAS when childCasOutstandingFlowCounter=0.    
    private Semaphore flowSemaphore = new Semaphore(1);
    
    //  Counts how many child CASes need their Flow objects. Incremented immediately when a child
    //  CAS arrives at the aggregate and decremented right after the flow is computed from the
    //  parent CAS flow.
    private AtomicInteger childCasOutstandingFlowCounter = new AtomicInteger();

    private List<AnalysisEnginePerformanceMetrics> performanceList = 
      new ArrayList<AnalysisEnginePerformanceMetrics>();
    
    private Object monitor = new Object();
    
    //	 used only for parallel step. Counts down when a CAS is dispatched to a each delegate.
    // Prevents a race when reply from 1st parallel delegate arrives before a dispatch to a remaining
    // delegates has not yet completed. In such scenario, the CAS may be corrupted by merging
    // reply from the 1st delegate.
    private CountDownLatch latch=null;
    
    protected Map<String, AEMetrics > casMetrics = new TreeMap<String, AEMetrics>();
    
    public Endpoint getClientEndpoint() {
    	return clientEndpoint;
    }
    public void setClientEndpoint(Endpoint ce) {
    	clientEndpoint = ce;
    }
    public boolean waitingForChildrenToFinish() {
    	return waitingForChildren;
    }
    public void waitingForChildrenToFinish(boolean waiting) {
    	waitingForChildren = waiting;
    }
    public String getHostIpProcessingCAS() {
      return hostIpProcessingCAS;
    }

    public void addMetrics(List<AnalysisEnginePerformanceMetrics> metrics) {
    	for( AnalysisEnginePerformanceMetrics m : metrics) {
    		AEMetrics aeMetrics;
    		if ( casMetrics.containsKey(m.getUniqueName()) ) {
    			aeMetrics = casMetrics.get(m.getUniqueName());
    		} else {
    			aeMetrics = new AEMetrics();
    			aeMetrics.setName(m.getName());
    			casMetrics.put(m.getUniqueName(), aeMetrics);
    		}
			aeMetrics.incrementAnalysisTime(m.getAnalysisTime());
			aeMetrics.incrementNumProcessed(m.getNumProcessed());
    	}
    }
    public List<AnalysisEnginePerformanceMetrics> getCASMetrics() {
    	List<AnalysisEnginePerformanceMetrics> list = new ArrayList<AnalysisEnginePerformanceMetrics>();
    	for( Entry<String, AEMetrics> m : casMetrics.entrySet()) {
    		AnalysisEnginePerformanceMetrics metrics = 
    				new AnalysisEnginePerformanceMetrics(
    						m.getValue().getName(),
    						m.getKey(),
    						m.getValue().getAnalysisTime().get(),
    						m.getValue().getNumProcessed().get() );
    		list.add(metrics);
    	}
    	return list;
    }
    public void setHostIpProcessingCAS(String hostIpProcessingCAS) {
      this.hostIpProcessingCAS = hostIpProcessingCAS;
    }

    public boolean deliveryToClientFailed() {
		return deliveryToClientFailed;
	}

	public void setDeliveryToClientFailed() {
		this.deliveryToClientFailed = true;
	}
	public void setSequenceNumber(long seq) {
		this.seqNo = seq;
	}
	public long getSequenceNumber() {
		return this.seqNo;
	}
	public boolean isDropped() {
      return dropped;
    }

    public void setDropped(boolean dropped) {
      this.dropped = dropped;
    }

    public Endpoint getFreeCasNotificationEndpoint() {
      return freeCasNotificationEndpoint;
    }

    public void setFreeCasNotificationEndpoint(Endpoint freeCasNotificationEndpoint) {
      this.freeCasNotificationEndpoint = freeCasNotificationEndpoint;
    }

    public CasStateEntry(String aCasReferenceId) {
      casReferenceId = aCasReferenceId;
    }

    public void setLastDelegate(Delegate aDelegate) {
      lastDelegate = aDelegate;
    }

    public Delegate getLastDelegate() {
      return lastDelegate;
    }

    public String getCasReferenceId() {
      return casReferenceId;
    }

    public String getParentCasReferenceId() {
      return parentCasReferenceId;
    }

    public void setParentCasReferenceId(String parentCasReferenceId) {
      this.parentCasReferenceId = parentCasReferenceId;
      subordinateCAS = true;
    }
    public String getInputCasReferenceId() {
    	return inputCasReferenceId;
    }
    public void setInputCasReferenceId(String inputCasId) {
    	inputCasReferenceId = inputCasId;
    }
    public void setWaitingForRelease(boolean flag) {
      waitingForRealease = flag;
    }

    public boolean isWaitingForRelease() {
      return waitingForRealease;
    }

    public void setFinalStep(FinalStep step) {
      this.step = step;
    }

    public FinalStep getFinalStep() {
      return step;
    }

    public int getState() {
      return state;
    }

    public void setState(int aState) {
      state = aState;
    }

    public boolean isSubordinate() {
      return subordinateCAS;
    }

    public int getSubordinateCasInPlayCount() {
      synchronized (childCountMux) {
        return subordinateCasInPlayCount;
      }
    }

    public void incrementSubordinateCasInPlayCount() {
      synchronized (childCountMux) {
        subordinateCasInPlayCount++;
      }
    }

    public int decrementSubordinateCasInPlayCount() {
      synchronized (childCountMux) {
        if (subordinateCasInPlayCount > 0) {
          subordinateCasInPlayCount--;
        }
        return subordinateCasInPlayCount;
      }
    }

    public boolean isPendingReply() {
      return pendingReply;
    }

    public void setPendingReply(boolean pendingReply) {
      this.pendingReply = pendingReply;
    }

    public void setReplyReceived() {
      replyReceived = true;
    }

    public boolean isReplyReceived() {
      return replyReceived;
    }
    public void resetReplyReceived() {
      replyReceived = false;
    }
    public synchronized void incrementHowManyDelegatesResponded() {
      if (howManyDelegatesResponded < numberOfParallelDelegates) {
        howManyDelegatesResponded++;
      }
    }

    public synchronized int howManyDelegatesResponded() {
      return howManyDelegatesResponded;
    }

    public synchronized void resetDelegateResponded() {
      howManyDelegatesResponded = 0;
    }

    public void setNumberOfParallelDelegates(int aNumberOfParallelDelegates) {
      numberOfParallelDelegates = aNumberOfParallelDelegates;
      latch = new CountDownLatch(aNumberOfParallelDelegates);
    }

    public int getNumberOfParallelDelegates() {
      return numberOfParallelDelegates;
    }
    
    public void dispatchedCasToParallelDelegate() {
    	if (latch != null ) {
        	if ( latch.getCount() > 0 ) {
        		latch.countDown();
        	}
    	}
    }
    public void blockIfParallelDispatchNotComplete() {
    	try {
        	if (latch != null ) {
        	   latch.await();
        	}
    	} catch( InterruptedException e) {
    	}
    }
    public void resetDispatchLatch() {
    	if (latch != null ) {
    		while ( latch.getCount() > 0 ) {
    			latch.countDown();
    		}
    	}
    }
    public boolean isFailed() {
      return failed;
    }

    public void setFailed() {
      this.failed = true;
    }

    public void addThrowable(Throwable t) {
      exceptionList.add(t);
    }

    public List<Throwable> getErrors() {
      return exceptionList;
    }
    /**
     * Grab a permit from a binary semaphore blocking if the permit is not 
     * available.
     * 
     * @throws InterruptedException - thrown if thread is interrupted
     */
    public void acquireFlowSemaphore() throws InterruptedException {
      flowSemaphore.acquire();
    }
    /**
     * Release a permit to the binary semaphore.
     */
    public void releaseFlowSemaphore() {
      flowSemaphore.release();
    }
    /**
     * Count the number of child CASes that have not yet obtained their Flow
     * object. If this is the first child with no Flow object, grab a permit
     * from a binary semaphore preventing parent CAS from calling Flow Controller's
     * next() method. 
     * 
     */
    public void incrementOutstandingFlowCounter() {
      synchronized( monitor ) {
        if ( childCasOutstandingFlowCounter.incrementAndGet() == 1  ) {
          try {
          	System.out.println("::::::::::  Waiting to acquire semaphore - CAS:"+getCasReferenceId()+" ThreadId:"+Thread.currentThread().getId());  

            acquireFlowSemaphore();
          	System.out.println(":::::::::: acquired semaphore - CAS:"+getCasReferenceId()+" ThreadId:"+Thread.currentThread().getId());  
          } catch( InterruptedException e) {
          }
        }
      }
    }
    /**
     * Decrement the number of child CASes with no Flow object. Releases
     * the permit to the binary semaphore if all child CASes in-play obtain
     * their Flow objects. This releases the parent CAS which can than continue
     * with its Flow. 
     */
    public void decrementOutstandingFlowCounter() {
      synchronized( monitor ) {
        if( flowSemaphore.availablePermits() == 0 ) {
          if ( childCasOutstandingFlowCounter.get() > 0 ) {
            childCasOutstandingFlowCounter.decrementAndGet();
          }
          releaseFlowSemaphore();
        	System.out.println(":::::::::: released semaphore - CAS:"+getCasReferenceId()+" ThreadId:"+Thread.currentThread().getId());  

        }
      }
    }
    public List<AnalysisEnginePerformanceMetrics> getAEPerformanceList() {
      return performanceList;
    }
    
  

  }
}

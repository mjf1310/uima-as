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

package org.apache.uima.aae;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.uima.UIMAFramework;
import org.apache.uima.aae.controller.AnalysisEngineController;
import org.apache.uima.aae.controller.BaseAnalysisEngineController.ServiceState;
import org.apache.uima.aae.controller.PrimitiveAnalysisEngineController;
import org.apache.uima.aae.controller.PrimitiveAnalysisEngineController_impl;
import org.apache.uima.aae.message.MessageWrapper;
import org.apache.uima.util.Level;

/**
 * Custom ThreadFactory for use in the TaskExecutor. The TaskExecutor is plugged in by Spring from
 * spring xml file generated by dd2spring. The TaskExecutor is only defined for PrimitiveControllers
 * and its main purpose is to provide thread pooling and management. Each new thread produced by
 * this ThreadFactory is used to initialize a dedicated AE instance in the PrimitiveController.
 * 
 * 
 */
public class UimaAsPriorityBasedThreadFactory implements ThreadFactory {
  private static final Class<UimaAsPriorityBasedThreadFactory> CLASS_NAME = UimaAsPriorityBasedThreadFactory.class;
  private static final String THREAD_POOL = "[UIMA AS ThreadPool ";
  
  private AnalysisEngineController controller;
//  private PrimitiveAnalysisEngineController controller;

  private ThreadGroup theThreadGroup;

  private String threadNamePrefix=null;
  
  private boolean isDaemon=false;
  
  public static AtomicInteger poolIdGenerator = new AtomicInteger();
  
  private final int poolId = poolIdGenerator.incrementAndGet();
  
  private CountDownLatch latchToCountNumberOfTerminatedThreads;
  
  private volatile boolean initFailed=false;
  
  private BlockingQueue<MessageWrapper> queue;
  
  private InputChannel ic;
  
  private final List<Thread> tList = new ArrayList<Thread>();
  
  public UimaAsPriorityBasedThreadFactory(ThreadGroup tGroup) {
    this(tGroup,null);
  }
//  public UimaAsPriorityBasedThreadFactory(ThreadGroup tGroup, PrimitiveAnalysisEngineController aController) {
  public UimaAsPriorityBasedThreadFactory(ThreadGroup tGroup, AnalysisEngineController aController) {
    this( tGroup, aController, null);
  }
//  public UimaAsPriorityBasedThreadFactory(ThreadGroup tGroup, PrimitiveAnalysisEngineController aController, CountDownLatch latchToCountNumberOfTerminatedThreads) {
  public UimaAsPriorityBasedThreadFactory(ThreadGroup tGroup, AnalysisEngineController aController, CountDownLatch latchToCountNumberOfTerminatedThreads) {
    controller = aController;
    theThreadGroup = tGroup;
    this.latchToCountNumberOfTerminatedThreads = latchToCountNumberOfTerminatedThreads;
  }
  public UimaAsPriorityBasedThreadFactory withQueue(BlockingQueue<MessageWrapper> wq ) {
	  queue = wq;
	  return this;
  }
  public UimaAsPriorityBasedThreadFactory withChannel(InputChannel channel) {
	  ic = channel;
	  return this;
  }
  public void setThreadNamePrefix(String prefix) {
    threadNamePrefix = prefix;
  }
  public void setThreadGroup( ThreadGroup tGroup) {
    theThreadGroup = tGroup;
  }
  public void setDaemon(boolean daemon) {
 //   isDaemon = daemon;
  }
  public void stop() {
  }

  /**
   * Creates a new thread, initializes instance of AE via a call on a given PrimitiveController.
   * Once the thread finishes initializing AE instance in the controller, it calls run() on a given
   * Runnable. This Runnable is a Worker instance managed by the TaskExecutor. When the thread calls
   * run() on the Runnable it blocks until the Worker releases it.
   * 
   * @param r runnable
   */
  public Thread newThread(final Runnable r) {
    Thread newThread = null;
    try {
      newThread = new Thread(theThreadGroup, new Runnable() {
        public void run() {
          if ( threadNamePrefix == null ) {
             if ( controller != null ) {
               threadNamePrefix = THREAD_POOL+poolId+"] "+controller.getComponentName() + " Process Thread";
             } else {
               threadNamePrefix = THREAD_POOL+poolId+"] ";
             }
          } 
          boolean interruptAllThreads = false;
          Thread.currentThread().setName( threadNamePrefix +" - "                 
                          + Thread.currentThread().getId());
          try {
            if (controller != null && 
            	controller instanceof PrimitiveAnalysisEngineController && 
            	!((PrimitiveAnalysisEngineController)controller).threadAssignedToAE())  {
              // call the controller to initialize next instance of AE. Once initialized this
              // AE instance process() method will only be called from this thread
			  UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, getClass().getName(),
					"UimaAsPriorityBasedThreadFactory.run()", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
					"UIMAEE_calling_ae_initialize__INFO", new Object[] {controller.getComponentName(),Thread.currentThread().getId()});
             
			  if ( !initFailed && !controller.getState().equals(ServiceState.FAILED) ) {
            	  try {
            		  System.out.println(".....UimaAsPriorityBasedThreadFactory.run() - callint AE.initialize() - Thread:"+Thread.currentThread().getId());
            		  ((PrimitiveAnalysisEngineController)controller).initializeAnalysisEngine();
            	  } catch( Exception e) {
            		  initFailed = true;
            		  e.printStackTrace();
            		  throw e;
            	  }
              } else {
            	  return; // there was failure previously so just return
              }
            }
            System.out.println("............ Worker Thread Waiting for messages");
            // runs forever until controll is stopped
            while (!controller.isStopped()) {
            	// block until a message arrives or timeout. On timeout, the pool returns null
            	MessageWrapper m = queue.poll(100,
            			TimeUnit.MILLISECONDS);
            	if (m == null) {
            		// nothing received, try again
            		continue;
            	}
            	System.out.println(">>>>>>>>>>>>>>>>>> GOT MESSAGE .....");
            	// 'poison pill' sent when controller stops
            	if (m.getMessage() == null
            			&& m.getSemaphore() == null
            			&& m.getSession() == null) {
            		interruptAllThreads = true;
            		break;
            	} else {
            		// got a message, so process it by passing it on the input channel
            		ic.onMessage(m);
            	}
            }
            	
          } catch (Throwable e) {
            if ( !(e instanceof Exception) ) {
              //   try to log. If this is OOM, logging may not succeed and we
              //   get another OOM.
              try {
                UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                        "UimaAsPriorityBasedThreadFactory", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                        "UIMAEE_exception__WARNING", e);
                System.out.println(">>>>>>>>>>>>>>>>>>Exiting UIMA AS Process Due to Java Error "+e);
              } catch( Throwable t ) {
                 // Failed during logging. We are tight on memory. Just exit 
              }
              System.exit(-1);
              
            }
            return;
          } finally {
              if ( controller instanceof PrimitiveAnalysisEngineController_impl ) {
       			     UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, getClass().getName(),
        					"UimaAsPriorityBasedThreadFactory.run", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
        					"UIMAEE_process_thread_exiting__INFO", new Object[] {controller.getComponentName(),Thread.currentThread().getId()});
            	  ((PrimitiveAnalysisEngineController_impl)controller).destroyAE();
        			  UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, getClass().getName(),
         					"UimaAsPriorityBasedThreadFactory.run()", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
         					"UIMAEE_ae_instance_destroy_called__INFO", new Object[] {controller.getComponentName(),Thread.currentThread().getId()});
        			  if ( latchToCountNumberOfTerminatedThreads != null ) {
                  synchronized( latchToCountNumberOfTerminatedThreads ) {
                    latchToCountNumberOfTerminatedThreads.countDown();
                   }
        			  }
              }
              if ( interruptAllThreads ) {
            	  for( Thread t : tList ) {
            		  t.interrupt();
            	  }
              }
          }
        
        }
      });
    } catch (Exception e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        if ( controller != null ) {
          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                  "UimaAsPriorityBasedThreadFactory", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                  "UIMAEE_service_exception_WARNING", controller.getComponentName());
        }

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "UimaAsPriorityBasedThreadFactory", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
    }
    if ( newThread != null ) {
      newThread.setDaemon(isDaemon);
      tList.add(newThread);
    }
    return newThread;
  }
}

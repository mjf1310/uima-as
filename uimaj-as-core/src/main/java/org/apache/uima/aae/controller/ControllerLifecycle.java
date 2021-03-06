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

/**
 * Interface defining methods to enable stopping of Asynchronous Service.
 */
public interface ControllerLifecycle {
  /**
   * Called to initiate shutdown of the Asynchronous Service. An implementation can close an input
   * and output channels and do any necessary cleanup before terminating.
   */
  public void terminate();

  /**
   * Called to initiate shutdown of the Asynchronous Service. An implementation can close an input
   * and output channels and do any necessary cleanup before terminating.
   */
  public void terminate(Throwable cause);
  /**
   * Register one or more listeners through which the controller can send notification of events.
   * 
   * 
   * @param aListener
   *          - application listener object to register
   */
  public void addControllerCallbackListener(ControllerCallbackListener aListener);

  /**
   * Removes named application listener.
   * 
   * @param aListener
   *          - application listener to remove
   */
  public void removeControllerCallbackListener(ControllerCallbackListener aListener);
}

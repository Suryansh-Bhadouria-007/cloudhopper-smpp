package com.cloudhopper.smpp.demo;

/*
 * #%L
 * ch-smpp
 * %%
 * Copyright (C) 2009 - 2012 Cloudhopper by Twitter
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.DecimalUtil;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author joelauer (twitter: @jjlauer or <a href="http://twitter.com/jjlauer" target=window>http://twitter.com/jjlauer</a>)
 */
public class PerformanceClientMain {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceClientMain.class);

    //
    // performance testing options (just for this sample)
    //
    // total number of sessions (conns) to create
	static public final int SESSION_COUNT = 20;
	// size of window per session
	static public final int WINDOW_SIZE = 50;
    // total number of submit to send total across all sessions
	static public final int SUBMIT_TO_SEND = Integer.MAX_VALUE;
	// total number of submit sent
	static public final AtomicInteger SUBMIT_SENT = new AtomicInteger(0);
    
    static public void main(String[] args) throws Exception {
        //
        // setup 3 things required for any session we plan on creating
        //
        
        // for monitoring thread use, it's preferable to create your own instance
        // of an executor with Executors.newCachedThreadPool() and cast it to ThreadPoolExecutor
        // this permits exposing thinks like executor.getActiveCount() via JMX possible
        // no point renaming the threads in a factory since underlying Netty 
        // framework does not easily allow you to customize your thread names
        ThreadPoolExecutor executor = (ThreadPoolExecutor)Executors.newCachedThreadPool();
        
        // to enable automatic expiration of requests, a second scheduled executor
        // is required which is what a monitor task will be executed with - this
        // is probably a thread pool that can be shared with between all client bootstraps
        ScheduledThreadPoolExecutor monitorExecutor = (ScheduledThreadPoolExecutor)Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private AtomicInteger sequence = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("SmppClientSessionWindowMonitorPool-" + sequence.getAndIncrement());
                return t;
            }
        });
        
        // a single instance of a client bootstrap can technically be shared
        // between any sessions that are created (a session can go to any different
        // number of SMSCs) - each session created under
        // a client bootstrap will use the executor and monitorExecutor set
        // in its constructor - just be *very* careful with the "expectedSessions"
        // value to make sure it matches the actual number of total concurrent
        // open sessions you plan on handling - the underlying netty library
        // used for NIO sockets essentially uses this value as the max number of
        // threads it will ever use, despite the "max pool size", etc. set on
        // the executor passed in here
        DefaultSmppClient clientBootstrap = new DefaultSmppClient(Executors.newCachedThreadPool(), SESSION_COUNT, monitorExecutor);

        // same configuration for each client runner
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setWindowSize(WINDOW_SIZE);
        config.setName("Tester.Session.0");
        config.setType(SmppBindType.TRANSCEIVER);
        config.setHost("127.0.0.1");
        config.setPort(2776);
        config.setConnectTimeout(10000);
        config.setSystemId("1234567890");
        config.setPassword("password");
        config.getLoggingOptions().setLogBytes(false);
        // to enable monitoring (request expiration)
        config.setRequestExpiryTimeout(30000);
        config.setWindowMonitorInterval(15000);
        config.setCountersEnabled(true);

        // various latches used to signal when things are ready
        CountDownLatch allSessionsBoundSignal = new CountDownLatch(SESSION_COUNT);
        CountDownLatch startSendingSignal = new CountDownLatch(1);
        
        // create all session runners and executors to run them
        ThreadPoolExecutor taskExecutor = (ThreadPoolExecutor)Executors.newCachedThreadPool();
        ClientSessionTask[] tasks = new ClientSessionTask[SESSION_COUNT];
        for (int i = 0; i < SESSION_COUNT; i++) {
            tasks[i] = new ClientSessionTask(allSessionsBoundSignal, startSendingSignal, clientBootstrap, config);
            taskExecutor.submit(tasks[i]);
        }
        
        // wait for all sessions to bind
        logger.info("Waiting up to 7 seconds for all sessions to bind...");
        if (!allSessionsBoundSignal.await(7000, TimeUnit.MILLISECONDS)) {
            throw new Exception("One or more sessions were unable to bind, cancelling test");
        }
        
        logger.info("Sending signal to start test..."); 
        long startTimeMillis = System.currentTimeMillis();
        startSendingSignal.countDown();
        
        // wait for all tasks to finish
        taskExecutor.shutdown();
        taskExecutor.awaitTermination(3, TimeUnit.DAYS);
        long stopTimeMillis = System.currentTimeMillis();
        
        // did everything succeed?
        int actualSubmitSent = 0;
        int sessionFailures = 0;
        for (int i = 0; i < SESSION_COUNT; i++) {
            if (tasks[i].getCause() != null) {
                sessionFailures++;
                logger.error("Task #" + i + " failed with exception: " + tasks[i].getCause());
            } else {
                actualSubmitSent += tasks[i].getSubmitRequestSent();
            }
        }
        
        logger.info("Performance client finished:");
        logger.info("       Sessions: " + SESSION_COUNT);
        logger.info("    Window Size: " + WINDOW_SIZE);
        logger.info("Sessions Failed: " + sessionFailures);
        logger.info("           Time: " + (stopTimeMillis - startTimeMillis) + " ms");
        logger.info("  Target Submit: " + SUBMIT_TO_SEND);
        logger.info("  Actual Submit: " + actualSubmitSent);
        double throughput = (double)actualSubmitSent/((double)(stopTimeMillis - startTimeMillis)/(double)1000);
        logger.info("     Throughput: " + DecimalUtil.toString(throughput, 3) + " per sec");
        
        for (int i = 0; i < SESSION_COUNT; i++) {
            if (tasks[i].session != null && tasks[i].session.hasCounters()) {
                logger.info(" Session " + i + ": submitSM {}", tasks[i].session.getCounters().getTxSubmitSM());
            }
        }

        // this is required to not causing server to hang from non-daemon threads
        // this also makes sure all open Channels are closed to I *think*
        logger.info("Shutting down client bootstrap and executors...");
        clientBootstrap.destroy();
        executor.shutdownNow();
        monitorExecutor.shutdownNow();
        
        logger.info("Done. Exiting");
    }
    
    
    public static class ClientSessionTask implements Runnable {

        private SmppSession session;
        private CountDownLatch allSessionsBoundSignal;
        private CountDownLatch startSendingSignal;
        private DefaultSmppClient clientBootstrap;
        private SmppSessionConfiguration config;
        private int submitRequestSent;
        private int submitResponseReceived;
        private AtomicBoolean sendingDone;
        private Exception cause;
        
        public ClientSessionTask(CountDownLatch allSessionsBoundSignal, CountDownLatch startSendingSignal, DefaultSmppClient clientBootstrap, SmppSessionConfiguration config) {
            this.allSessionsBoundSignal = allSessionsBoundSignal;
            this.startSendingSignal = startSendingSignal;
            this.clientBootstrap = clientBootstrap;
            this.config = config;
            this.submitRequestSent = 0;
            this.submitResponseReceived = 0;
            this.sendingDone = new AtomicBoolean(false);
        }
        
        public Exception getCause() {
            return this.cause;
        }
        
        public int getSubmitRequestSent() {
            return this.submitRequestSent;
        }
        
        @Override
        public void run() {
            // a countdownlatch will be used to eventually wait for all responses
            // to be received by this thread since we don't want to exit too early
            CountDownLatch allSubmitResponseReceivedSignal = new CountDownLatch(1);
            
            DefaultSmppSessionHandler sessionHandler = new ClientSmppSessionHandler(allSubmitResponseReceivedSignal);
            String text160 = "\u20AC Lorem [ipsum] dolor sit amet, consectetur adipiscing elit. Proin feugiat, leo id commodo tincidunt, nibh diam ornare est, vitae accumsan risus lacus sed sem metus.";
            byte[] textBytes = CharsetUtil.encode(text160, CharsetUtil.CHARSET_GSM);
            
            try {
                // create session a session by having the bootstrap connect a
                // socket, send the bind request, and wait for a bind response
                session = clientBootstrap.bind(config, sessionHandler);
                
                // don't start sending until signalled
                allSessionsBoundSignal.countDown();
                startSendingSignal.await();
                
                // all threads compete for processing
				int andIncrement = SUBMIT_SENT.getAndIncrement();
				while (andIncrement < SUBMIT_TO_SEND) {
					if (andIncrement % 100000 == 0) {
						logger.info("sent: " + andIncrement);
					}
					SubmitSm submit = new SubmitSm();
					submit.setSourceAddress(new Address((byte) 0x03, (byte) 0x00, "40404"));
					submit.setDestAddress(new Address((byte) 0x01, (byte) 0x01, "44555519205"));
					submit.setShortMessage(textBytes);
					// asynchronous send
					this.submitRequestSent++;
					sendingDone.set(true);
					try {
						final PduResponse pduResponse = sendRequestAndGetResponse(session, submit, 30000);
					} catch (Exception e) {
//						logger.error(andIncrement+ "  " + submit.toString(), e);
					}
					andIncrement = SUBMIT_SENT.getAndIncrement();
				}

				// all threads have sent all submit, we do need to wait for
				// an acknowledgement for all "inflight" though (synchronize
				// against the window)
				logger.debug("before waiting sendWindow.size: {}", session.getSendWindow().getSize());

                allSubmitResponseReceivedSignal.await();

				logger.debug("after waiting sendWindow.size: {}", session.getSendWindow().getSize());

				session.unbind(5000);
			} catch (Exception e) {
                logger.error("", e);
                this.cause = e;
            }
        }
        
        class ClientSmppSessionHandler extends DefaultSmppSessionHandler {

            private CountDownLatch allSubmitResponseReceivedSignal;
            
            public ClientSmppSessionHandler(CountDownLatch allSubmitResponseReceivedSignal) {
                super(logger);
                this.allSubmitResponseReceivedSignal = allSubmitResponseReceivedSignal;
            }
            
            @Override
            public void fireChannelUnexpectedlyClosed() {
                // this is an error we didn't really expect for perf testing
                // its best to at least countDown the latch so we're not waiting forever
                logger.error("Unexpected close occurred...");
                this.allSubmitResponseReceivedSignal.countDown();
            }

            @Override
            public void fireExpectedPduResponseReceived(PduAsyncResponse pduAsyncResponse) {
                submitResponseReceived++;
                // if the sending thread is finished, check if we're done
                if (sendingDone.get()) {
                    if (submitResponseReceived >= submitRequestSent) {
                        this.allSubmitResponseReceivedSignal.countDown();
                    }
                }
            }
        }
    }

	/**
	 * Sends a PDU request and gets a PDU response that matches its sequence #.
	 * NOTE: This PDU response may not be the actual response the caller was
	 * expecting, it needs to verify it afterwards.
	 */
	protected static PduResponse sendRequestAndGetResponse(SmppSession session, PduRequest requestPdu, long timeoutInMillis) throws RecoverablePduException, UnrecoverablePduException, SmppTimeoutException, SmppChannelException, InterruptedException {
		WindowFuture<Integer, PduRequest, PduResponse> future = session.sendRequestPdu(requestPdu, timeoutInMillis, true);
		boolean completedWithinTimeout = future.await();

		if (!completedWithinTimeout) {
			// since this is a "synchronous" request and it timed out, we don't
			// want it eating up valuable window space - cancel it before returning exception
			future.cancel();
			throw new SmppTimeoutException("Unable to get response within [" + timeoutInMillis + " ms]");
		}

		// 3 possible scenarios once completed: success, failure, or cancellation
		final boolean success = future.isSuccess();
		final boolean cancelled = future.isCancelled();
		final boolean done = future.isDone();
		final PduResponse response = future.getResponse();
		final long doneTimestamp = future.getDoneTimestamp();
		if (success) {
			return future.getResponse();
		} else if (future.getCause() != null) {
			Throwable cause = future.getCause();
			if (cause instanceof ClosedChannelException) {
				throw new SmppChannelException("Channel was closed after sending request, but before receiving response", cause);
			} else {
				throw new UnrecoverablePduException(cause.getMessage(), cause);
			}
		} else {
			if (cancelled) {
				logger.error("{} {} {} {} {}", done, success, cancelled, doneTimestamp, response);
				logger.error("{} {} {} {} {}", future.isDone(), future.isSuccess(), future.isCancelled(), future.getDoneTimestamp(), future.getResponse());
				throw new RecoverablePduException("Request was cancelled");
			} else {
				logger.error("{} {} {} {} {}", done, success, cancelled, doneTimestamp, response);
				logger.error("{} {} {} {} {}", future.isDone(), future.isSuccess(), future.isCancelled(), future.getDoneTimestamp(), future.getResponse());
				throw new UnrecoverablePduException("Unable to sendRequestAndGetResponse successfully (future was in strange state)");
			}
		}
	}
}
	
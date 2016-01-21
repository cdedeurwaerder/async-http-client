/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.channel;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.EventCollectingHandler.COMPLETED_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.CONNECTION_OFFER_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.CONNECTION_POOLED_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.CONNECTION_POOL_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.HEADERS_RECEIVED_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.HEADERS_WRITTEN_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.REQUEST_SEND_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.STATUS_RECEIVED_EVENT;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.exception.TooManyConnectionsException;
import org.asynchttpclient.test.EventCollectingHandler;
import org.testng.annotations.Test;

public class ConnectionPoolTest extends AbstractBasicTest {

    @Test(groups = "standalone")
    public void testMaxTotalConnections() throws Exception {
        AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setMaxConnections(1));
        try {
            String url = getTargetUrl();
            int i;
            Exception exception = null;
            for (i = 0; i < 3; i++) {
                try {
                    logger.info("{} requesting url [{}]...", i, url);
                    Response response = client.prepareGet(url).execute().get();
                    logger.info("{} response [{}].", i, response);
                } catch (Exception ex) {
                    exception = ex;
                }
            }
            assertNull(exception);
        } finally {
            client.close();
        }
    }

    @Test(groups = "standalone", expectedExceptions = TooManyConnectionsException.class)
    public void testMaxTotalConnectionsException() throws Throwable {
        AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setMaxConnections(1));
        try {
            String url = getTargetUrl();

            List<ListenableFuture<Response>> futures = new ArrayList<ListenableFuture<Response>>();
            for (int i = 0; i < 5; i++) {
                logger.info("{} requesting url [{}]...", i, url);
                futures.add(client.prepareGet(url).execute());
            }

            Exception exception = null;
            for (ListenableFuture<Response> future : futures) {
                try {
                    future.get();
                } catch (Exception ex) {
                    exception = ex;
                    break;
                }
            }

            assertNotNull(exception);
            throw exception.getCause();
        } finally {
            client.close();
        }
    }

    @Test(groups = "standalone", invocationCount = 10, alwaysRun = true)
    public void asyncDoGetKeepAliveHandlerTest_channelClosedDoesNotFail() throws Exception {
        AsyncHttpClient client = asyncHttpClient();
        try {
            // Use a l in case the assert fail
            final CountDownLatch l = new CountDownLatch(2);

            final Map<String, Boolean> remoteAddresses = new ConcurrentHashMap<String, Boolean>();

            AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    System.out.println("ON COMPLETED INVOKED " + response.getHeader("X-KEEP-ALIVE"));
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        remoteAddresses.put(response.getHeader("X-KEEP-ALIVE"), true);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            };

            client.prepareGet(getTargetUrl()).execute(handler).get();
            server.stop();
            server.start();
            client.prepareGet(getTargetUrl()).execute(handler);

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }

            assertEquals(remoteAddresses.size(), 2);
        } finally {
            client.close();
        }
    }

    @Test(groups = "standalone", expectedExceptions = TooManyConnectionsException.class)
    public void multipleMaxConnectionOpenTest() throws Throwable {
        AsyncHttpClient c = asyncHttpClient(config().setKeepAlive(true).setConnectTimeout(5000).setMaxConnections(1));
        try {
            String body = "hello there";

            // once
            Response response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), body);

            // twice
            Exception exception = null;
            try {
                c.preparePost(String.format("http://localhost:%d/foo/test", port2)).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
                fail("Should throw exception. Too many connections issued.");
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNotNull(exception);
            throw exception.getCause();
        } finally {
            c.close();
        }
    }

    @Test(groups = "standalone")
    public void multipleMaxConnectionOpenTestWithQuery() throws Exception {
        AsyncHttpClient c = asyncHttpClient(config().setKeepAlive(true).setConnectTimeout(5000).setMaxConnections(1));
        try {
            String body = "hello there";

            // once
            Response response = c.preparePost(getTargetUrl() + "?foo=bar").setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), "foo_" + body);

            // twice
            Exception exception = null;
            try {
                response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNull(exception);
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            c.close();
        }
    }

    /**
     * This test just make sure the hack used to catch disconnected channel
     * under win7 doesn't throw any exception. The onComplete method must be
     * only called once.
     * 
     * @throws Exception if something wrong happens.
     */
    @Test(groups = "standalone")
    public void win7DisconnectTest() throws Exception {
        final AtomicInteger count = new AtomicInteger(0);
        AsyncHttpClient client = asyncHttpClient();
        try {
            AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {

                    count.incrementAndGet();
                    StackTraceElement e = new StackTraceElement("sun.nio.ch.SocketDispatcher", "read0", null, -1);
                    IOException t = new IOException();
                    t.setStackTrace(new StackTraceElement[] { e });
                    throw t;
                }
            };

            try {
                client.prepareGet(getTargetUrl()).execute(handler).get();
                fail("Must have received an exception");
            } catch (ExecutionException ex) {
                assertNotNull(ex);
                assertNotNull(ex.getCause());
                assertEquals(ex.getCause().getClass(), IOException.class);
                assertEquals(count.get(), 1);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = "standalone")
    public void asyncHandlerOnThrowableTest() throws Exception {
        AsyncHttpClient client = asyncHttpClient();
        try {
            final AtomicInteger count = new AtomicInteger();
            final String THIS_IS_NOT_FOR_YOU = "This is not for you";
            final CountDownLatch latch = new CountDownLatch(16);
            for (int i = 0; i < 16; i++) {
                client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerBase() {
                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        throw new Exception(THIS_IS_NOT_FOR_YOU);
                    }
                });

                client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerBase() {
                    @Override
                    public void onThrowable(Throwable t) {
                        if (t.getMessage() != null && t.getMessage().equalsIgnoreCase(THIS_IS_NOT_FOR_YOU)) {
                            count.incrementAndGet();
                        }
                    }

                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        latch.countDown();
                        return response;
                    }
                });
            }
            latch.await(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(count.get(), 0);
        } finally {
            client.close();
        }
    }

    @Test(groups = "standalone")
    public void nonPoolableConnectionReleaseSemaphoresTest() throws Throwable {

        RequestBuilder request = get(getTargetUrl()).setHeader("Connection", "close");
        AsyncHttpClient client = asyncHttpClient(config().setMaxConnections(6).setMaxConnectionsPerHost(3));
        try {
            client.executeRequest(request).get();
            Thread.sleep(1000);
            client.executeRequest(request).get();
            Thread.sleep(1000);
            client.executeRequest(request).get();
            Thread.sleep(1000);
            client.executeRequest(request).get();
        } finally {
            client.close();
        }
    }

    @Test(groups = "standalone")
    public void testPooledEventsFired() throws Exception {
        RequestBuilder request = get("http://localhost:" + port1 + "/Test");

        AsyncHttpClient client = asyncHttpClient();
        try {
            EventCollectingHandler firstHandler = new EventCollectingHandler();
            client.executeRequest(request, firstHandler).get(3, TimeUnit.SECONDS);
            firstHandler.waitForCompletion(3, TimeUnit.SECONDS);

            EventCollectingHandler secondHandler = new EventCollectingHandler();
            client.executeRequest(request, secondHandler).get(3, TimeUnit.SECONDS);
            secondHandler.waitForCompletion(3, TimeUnit.SECONDS);

            Object[] expectedEvents = new Object[] { CONNECTION_POOL_EVENT, CONNECTION_POOLED_EVENT, REQUEST_SEND_EVENT, HEADERS_WRITTEN_EVENT, STATUS_RECEIVED_EVENT,
                    HEADERS_RECEIVED_EVENT, CONNECTION_OFFER_EVENT, COMPLETED_EVENT };

            assertEquals(secondHandler.firedEvents.toArray(), expectedEvents, "Got " + Arrays.toString(secondHandler.firedEvents.toArray()));
        } finally {
            client.close();
        }
    }
}

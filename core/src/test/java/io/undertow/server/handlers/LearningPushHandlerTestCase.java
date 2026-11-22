/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.handlers;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.cache.LRUCache;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

/**
 * Checks that the per-referer push map of {@link LearningPushHandler} stays bounded.
 */
@RunWith(DefaultServer.class)
public class LearningPushHandlerTestCase {

    private static final int MAX_PUSH_ENTRIES = 10;
    private static final int PUSHES = 50;
    private static final String REFERER = "http://localhost/index.html";

    private static LearningPushHandler handler;

    @BeforeClass
    public static void setup() {
        Map<String, Object> config = new HashMap<>();
        config.put("max-entries", 1000);
        config.put("max-push-entries", MAX_PUSH_ENTRIES);
        handler = (LearningPushHandler) new LearningPushHandler.Builder().build(config).wrap(new HttpHandler() {
            @Override
            public void handleRequest(final HttpServerExchange exchange) {
                exchange.getResponseSender().send("ok");
            }
        });
        DefaultServer.setRootHandler(handler);
    }

    @Test
    public void testPushEntriesPerRefererAreBounded() throws Exception {
        TestHttpClient client = new TestHttpClient();
        try {
            for (int i = 0; i < PUSHES; ++i) {
                HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/resource" + i + ".css");
                get.addHeader(Headers.REFERER_STRING, REFERER);
                get.addHeader(Headers.ACCEPT_STRING, "text/css");
                HttpResponse result = client.execute(get);
                Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
                HttpClientUtils.readResponse(result);
            }
        } finally {
            client.getConnectionManager().shutdown();
        }
        int size = pushEntriesFor(REFERER);
        Assert.assertEquals("push entries for referer", MAX_PUSH_ENTRIES, size);
    }

    // Reflection keeps this compiling whether the inner map is a Map or an LRUCache.
    private static int pushEntriesFor(String referer) throws ReflectiveOperationException {
        Field cacheField = LearningPushHandler.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Object inner = ((LRUCache<String, ?>) cacheField.get(handler)).get(referer);
        if (inner == null) {
            return 0;
        }
        if (inner instanceof Map) {
            return ((Map<?, ?>) inner).size();
        }
        Field innerMap = LRUCache.class.getDeclaredField("cache");
        innerMap.setAccessible(true);
        return ((Map<?, ?>) innerMap.get(inner)).size();
    }
}

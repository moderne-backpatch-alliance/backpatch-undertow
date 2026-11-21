/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2023 Red Hat, Inc., and individual contributors
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

package io.undertow.protocols.http2;

import io.undertow.UndertowOptions;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.testutils.category.UnitTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.xnio.ChannelPipe;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.StreamConnection;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.undertow.testutils.StopServerWithExternalWorkerUtils.stopWorker;

/**
 * Checks that RST_STREAM frames sent by this side count against
 * {@link UndertowOptions#MAX_RST_FRAMES_PER_WINDOW}, see CVE-2025-9784.
 */
@Category(UnitTest.class)
public class SentRstFramesLimitUnitTestCase {

    private static final int MAX_RST_FRAMES = 5;
    private static XnioWorker worker;

    @BeforeClass
    public static void beforeClass() throws Exception {
        worker = Xnio.getInstance().createWorker(OptionMap.EMPTY);
    }

    @AfterClass
    public static void afterClass() {
        if (worker != null) {
            stopWorker(worker);
        }
    }

    @Test
    public void testGoAwayWhenSentRstFramesExceedLimit() throws Exception {
        assertSentRstFrames(MAX_RST_FRAMES + 1, true);
    }

    @Test
    public void testNoGoAwayWhenSentRstFramesWithinLimit() throws Exception {
        assertSentRstFrames(MAX_RST_FRAMES, false);
    }

    private void assertSentRstFrames(int rstFrames, boolean goAwayExpected) throws Exception {
        final ChannelPipe<StreamConnection, StreamConnection> pipe = worker.createFullDuplexPipeConnection();
        final OptionMap settings = OptionMap.builder()
                .set(UndertowOptions.MAX_RST_FRAMES_PER_WINDOW, MAX_RST_FRAMES)
                .set(UndertowOptions.RST_FRAMES_TIME_WINDOW, 60000)
                .getMap();
        final Http2Channel channel = new Http2Channel(pipe.getLeftSide(), null, new DefaultByteBufferPool(false, 16 * 1024),
                null, false, false, settings);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            channel.getIoThread().execute(() -> {
                try {
                    for (int i = 0; i < rstFrames; i++) {
                        channel.sendRstStream(2 * i + 1, Http2Channel.ERROR_CANCEL);
                    }
                } finally {
                    latch.countDown();
                }
            });
            Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
            Assert.assertEquals(goAwayExpected, channel.isThisGoneAway());
        } finally {
            IoUtils.safeClose(channel);
            IoUtils.safeClose(pipe.getLeftSide());
            IoUtils.safeClose(pipe.getRightSide());
        }
    }
}

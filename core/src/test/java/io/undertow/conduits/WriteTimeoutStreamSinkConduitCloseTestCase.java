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

package io.undertow.conduits;

import java.lang.reflect.Field;

import io.undertow.UndertowOptions;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.protocol.http.HttpOpenListener;
import io.undertow.testutils.category.UnitTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.xnio.ChannelPipe;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.StreamConnection;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

/**
 * Checks that the write timeout task is removed when the connection is closed by someone else.
 */
@Category(UnitTest.class)
public class WriteTimeoutStreamSinkConduitCloseTestCase {

    private XnioWorker worker;

    @Before
    public void createWorker() throws Exception {
        worker = Xnio.getInstance().createWorker(OptionMap.EMPTY);
    }

    @After
    public void shutdownWorker() {
        worker.shutdownNow();
    }

    @Test
    public void testTimeoutTaskRemovedWhenConnectionClosedElsewhere() throws Exception {
        ChannelPipe<StreamConnection, StreamConnection> pipe = worker.createFullDuplexPipeConnection();
        StreamConnection connection = pipe.getLeftSide();
        try {
            HttpOpenListener openListener = new HttpOpenListener(new DefaultByteBufferPool(false, 1024),
                    OptionMap.create(UndertowOptions.IDLE_TIMEOUT, 60000));
            WriteTimeoutStreamSinkConduit conduit = new WriteTimeoutStreamSinkConduit(
                    connection.getSinkChannel().getConduit(), connection, openListener);
            connection.getSinkChannel().setConduit(conduit);

            Field handleField = WriteTimeoutStreamSinkConduit.class.getDeclaredField("handle");
            handleField.setAccessible(true);

            conduit.resumeWrites();
            Assert.assertNotNull("timeout task should be scheduled", handleField.get(conduit));

            connection.close();
            long deadline = System.currentTimeMillis() + 5000;
            while (handleField.get(conduit) != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            Assert.assertNull("timeout task should be removed once the connection is closed", handleField.get(conduit));
        } finally {
            IoUtils.safeClose(pipe.getLeftSide(), pipe.getRightSide());
        }
    }
}

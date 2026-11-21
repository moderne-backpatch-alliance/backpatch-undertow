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

package io.undertow.protocols.ssl;

import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

import org.junit.Assert;
import org.junit.Test;
import org.xnio.StreamConnection;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.StreamSourceConduit;

import io.undertow.server.DefaultByteBufferPool;
import io.undertow.testutils.category.UnitTest;
import org.junit.experimental.categories.Category;

/**
 * Checks that a write reports every byte that {@link SslConduit} wrapped when the engine
 * needs more than one {@code wrap} call (CVE-2024-5971).
 */
@Category(UnitTest.class)
public class SslConduitWrapConsumedBytesTestCase {

    @Test
    public void testWriteReportsBytesConsumedByAllWrapCalls() throws Exception {
        // first wrap consumes 6 bytes and asks for another wrap, the second one consumes the other 4
        final int[] consumedPerWrap = {6, 4};
        final AtomicInteger wrapCalls = new AtomicInteger();

        SSLEngine engine = createNiceMock(SSLEngine.class);
        expect(engine.getUseClientMode()).andReturn(true).anyTimes();
        expect(engine.isInboundDone()).andReturn(false).anyTimes();
        expect(engine.wrap(anyObject(ByteBuffer[].class), anyInt(), anyInt(), anyObject(ByteBuffer.class))).andAnswer(() -> {
            int call = wrapCalls.getAndIncrement();
            ByteBuffer[] srcs = (ByteBuffer[]) getCurrentArguments()[0];
            ByteBuffer dst = (ByteBuffer) getCurrentArguments()[3];
            ByteBuffer src = srcs[(Integer) getCurrentArguments()[1]];
            int consumed = Math.min(consumedPerWrap[call], src.remaining());
            for (int i = 0; i < consumed; i++) {
                dst.put(src.get());
            }
            SSLEngineResult.HandshakeStatus hs = call == 0 ? SSLEngineResult.HandshakeStatus.NEED_WRAP
                    : SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
            return new SSLEngineResult(SSLEngineResult.Status.OK, hs, consumed, consumed);
        }).times(2);

        StreamSinkConduit sink = createNiceMock(StreamSinkConduit.class);
        expect(sink.write(anyObject(ByteBuffer.class))).andAnswer(() -> {
            ByteBuffer buf = (ByteBuffer) getCurrentArguments()[0];
            int written = buf.remaining();
            buf.position(buf.limit());
            return written;
        }).anyTimes();
        StreamSourceConduit source = createNiceMock(StreamSourceConduit.class);

        StreamConnection delegate = createNiceMock(StreamConnection.class);
        expect(delegate.getSinkChannel()).andReturn(new ConduitStreamSinkChannel(null, sink)).anyTimes();
        expect(delegate.getSourceChannel()).andReturn(new ConduitStreamSourceChannel(null, source)).anyTimes();
        replay(engine, sink, source, delegate);

        SslConduit conduit = new SslConduit(null, delegate, engine, null,
                new DefaultByteBufferPool(false, 1024, -1, 0), () -> { });

        ByteBuffer data = ByteBuffer.wrap("0123456789".getBytes("US-ASCII"));
        int reported = conduit.write(data);

        Assert.assertEquals(2, wrapCalls.get());
        Assert.assertEquals(0, data.remaining());
        Assert.assertEquals("bytes reported as written must match the bytes the engine consumed", 10, reported);
    }
}

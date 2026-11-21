package io.undertow.server.protocol.proxy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.server.DefaultByteBufferPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.xnio.OptionMap;

/**
 * Tests the state the proxy protocol v1 parser keeps between and after parse calls.
 */
public class ProxyProtocolV1ParserStateTestCase {

    private ByteBufferPool bufferPool;
    private ProxyProtocolReadListener listener;

    @Before
    public void setUp() {
        bufferPool = new DefaultByteBufferPool(false, 1024);
        listener = new ProxyProtocolReadListener(null, null, null, bufferPool, OptionMap.EMPTY);
    }

    @After
    public void tearDown() {
        bufferPool.close();
    }

    @Test
    public void testNoBytesKeptAfterInvalidSourceAddress() throws Exception {
        try {
            parse("PROXY TCP4 not-an-address ");
            Assert.fail("invalid source address was accepted");
        } catch (InvocationTargetException expected) {
            // the parser rejects the header
        }
        Assert.assertEquals("", pendingBytes());
    }

    @Test
    public void testNoBytesKeptAfterLineFeedInHeader() throws Exception {
        try {
            parse("PROXY TCP4 1.2.3.4\n");
            Assert.fail("line feed inside the header was accepted");
        } catch (InvocationTargetException expected) {
            // the parser rejects the header
        }
        Assert.assertEquals("", pendingBytes());
    }

    @Test
    public void testHeaderSplitAcrossParseCalls() throws Exception {
        parse("PROXY TCP4 1.2.3");
        parse(".4 5.6.7.8 ");
        Assert.assertEquals(InetAddress.getByName("1.2.3.4"), field("sourceAddress").get(listener));
        Assert.assertEquals(InetAddress.getByName("5.6.7.8"), field("destAddress").get(listener));
    }

    private void parse(String data) throws Exception {
        PooledByteBuffer buffer = bufferPool.allocate();
        try {
            buffer.getBuffer().put(data.getBytes(StandardCharsets.US_ASCII)).flip();
            Method method = ProxyProtocolReadListener.class.getDeclaredMethod("parseProxyProtocolV1", PooledByteBuffer.class, AtomicBoolean.class);
            method.setAccessible(true);
            method.invoke(listener, buffer, new AtomicBoolean(true));
        } finally {
            buffer.close();
        }
    }

    private String pendingBytes() throws Exception {
        return field("stringBuilder").get(listener).toString();
    }

    private static Field field(String name) throws Exception {
        Field field = ProxyProtocolReadListener.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

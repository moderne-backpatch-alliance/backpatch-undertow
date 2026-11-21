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

package io.undertow.server.security;

import io.undertow.predicate.Predicates;
import io.undertow.security.api.AuthenticationMechanism;
import io.undertow.security.impl.BasicAuthenticationMechanism;
import io.undertow.security.impl.CachedAuthenticatedSessionMechanism;
import io.undertow.security.impl.FormAuthenticationMechanism;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PredicateHandler;
import io.undertow.server.session.InMemorySessionManager;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.FlexBase64;
import io.undertow.util.Headers;
import io.undertow.util.Sessions;
import io.undertow.util.StatusCodes;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HttpContext;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * CVE-2023-1973: a session created by FORM authentication for an unauthenticated
 * client must get a short timeout, and the configured timeout once authenticated.
 */
@RunWith(DefaultServer.class)
public class FormAuthSessionTimeoutTestCase extends AuthenticationTestBase {

    private static final int CONFIGURED_TIMEOUT = 30 * 60;
    private static final int AUTHENTICATION_TIMEOUT = 120;

    private static final InMemorySessionManager sessionManager = new InMemorySessionManager("test");

    @Override
    protected void setRootHandler(HttpHandler current) {
        sessionManager.setDefaultSessionTimeout(CONFIGURED_TIMEOUT);
        HttpHandler handler = new PredicateHandler(Predicates.path("/login"), new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                exchange.getResponseSender().send("Login Page");
            }
        }, current);
        handler = new PredicateHandler(Predicates.path("/create-session"), new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                Sessions.getOrCreateSession(exchange);
                exchange.getResponseSender().send("Session");
            }
        }, handler);
        super.setRootHandler(new SessionAttachmentHandler(handler, sessionManager, new SessionCookieConfig()));
    }

    @Override
    protected boolean cachingRequired() {
        return true;
    }

    @Override
    protected List<AuthenticationMechanism> getTestMechanisms() {
        List<AuthenticationMechanism> ret = new ArrayList<>();
        ret.add(new CachedAuthenticatedSessionMechanism());
        ret.add(new FormAuthenticationMechanism("test", "/login", "/error"));
        ret.add(new BasicAuthenticationMechanism("Test Realm"));
        return ret;
    }

    @Test
    public void testNewSessionIsShortUntilFormLogin() throws IOException {
        TestHttpClient client = createClient();
        try {
            requestLoginPage(client);
            assertEquals(AUTHENTICATION_TIMEOUT, currentSession(client).getMaxInactiveInterval());

            final List<NameValuePair> data = new ArrayList<>();
            data.add(new BasicNameValuePair("j_username", "userOne"));
            data.add(new BasicNameValuePair("j_password", "passwordOne"));
            HttpPost post = new HttpPost(DefaultServer.getDefaultServerURL() + "/j_security_check");
            post.setEntity(new UrlEncodedFormEntity(data));
            HttpResponse result = client.execute(post);
            assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            assertEquals("ResponseHandler", result.getFirstHeader("ProcessedBy").getValue());
            HttpClientUtils.readResponse(result);

            assertEquals(CONFIGURED_TIMEOUT, currentSession(client).getMaxInactiveInterval());
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testNewSessionIsShortUntilOtherMechanismLogin() throws IOException {
        TestHttpClient client = createClient();
        try {
            requestLoginPage(client);
            assertEquals(AUTHENTICATION_TIMEOUT, currentSession(client).getMaxInactiveInterval());

            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/secured/test");
            get.addHeader(Headers.AUTHORIZATION.toString(), "Basic "
                    + FlexBase64.encodeString("userOne:passwordOne".getBytes(StandardCharsets.UTF_8), false));
            HttpResponse result = client.execute(get);
            assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            assertEquals("ResponseHandler", result.getFirstHeader("ProcessedBy").getValue());
            HttpClientUtils.readResponse(result);

            assertEquals(CONFIGURED_TIMEOUT, currentSession(client).getMaxInactiveInterval());
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testExistingSessionKeepsConfiguredTimeout() throws IOException {
        TestHttpClient client = createClient();
        try {
            HttpResponse result = client.execute(new HttpGet(DefaultServer.getDefaultServerURL() + "/create-session"));
            assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            assertEquals(CONFIGURED_TIMEOUT, currentSession(client).getMaxInactiveInterval());

            requestLoginPage(client);
            assertEquals(CONFIGURED_TIMEOUT, currentSession(client).getMaxInactiveInterval());
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    private static TestHttpClient createClient() {
        TestHttpClient client = new TestHttpClient();
        client.setRedirectStrategy(new DefaultRedirectStrategy() {
            @Override
            public boolean isRedirected(final HttpRequest request, final HttpResponse response, final HttpContext context) throws ProtocolException {
                if (response.getStatusLine().getStatusCode() == StatusCodes.FOUND) {
                    return true;
                }
                return super.isRedirected(request, response, context);
            }
        });
        return client;
    }

    private static void requestLoginPage(final TestHttpClient client) throws IOException {
        HttpResponse result = client.execute(new HttpGet(DefaultServer.getDefaultServerURL() + "/secured/test"));
        assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
        Assert.assertEquals("Login Page", HttpClientUtils.readResponse(result));
    }

    private static Session currentSession(final TestHttpClient client) {
        for (Cookie cookie : client.getCookieStore().getCookies()) {
            if (cookie.getName().equals("JSESSIONID")) {
                Session session = sessionManager.getSession(cookie.getValue());
                Assert.assertNotNull("No session " + cookie.getValue(), session);
                return session;
            }
        }
        Assert.fail("No session cookie");
        return null;
    }
}

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

package io.undertow.servlet.test.security.form;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.LoginConfig;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ServletSecurityInfo;
import io.undertow.servlet.test.SimpleServletTestCase;
import io.undertow.servlet.test.security.constraint.ServletIdentityManager;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.StatusCodes;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HttpContext;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/**
 * CVE-2023-1973: a session created by FORM authentication for an unauthenticated
 * client must get a short timeout, and the configured timeout once authenticated.
 */
@RunWith(DefaultServer.class)
public class ServletFormAuthSessionTimeoutTestCase {

    private static final int CONFIGURED_TIMEOUT_MINUTES = 30;

    @BeforeClass
    public static void setup() throws ServletException {
        final PathHandler path = new PathHandler();
        final ServletContainer container = ServletContainer.Factory.newInstance();

        ServletInfo secured = new ServletInfo("secured", SessionTimeoutServlet.class)
                .setServletSecurityInfo(new ServletSecurityInfo()
                        .addRoleAllowed("role1"))
                .addMapping("/secured/*");

        ServletInfo login = new ServletInfo("loginPage", SessionTimeoutServlet.class)
                .addMapping("/LoginPage");

        ServletIdentityManager identityManager = new ServletIdentityManager();
        identityManager.addUser("user1", "password1", "role1");

        DeploymentInfo builder = new DeploymentInfo()
                .setClassLoader(SimpleServletTestCase.class.getClassLoader())
                .setContextPath("/servletContext")
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .setDeploymentName("servletContext.war")
                .setIdentityManager(identityManager)
                .setDefaultSessionTimeout(CONFIGURED_TIMEOUT_MINUTES * 60)
                .setLoginConfig(new LoginConfig("FORM", "Test Realm", "/LoginPage", "/error.html"))
                .addServlets(secured, login);

        DeploymentManager manager = container.addDeployment(builder);
        manager.deploy();
        path.addPrefixPath(builder.getContextPath(), manager.start());

        DefaultServer.setRootHandler(path);
    }

    @Test
    public void testNewSessionIsShortUntilFormLogin() throws IOException {
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
        try {
            HttpResponse result = client.execute(new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/secured/test"));
            assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            assertEquals("120", HttpClientUtils.readResponse(result));

            final List<NameValuePair> data = new ArrayList<>();
            data.add(new BasicNameValuePair("j_username", "user1"));
            data.add(new BasicNameValuePair("j_password", "password1"));
            HttpPost post = new HttpPost(DefaultServer.getDefaultServerURL() + "/servletContext/j_security_check");
            post.setEntity(new UrlEncodedFormEntity(data));
            result = client.execute(post);
            assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            assertEquals(String.valueOf(CONFIGURED_TIMEOUT_MINUTES * 60), HttpClientUtils.readResponse(result));
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    public static class SessionTimeoutServlet extends HttpServlet {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
            HttpSession session = req.getSession(false);
            resp.getWriter().write(session == null ? "no session" : String.valueOf(session.getMaxInactiveInterval()));
        }

        @Override
        protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
            doGet(req, resp);
        }
    }
}

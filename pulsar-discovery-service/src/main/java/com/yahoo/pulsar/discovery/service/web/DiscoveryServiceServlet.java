/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.discovery.service.web;

import static org.apache.bookkeeper.util.MathUtils.signSafeMod;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.pulsar.common.policies.data.loadbalancer.LoadReport;
import com.yahoo.pulsar.zookeeper.ZooKeeperClientFactory;
import com.yahoo.pulsar.zookeeper.ZookeeperClientFactoryImpl;

/**
 * Acts a load-balancer that receives any incoming request and discover active-available broker in round-robin manner
 * and redirect request to that broker.
 * <p>
 * Accepts any {@value GET, PUT, POST} request and redirects to available broker-server to serve the request
 * </p>
 *
 */
public class DiscoveryServiceServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final AtomicInteger counter = new AtomicInteger();

    private ZookeeperCacheLoader zkCache;

    @Override
    public void init(ServletConfig config) throws ServletException {
        log.info("Initializing DiscoveryServiceServlet resource");

        String zookeeperServers = config.getInitParameter("zookeeperServers");
        String zookeeperClientFactoryClassName = config.getInitParameter("zookeeperClientFactoryClass");
        if (zookeeperClientFactoryClassName == null) {
            zookeeperClientFactoryClassName = ZookeeperClientFactoryImpl.class.getName();
        }

        log.info("zookeeperServers={} zookeeperClientFactoryClass={}", zookeeperServers,
                zookeeperClientFactoryClassName);

        try {
            ZooKeeperClientFactory zkClientFactory = (ZooKeeperClientFactory) Class
                    .forName(zookeeperClientFactoryClassName).newInstance();

            zkCache = new ZookeeperCacheLoader(zkClientFactory, zookeeperServers);
        } catch (Throwable t) {
            throw new ServletException(t);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        redirect(req, resp);
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        redirect(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        redirect(req, resp);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        redirect(req, resp);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        redirect(req, resp);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        redirect(req, resp);
    }

    @Override
    protected void doTrace(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        redirect(req, resp);
    }

    private void redirect(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            LoadReport broker = nextBroker();

            URI brokerURI;
            if (request.getScheme().equals("http")) {
                // Use normal HTTP url
                brokerURI = new URI(broker.getWebServiceUrl());
            } else {
                brokerURI = new URI(broker.getWebServiceUrlTls());
            }

            StringBuilder location = new StringBuilder();
            location.append(brokerURI.getScheme()).append("://").append(brokerURI.getHost()).append(':')
                    .append(brokerURI.getPort()).append(request.getRequestURI());
            if (request.getQueryString() != null) {
                location.append('?').append(request.getQueryString());
            }
            if (log.isDebugEnabled()) {
                log.info("Redirecting to {}", location);
            }
            response.sendRedirect(location.toString());
        } catch (URISyntaxException e) {
            log.warn("No broker found in zookeeper {}", e.getMessage(), e);
            throw new RestException(Status.SERVICE_UNAVAILABLE, "Broker is not available");
        }
    }

    /**
     * Find next broke url in round-robin
     *
     * @return
     */
    LoadReport nextBroker() {
        List<LoadReport> availableBrokers = zkCache.getAvailableBrokers();

        if (availableBrokers.isEmpty()) {
            throw new RestException(Status.SERVICE_UNAVAILABLE, "No active broker is available");
        } else {
            int brokersCount = availableBrokers.size();
            int nextIdx = signSafeMod(counter.getAndIncrement(), brokersCount);
            return availableBrokers.get(nextIdx);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DiscoveryServiceServlet.class);
}

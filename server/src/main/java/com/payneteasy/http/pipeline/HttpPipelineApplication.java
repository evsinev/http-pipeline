package com.payneteasy.http.pipeline;

import com.payneteasy.http.pipeline.metrics.ThreadPoolExecutorCollector;
import com.payneteasy.http.pipeline.servlet.PipelineServlet;
import com.payneteasy.http.pipeline.servlet.VersionServlet;
import com.payneteasy.http.pipeline.upstream.UpstreamExecutor;
import com.payneteasy.http.pipeline.upstream.UpstreamExecutors;
import com.payneteasy.startup.parameters.StartupParametersFactory;
import io.prometheus.client.exporter.MetricsServlet;
import io.prometheus.client.jetty.JettyStatisticsCollector;
import io.prometheus.client.jetty.QueuedThreadPoolStatisticsCollector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class HttpPipelineApplication {
    
    public static void main(String[] args) throws Exception {

        IStartupConfig startupConfig = StartupParametersFactory.getStartupParameters(IStartupConfig.class);

        Server jetty = createJettyServer(startupConfig);
        jetty.start();
        jetty.setStopAtShutdown(true);
    }

    private static Server createJettyServer(IStartupConfig aConfig) {
        QueuedThreadPool threadPool = new QueuedThreadPool(
                  aConfig.getJettyMaxThreads()
                , aConfig.getJettyMinThreads()
                , aConfig.getJettyIdleTimeoutMs()
        );

        new QueuedThreadPoolStatisticsCollector(threadPool, "server_thread_pool").register();

        Server jetty = new Server(threadPool);

        ServerConnector connector=new ServerConnector(jetty);

        connector.setPort(aConfig.webServerPort());
        jetty.setConnectors(new Connector[]{connector});


        ServletContextHandler context = new ServletContextHandler(jetty, aConfig.webServerContext(), ServletContextHandler.SESSIONS);

        registerJettyMetrics(jetty);

        UpstreamExecutors executors = new UpstreamExecutors(aConfig.getUpstreamMaxConnections(), aConfig.getUpstreamQueueSize());
        registerExecutorsMetrics(executors);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> executors.stop()));

        PipelineServlet pipelineServlet = new PipelineServlet(
                aConfig.getUpstreamBaseUrl()
                , executors
                , aConfig.getUpstreamConnectTimeoutMs()
                , aConfig.getUpstreamReadTimeoutMs()
                , aConfig.getUpstreamWaitTimeoutMs()
        );
        context.addServlet(new ServletHolder(pipelineServlet), aConfig.getPipelineServletPath());
        context.addServlet(new ServletHolder(new VersionServlet()), "/version/*");
        context.addServlet(new ServletHolder(new MetricsServlet()), aConfig.getMetricsServletPath());
        return jetty;
    }

    private static void registerExecutorsMetrics(UpstreamExecutors executors) {
        int i = 0;
        for (UpstreamExecutor upstreamExecutor : executors.geExecutors()) {
            new ThreadPoolExecutorCollector("executor_" + (i++), "help", upstreamExecutor).register();
        }
    }

    private static void registerJettyMetrics(Server jetty) {
        // Configure StatisticsHandler.
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(jetty.getHandler());
        jetty.setHandler(stats);
        // Register collector.
        new JettyStatisticsCollector(stats).register();
    }
}

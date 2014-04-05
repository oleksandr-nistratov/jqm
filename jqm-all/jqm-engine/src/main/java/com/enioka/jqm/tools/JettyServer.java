package com.enioka.jqm.tools;

import java.io.File;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.TagLibConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

import com.enioka.jqm.jpamodel.Node;

/**
 * Every engine has an embedded Jetty engine that serves the servlet API ({@link ServletFile}, {@link ServletStatus}, {@link ServletEnqueue}
 * ). It may also serve the REST API.
 */
class JettyServer
{
    private static Logger jqmlogger = Logger.getLogger(JettyServer.class);

    private Server server = null;
    private HandlerCollection h = new HandlerCollection();

    void start(Node node)
    {
        server = new Server();

        // Connectors.
        List<Connector> ls = new ArrayList<Connector>();
        try
        {
            InetAddress[] adresses = InetAddress.getAllByName(node.getDns());
            for (InetAddress s : adresses)
            {
                if (s instanceof Inet4Address)
                {
                    Connector connector = new SelectChannelConnector();
                    connector.setHost(s.getHostAddress());
                    connector.setPort(node.getPort());
                    ls.add(connector);
                    jqmlogger.debug("Jetty will bind on " + s.getHostAddress() + ":" + node.getPort());
                }
            }
        }
        catch (UnknownHostException e1)
        {
            jqmlogger.warn("Could not resolve name " + node.getDns() + ". Will bind on all interfaces.");
            Connector connector = new SelectChannelConnector();
            connector.setHost(null);
            connector.setPort(node.getPort());
            ls.add(connector);
        }
        server.setConnectors(ls.toArray(new Connector[ls.size()]));

        // Collection handler
        server.setHandler(h);

        // Servlets (basic script API + files)
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        context.addServlet(new ServletHolder(new ServletFile()), "/getfile");
        context.addServlet(new ServletHolder(new ServletEnqueue()), "/enqueue");
        context.addServlet(new ServletHolder(new ServletStatus()), "/status");
        context.addServlet(new ServletHolder(new ServletLog()), "/log");

        h.addHandler(context);

        // Potentially add a webapp context
        loadWar();

        // Start the server
        jqmlogger.trace("Starting Jetty (port " + node.getPort() + ")");
        try
        {
            server.start();
        }
        catch (BindException e)
        {
            // JETTY-839: threadpool not daemon nor close on exception. Explicit closing required as workaround.
            this.stop();
            throw new JqmInitError("Could not start web server - check there is no other process binding on this port & interface", e);
        }
        catch (Exception e)
        {
            throw new JqmInitError("Could not start web server - not a port issue, but a generic one", e);
        }
        jqmlogger.info("Jetty has started on port " + getActualPort());
    }

    int getActualPort()
    {
        return server.getConnectors()[0].getLocalPort();
    }

    void stop()
    {
        jqmlogger.trace("Jetty will now stop");
        try
        {
            this.server.stop();
            this.server.join();
            jqmlogger.info("Jetty has stopped");
        }
        catch (Exception e)
        {
            // Who cares, we are dying.
        }
    }

    private void loadWar()
    {
        File war = new File("./webapp/jqm-ws.war");
        if (!war.exists() || !war.isFile())
        {
            return;
        }
        jqmlogger.info("Jetty will now load the web service application war");

        // Load web application.
        WebAppContext webAppContext = new WebAppContext(war.getPath(), "/api");
        webAppContext.setDisplayName("JqmWebServices");

        // Hide server classes from the web app
        final int nbEx = 4;
        String[] defExcl = webAppContext.getDefaultServerClasses();
        String[] exclusions = new String[defExcl.length + nbEx];
        for (int i = nbEx; i <= defExcl.length; i++)
        {
            exclusions[i] = defExcl[i - nbEx];
        }
        exclusions[0] = "com.enioka.";
        exclusions[1] = "org.slf4j.";
        exclusions[2] = "org.apache.log4j.";
        exclusions[3] = "org.glassfish."; // Jersey
        webAppContext.setServerClasses(exclusions);

        // JQM configuration should be on the class path
        webAppContext.setExtraClasspath("conf/jqm.properties");

        // Set configurations (order is important: need to unpack war before reading web.xml)
        webAppContext.setConfigurations(new Configuration[] { new WebInfConfiguration(), new WebXmlConfiguration(),
                new MetaInfConfiguration(), new FragmentConfiguration(), new AnnotationConfiguration(), new TagLibConfiguration() });

        h.addHandler(webAppContext);
    }
}

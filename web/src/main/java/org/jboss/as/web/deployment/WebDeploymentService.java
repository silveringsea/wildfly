/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.web.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletContext;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.Realm;
import org.apache.catalina.core.StandardContext;
import org.jboss.as.server.deployment.SetupAction;
import org.jboss.as.web.ThreadSetupBindingListener;
import org.jboss.as.web.common.WebInjectionContainer;
import org.jboss.as.web.host.ContextActivator;
import org.jboss.msc.inject.Injector;
import org.jboss.as.web.common.ServletContextAttribute;
import org.jboss.as.web.common.StartupContext;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.StabilityMonitor;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

import static org.jboss.as.web.WebLogger.WEB_LOGGER;
import static org.jboss.as.web.WebMessages.MESSAGES;

/**
 * A service starting a web deployment.
 *
 * @author Emanuel Muckenhuber
 * @author Thomas.Diesler@jboss.com
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class WebDeploymentService implements Service<StandardContext> {

    private final StandardContext context;
    private final InjectedValue<Realm> realm = new InjectedValue<Realm>();
    private final WebInjectionContainer injectionContainer;
    private final List<SetupAction> setupActions;
    final List<ServletContextAttribute> attributes;
    // used for blocking tasks in this Service's start/stop
    private final InjectedValue<ExecutorService> serverExecutor = new InjectedValue<ExecutorService>();

    public WebDeploymentService(final StandardContext context, final WebInjectionContainer injectionContainer, final List<SetupAction> setupActions,
                                final List<ServletContextAttribute> attributes) {
        this.context = context;
        this.injectionContainer = injectionContainer;
        this.setupActions = setupActions;
        this.attributes = attributes;
    }

    InjectedValue<Realm> getRealm() {
        return realm;
    }

    @Override
    public synchronized void start(final StartContext startContext) throws StartException {
        // https://issues.jboss.org/browse/AS7-5969 WebDeploymentService start can trigger the web app context initialization
        // which involves blocking tasks like servlet context initialization, startup servlet
        // initialization lifecycles and such. Hence this needs to be done asynchronously
        // to prevent the MSC threads from blocking
        startContext.asynchronous();
        serverExecutor.getValue().submit(new Runnable() {
            @Override
            public void run() {
                try {
                    doStart();
                    startContext.complete();
                } catch (Throwable e) {
                    startContext.failed(new StartException(e));
                }
            }
        });
    }

    @Override
    public synchronized void stop(final StopContext stopContext) {
        // https://issues.jboss.org/browse/AS7-5969 WebDeploymentService stop can trigger the web app context destruction
        // which involves blocking tasks like servlet context destruction, startup servlet
        // destruction lifecycles and such. Hence this needs to be done asynchronously
        // to prevent the MSC threads from blocking
        stopContext.asynchronous();
        serverExecutor.getValue().submit(new Runnable() {
            @Override
            public void run() {
                try {
                    doStop();
                } finally {
                    stopContext.complete();
                }
            }
        });
    }

    @Override
    public synchronized StandardContext getValue() throws IllegalStateException {
        if (context == null) {
            throw new IllegalStateException();
        }
        return context;
    }

    Injector<ExecutorService> getServerExecutorInjector() {
        return this.serverExecutor;
    }

    private void doStart() throws StartException {
        StartupContext.setInjectionContainer(injectionContainer);
        try {
            if (attributes != null) {
                final ServletContext context = this.context.getServletContext();
                for (ServletContextAttribute attribute : attributes) {
                    context.setAttribute(attribute.getName(), attribute.getValue());
                }
            }

            context.setRealm(realm.getValue());

            final List<SetupAction> actions = new ArrayList<SetupAction>();
            actions.addAll(setupActions);
            context.setInstanceManager(new WebInstanceManager(injectionContainer));
            context.setThreadBindingListener(new ThreadSetupBindingListener(actions));
            WEB_LOGGER.registerWebapp(context.getName());
            try {
                context.create();
            } catch (Exception e) {
                throw new StartException(MESSAGES.createContextFailed(), e);
            }
            try {
                context.start();
            } catch (LifecycleException e) {
                throw new StartException(MESSAGES.startContextFailed(), e);
            }
            if (context.getState() != 1) {
                throw new StartException(MESSAGES.startContextFailed());
            }
        } finally {
            StartupContext.setInjectionContainer(null);
        }
    }

    private void doStop() {
        WEB_LOGGER.unregisterWebapp(context.getName());
        try {
            context.stop();
        } catch (LifecycleException e) {
            WEB_LOGGER.stopContextFailed(e);
        }
        try {
            context.destroy();
        } catch (Exception e) {
            WEB_LOGGER.destroyContextFailed(e);
        }

    }

    /**
     * Provides an API to start/stop the {@link WebDeploymentService}.
     * This should register/deregister the web context.
     */
    public static class ContextActivatorImpl implements ContextActivator {

        private final ServiceController<StandardContext> controller;

        ContextActivatorImpl(ServiceController<StandardContext> controller) {
            this.controller = controller;
        }

        /**
         * Provide access to the Servlet Context.
         */
        public StandardContext getContext() {
            return controller.getValue();
        }

        /**
         * Start the web context asynchronously.
         * <p/>
         * This would happen during OSGi webapp deployment.
         * <p/>
         * No DUP can assume that all dependencies are available to make a blocking call
         * instead it should call this method.
         */
        public synchronized void startAsync() {
            controller.setMode(Mode.ACTIVE);
        }

        /**
         * Start the web context synchronously.
         * <p/>
         * This would happen when the OSGi webapp gets explicitly started.
         */
        public synchronized boolean start(long timeout, TimeUnit unit) throws TimeoutException {
            if (controller.getMode() == Mode.NEVER) {
                controller.setMode(Mode.ACTIVE);
                final StabilityMonitor monitor = new StabilityMonitor();
                monitor.addController(controller);
                try {
                    if (!monitor.awaitStability(timeout, unit)) {
                        throw MESSAGES.timeoutContextActivation(controller.getName());
                    }
                } catch (final InterruptedException e) {
                    // ignore
                } finally {
                    monitor.removeController(controller);
                }
            }
            return true;
        }

        /**
         * Stop the web context synchronously.
         * <p/>
         * This would happen when the OSGi webapp gets explicitly stops.
         */
        public synchronized boolean stop(long timeout, TimeUnit unit) {
            boolean result = true;
            if (controller.getMode() == Mode.ACTIVE) {
                controller.setMode(Mode.NEVER);
                final StabilityMonitor monitor = new StabilityMonitor();
                monitor.addController(controller);
                try {
                    if (!monitor.awaitStability(timeout, unit)) {
                        WEB_LOGGER.debugf("Timeout stopping context: %s", controller.getName());
                    }
                } catch (final InterruptedException e) {
                    // ignore
                } finally {
                    monitor.removeController(controller);
                }
            }
            return result;
        }

        @Override
        public ServletContext getServletContext() {
            return getContext().getServletContext();
        }
    }
}

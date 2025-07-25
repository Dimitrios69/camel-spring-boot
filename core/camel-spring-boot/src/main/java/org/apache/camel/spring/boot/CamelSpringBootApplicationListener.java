/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.camel.CamelContext;
import org.apache.camel.StartupListener;
import org.apache.camel.main.MainDurationEventNotifier;
import org.apache.camel.main.MainShutdownStrategy;
import org.apache.camel.main.RoutesCollector;
import org.apache.camel.main.RoutesConfigurer;
import org.apache.camel.main.SimpleMainShutdownStrategy;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.CamelEvent.Type;
import org.apache.camel.spi.EventNotifier;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.support.LifecycleStrategySupport;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.service.ServiceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;

/**
 * A spring application listener that when spring boot is starting (refresh event) will setup Camel by:
 * <p>
 * 1. collecting routes and rests from the various sources (like Spring application context beans registry or
 * opinionated classpath locations) and injects these into the Camel context. 2. setting up Camel main controller if
 * enabled. 3. setting up run duration if in use.
 */
public class CamelSpringBootApplicationListener implements ApplicationListener<ContextRefreshedEvent>, Ordered {

    // Static collaborators

    private static final Logger LOG = LoggerFactory.getLogger(CamelSpringBootApplicationListener.class);

    // Collaborators

    private final ApplicationContext applicationContext;
    private final List<CamelContextConfiguration> camelContextConfigurations;
    private final CamelConfigurationProperties configurationProperties;
    private final RoutesCollector springBootRoutesCollector;

    // Constructors

    public CamelSpringBootApplicationListener(ApplicationContext applicationContext,
            List<CamelContextConfiguration> camelContextConfigurations,
            CamelConfigurationProperties configurationProperties, RoutesCollector springBootRoutesCollector) {
        this.applicationContext = applicationContext;
        this.camelContextConfigurations = new ArrayList<>(camelContextConfigurations);
        this.configurationProperties = configurationProperties;
        this.springBootRoutesCollector = springBootRoutesCollector;
    }

    // Overridden

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        CamelContext camelContext = applicationContext.getBean(CamelContext.class);

        // only add and start Camel if its stopped (initial state)
        if (event.getApplicationContext() == this.applicationContext && camelContext.getStatus().isStopped()) {
            LOG.debug("Post-processing CamelContext bean: {}", camelContext.getName());

            RoutesConfigurer configurer = new RoutesConfigurer(camelContext);
            try {
                // we can use the default routes configurer
                ServiceHelper.startService(configurer);

                if (configurationProperties.getMain().isRoutesCollectorEnabled()) {
                    configurer.setRoutesCollector(springBootRoutesCollector);
                }

                configurer.setBeanPostProcessor(PluginHelper.getBeanPostProcessor(camelContext));
                configurer.setJavaRoutesExcludePattern(configurationProperties.getMain().getJavaRoutesExcludePattern());
                configurer.setJavaRoutesIncludePattern(configurationProperties.getMain().getJavaRoutesIncludePattern());
                configurer.setRoutesExcludePattern(configurationProperties.getMain().getRoutesExcludePattern());
                configurer.setRoutesIncludePattern(configurationProperties.getMain().getRoutesIncludePattern());
                configurer.configureRoutes(camelContext);

                for (CamelContextConfiguration camelContextConfiguration : camelContextConfigurations) {
                    LOG.debug("CamelContextConfiguration found. Invoking beforeApplicationStart: {}",
                            camelContextConfiguration);
                    camelContextConfiguration.beforeApplicationStart(camelContext);
                }

                if (configurationProperties.getMain().isRunController() || configurationProperties.getMain().isMainRunController()) {
                    CamelMainRunController controller = new CamelMainRunController(applicationContext, camelContext);

                    if (configurationProperties.getMain().getDurationMaxMessages() > 0
                            || configurationProperties.getMain().getDurationMaxIdleSeconds() > 0) {
                        if (configurationProperties.getMain().getDurationMaxMessages() > 0) {
                            LOG.info("CamelSpringBoot will terminate after processing {} messages",
                                    configurationProperties.getMain().getDurationMaxMessages());
                        }
                        if (configurationProperties.getMain().getDurationMaxIdleSeconds() > 0) {
                            LOG.info("CamelSpringBoot will terminate after being idle for more {} seconds",
                                    configurationProperties.getMain().getDurationMaxIdleSeconds());
                        }
                        // register lifecycle so we can trigger to shutdown the JVM when maximum number of messages has
                        // been processed
                        EventNotifier notifier = new MainDurationEventNotifier(camelContext,
                                configurationProperties.getMain().getDurationMaxMessages(),
                                configurationProperties.getMain().getDurationMaxIdleSeconds(),
                                controller.getMainShutdownStrategy(), true,
                                configurationProperties.getMain().isRoutesReloadRestartDuration(),
                                configurationProperties.getMain().getDurationMaxAction());
                        // register our event notifier
                        ServiceHelper.startService(notifier);
                        camelContext.getManagementStrategy().addEventNotifier(notifier);
                    }

                    if (configurationProperties.getMain().getDurationMaxSeconds() > 0) {
                        LOG.info("CamelSpringBoot will terminate after {} seconds",
                                configurationProperties.getMain().getDurationMaxSeconds());
                        terminateMainControllerAfter(camelContext,
                                configurationProperties.getMain().getDurationMaxSeconds(),
                                controller.getMainShutdownStrategy(), controller.getMainCompleteTask());
                    }

                    camelContext.addStartupListener(new StartupListener() {
                        @Override
                        public void onCamelContextStarted(CamelContext context, boolean alreadyStarted)
                                throws Exception {
                            // run the CamelMainRunController after the context has been started
                            // this way we ensure that NO_START flag is honoured as it's set as
                            // a thread local variable of the thread CamelMainRunController is
                            // not running on
                            if (!alreadyStarted) {
                                LOG.info("Starting CamelMainRunController to ensure the main thread keeps running");
                                controller.start();
                            }
                        }
                    });
                } else {
                    if (applicationContext instanceof ConfigurableApplicationContext) {
                        ConfigurableApplicationContext cac = (ConfigurableApplicationContext) applicationContext;

                        if (configurationProperties.getMain().getDurationMaxSeconds() > 0) {
                            LOG.info("CamelSpringBoot will terminate after {} seconds",
                                    configurationProperties.getMain().getDurationMaxSeconds());
                            terminateApplicationContext(cac, camelContext,
                                    configurationProperties.getMain().getDurationMaxSeconds());
                        }

                        if (configurationProperties.getMain().getDurationMaxMessages() > 0
                                || configurationProperties.getMain().getDurationMaxIdleSeconds() > 0) {

                            if (configurationProperties.getMain().getDurationMaxMessages() > 0) {
                                LOG.info("CamelSpringBoot will terminate after processing {} messages",
                                        configurationProperties.getMain().getDurationMaxMessages());
                            }
                            if (configurationProperties.getMain().getDurationMaxIdleSeconds() > 0) {
                                LOG.info("CamelSpringBoot will terminate after being idle for more {} seconds",
                                        configurationProperties.getMain().getDurationMaxIdleSeconds());
                            }
                            // needed by MainDurationEventNotifier to signal when we have processed the max messages
                            final MainShutdownStrategy strategy = new SimpleMainShutdownStrategy();

                            // register lifecycle so we can trigger to shutdown the JVM when maximum number of messages
                            // has been processed
                            EventNotifier notifier = new MainDurationEventNotifier(camelContext,
                                    configurationProperties.getMain().getDurationMaxMessages(),
                                    configurationProperties.getMain().getDurationMaxIdleSeconds(), strategy, false,
                                    configurationProperties.getMain().isRoutesReloadRestartDuration(),
                                    configurationProperties.getMain().getDurationMaxAction());

                            // register our event notifier
                            ServiceHelper.startService(notifier);
                            camelContext.getManagementStrategy().addEventNotifier(notifier);

                            terminateApplicationContext(cac, camelContext, strategy);
                        }
                    }
                }

                if (!camelContextConfigurations.isEmpty()) {
                    // we want to call these notifications just after CamelContext has been fully started
                    // so use an event notifier to trigger when this happens
                    camelContext.getManagementStrategy().addEventNotifier(new EventNotifierSupport() {
                        @Override
                        public void notify(CamelEvent eventObject) throws Exception {
                            for (CamelContextConfiguration camelContextConfiguration : camelContextConfigurations) {
                                LOG.debug("CamelContextConfiguration found. Invoking afterApplicationStart: {}",
                                        camelContextConfiguration);
                                try {
                                    camelContextConfiguration.afterApplicationStart(camelContext);
                                } catch (Exception e) {
                                    LOG.warn(
                                            "Error during calling afterApplicationStart due {}. This exception is ignored",
                                            e.getMessage(), e);
                                }
                            }
                        }

                        @Override
                        public boolean isEnabled(CamelEvent eventObject) {
                            return eventObject.getType() == Type.CamelContextStarted;
                        }
                    });
                }
            } catch (Exception e) {
                throw new CamelSpringBootInitializationException(e);
            } finally {
                ServiceHelper.stopService(configurer);
            }
        } else {
            LOG.debug("Camel already started, not adding routes.");
        }
    }

    @Override
    public int getOrder() {
        // RoutesCollector implements Ordered so that it's the
        // first Camel ApplicationListener to receive events,
        // SpringCamelContext should be the last one,
        // CamelContextFactoryBean should be second to last and then
        // RoutesCollector. This is important for startup as we want
        // all resources to be ready and all routes added to the
        // context before we start CamelContext.
        // So the order should be:
        // 1. RoutesCollector (LOWEST_PRECEDENCE - 2)
        // 2. CamelContextFactoryBean (LOWEST_PRECEDENCE -1)
        // 3. SpringCamelContext (LOWEST_PRECEDENCE)
        return LOWEST_PRECEDENCE - 2;
    }

    // Helpers

    private void terminateMainControllerAfter(final CamelContext camelContext, int seconds,
            final MainShutdownStrategy shutdownStrategy, final Runnable mainCompletedTask) {
        ScheduledExecutorService executorService = camelContext.getExecutorServiceManager()
                .newSingleThreadScheduledExecutor(this, "CamelSpringBootTerminateTask");

        final AtomicBoolean running = new AtomicBoolean();
        Runnable task = () -> {
            // need to spin up as separate thread so we can terminate this thread pool without problems
            Runnable stop = () -> {
                running.set(true);
                LOG.info("CamelSpringBoot triggering shutdown of the JVM.");
                try {
                    camelContext.stop();
                } catch (Throwable e) {
                    LOG.warn("Error during stopping CamelContext", e);
                } finally {
                    shutdownStrategy.shutdown();
                    mainCompletedTask.run();
                }
                running.set(false);
            };
            new Thread(stop, "CamelSpringBootTerminateTaskWorker").start();
        };

        final ScheduledFuture<?> future = executorService.schedule(task, seconds, TimeUnit.SECONDS);
        camelContext.addLifecycleStrategy(new LifecycleStrategySupport() {
            @Override
            public void onContextStopping(CamelContext context) {
                // we are stopping then cancel the task so we can shutdown quicker
                if (!running.get()) {
                    future.cancel(true);
                    // trigger shutdown
                    shutdownStrategy.shutdown();
                    mainCompletedTask.run();
                }
            }
        });
    }

    private void terminateApplicationContext(final ConfigurableApplicationContext applicationContext,
            final CamelContext camelContext, int seconds) {
        ScheduledExecutorService executorService = camelContext.getExecutorServiceManager()
                .newSingleThreadScheduledExecutor(this, "CamelSpringBootTerminateTask");

        final AtomicBoolean running = new AtomicBoolean();
        Runnable task = () -> {
            // need to spin up as separate thread so we can terminate this thread pool without problems
            Runnable stop = () -> {
                running.set(true);
                LOG.info("CamelSpringBoot triggering shutdown of the JVM.");
                // we need to run a daemon thread to stop ourselves so this thread pool can be stopped nice also
                new Thread(applicationContext::close).start();
                running.set(false);
            };
            new Thread(stop, "CamelSpringBootTerminateTaskWorker").start();
        };

        final ScheduledFuture<?> future = executorService.schedule(task, seconds, TimeUnit.SECONDS);
        camelContext.addLifecycleStrategy(new LifecycleStrategySupport() {
            @Override
            public void onContextStopping(CamelContext context) {
                // we are stopping then cancel the task so we can shutdown quicker
                if (!running.get()) {
                    future.cancel(true);
                }
            }
        });
    }

    private void terminateApplicationContext(final ConfigurableApplicationContext applicationContext,
            final CamelContext camelContext, final MainShutdownStrategy shutdownStrategy) {
        ExecutorService executorService = camelContext.getExecutorServiceManager().newSingleThreadExecutor(this,
                "CamelSpringBootTerminateTask");

        final AtomicBoolean running = new AtomicBoolean();
        Runnable task = () -> {
            try {
                shutdownStrategy.await();
                // only mark as running after the latch
                running.set(true);
                LOG.info("CamelSpringBoot triggering shutdown of the JVM.");
                // we need to run a daemon thread to stop ourselves so this thread pool can be stopped nice also
                new Thread(applicationContext::close).start();
            } catch (Throwable e) {
                // ignore
            }
            running.set(false);
        };

        final Future<?> future = executorService.submit(task);
        camelContext.addLifecycleStrategy(new LifecycleStrategySupport() {
            @Override
            public void onContextStopping(CamelContext context) {
                // we are stopping then cancel the task so we can shutdown quicker
                if (!running.get()) {
                    future.cancel(true);
                } else {
                    // trigger shutdown
                    shutdownStrategy.shutdown();
                }
            }
        });
    }

}

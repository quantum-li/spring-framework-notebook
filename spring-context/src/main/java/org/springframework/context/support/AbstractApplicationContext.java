/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.support.ResourceEditorRegistrar;
import org.springframework.context.*;
import org.springframework.context.event.*;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.context.weaving.LoadTimeWeaverAwareProcessor;
import org.springframework.core.NativeDetector;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Abstract implementation of the {@link org.springframework.context.ApplicationContext}
 * interface. Doesn't mandate the type of storage used for configuration; simply
 * implements common context functionality. Uses the Template Method design pattern,
 * requiring concrete subclasses to implement abstract methods.
 *
 * <p>In contrast to a plain BeanFactory, an ApplicationContext is supposed
 * to detect special beans defined in its internal bean factory:
 * Therefore, this class automatically registers
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor BeanFactoryPostProcessors},
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessors},
 * and {@link org.springframework.context.ApplicationListener ApplicationListeners}
 * which are defined as beans in the context.
 *
 * <p>A {@link org.springframework.context.MessageSource} may also be supplied
 * as a bean in the context, with the name "messageSource"; otherwise, message
 * resolution is delegated to the parent context. Furthermore, a multicaster
 * for application events can be supplied as an "applicationEventMulticaster" bean
 * of type {@link org.springframework.context.event.ApplicationEventMulticaster}
 * in the context; otherwise, a default multicaster of type
 * {@link org.springframework.context.event.SimpleApplicationEventMulticaster} will be used.
 *
 * <p>Implements resource loading by extending
 * {@link org.springframework.core.io.DefaultResourceLoader}.
 * Consequently treats non-URL resource paths as class path resources
 * (supporting full class path resource names that include the package path,
 * for example, "mypackage/myresource.dat"), unless the {@link #getResourceByPath}
 * method is overridden in a subclass.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @author Sebastien Deleuze
 * @author Brian Clozel
 * @since January 21, 2001
 * @see #refreshBeanFactory
 * @see #getBeanFactory
 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor
 * @see org.springframework.beans.factory.config.BeanPostProcessor
 * @see org.springframework.context.event.ApplicationEventMulticaster
 * @see org.springframework.context.ApplicationListener
 * @see org.springframework.context.MessageSource
 */
public abstract class AbstractApplicationContext extends DefaultResourceLoader
		implements ConfigurableApplicationContext {

	/**
	 * The name of the {@link MessageSource} bean in the context.
	 * If none is supplied, message resolution is delegated to the parent.
	 * @see org.springframework.context.MessageSource
	 * @see org.springframework.context.support.ResourceBundleMessageSource
	 * @see org.springframework.context.support.ReloadableResourceBundleMessageSource
	 * @see #getMessage(MessageSourceResolvable, Locale)
	 */
	public static final String MESSAGE_SOURCE_BEAN_NAME = "messageSource";

	/**
	 * The name of the {@link ApplicationEventMulticaster} bean in the context.
	 * If none is supplied, a {@link SimpleApplicationEventMulticaster} is used.
	 * @see org.springframework.context.event.ApplicationEventMulticaster
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 * @see #publishEvent(ApplicationEvent)
	 * @see #addApplicationListener(ApplicationListener)
	 */
	public static final String APPLICATION_EVENT_MULTICASTER_BEAN_NAME = "applicationEventMulticaster";

	/**
	 * The name of the {@link LifecycleProcessor} bean in the context.
	 * If none is supplied, a {@link DefaultLifecycleProcessor} is used.
	 * @since 3.0
	 * @see org.springframework.context.LifecycleProcessor
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 * @see #start()
	 * @see #stop()
	 */
	public static final String LIFECYCLE_PROCESSOR_BEAN_NAME = "lifecycleProcessor";


	static {
		// Eagerly load the ContextClosedEvent class to avoid weird classloader issues
		// on application shutdown in WebLogic 8.1. (Reported by Dustin Woods.)
		ContextClosedEvent.class.getName();
	}


	/** Logger used by this class. Available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Unique id for this context, if any. */
	private String id = ObjectUtils.identityToString(this);

	/** Display name. */
	private String displayName = ObjectUtils.identityToString(this);

	/** Parent context. */
	private @Nullable ApplicationContext parent;

	/** Environment used by this context. */
	private @Nullable ConfigurableEnvironment environment;

	/** BeanFactoryPostProcessors to apply on refresh. */
	private final List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new ArrayList<>();

	/** System time in milliseconds when this context started. */
	private long startupDate;

	/** Flag that indicates whether this context is currently active. */
	private final AtomicBoolean active = new AtomicBoolean();

	/** Flag that indicates whether this context has been closed already. */
	private final AtomicBoolean closed = new AtomicBoolean();

	/** Synchronization lock for "refresh" and "close". */
	private final Lock startupShutdownLock = new ReentrantLock();

	/** Currently active startup/shutdown thread. */
	private volatile @Nullable Thread startupShutdownThread;

	/** Reference to the JVM shutdown hook, if registered. */
	private @Nullable Thread shutdownHook;

	/** ResourcePatternResolver used by this context. */
	private final ResourcePatternResolver resourcePatternResolver;

	/** LifecycleProcessor for managing the lifecycle of beans within this context. */
	private @Nullable LifecycleProcessor lifecycleProcessor;

	/** MessageSource we delegate our implementation of this interface to. */
	private @Nullable MessageSource messageSource;

	/** Helper class used in event publishing. */
	private @Nullable ApplicationEventMulticaster applicationEventMulticaster;

	/** Application startup metrics. */
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;

	/** Statically specified listeners. */
	private final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();

	/** Local listeners registered before refresh. */
	private @Nullable Set<ApplicationListener<?>> earlyApplicationListeners;

	/** ApplicationEvents published before the multicaster setup. */
	private @Nullable Set<ApplicationEvent> earlyApplicationEvents;


	/**
	 * Create a new AbstractApplicationContext with no parent.
	 */
	public AbstractApplicationContext() {
		this.resourcePatternResolver = getResourcePatternResolver();
	}

	/**
	 * Create a new AbstractApplicationContext with the given parent context.
	 * @param parent the parent context
	 */
	public AbstractApplicationContext(@Nullable ApplicationContext parent) {
		this();
		setParent(parent);
	}


	//---------------------------------------------------------------------
	// Implementation of ApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the unique id of this application context.
	 * <p>Default is the object id of the context instance, or the name
	 * of the context bean if the context is itself defined as a bean.
	 * @param id the unique id of the context
	 */
	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public String getApplicationName() {
		return "";
	}

	/**
	 * Set a friendly name for this context.
	 * Typically done during initialization of concrete context implementations.
	 * <p>Default is the object id of the context instance.
	 */
	public void setDisplayName(String displayName) {
		Assert.hasLength(displayName, "Display name must not be empty");
		this.displayName = displayName;
	}

	/**
	 * Return a friendly name for this context.
	 * @return a display name for this context (never {@code null})
	 */
	@Override
	public String getDisplayName() {
		return this.displayName;
	}

	/**
	 * Return the parent context, or {@code null} if there is no parent
	 * (that is, this context is the root of the context hierarchy).
	 */
	@Override
	public @Nullable ApplicationContext getParent() {
		return this.parent;
	}

	/**
	 * Set the {@code Environment} for this application context.
	 * <p>Default value is determined by {@link #createEnvironment()}. Replacing the
	 * default with this method is one option but configuration through {@link
	 * #getEnvironment()} should also be considered. In either case, such modifications
	 * should be performed <em>before</em> {@link #refresh()}.
	 * @see org.springframework.context.support.AbstractApplicationContext#createEnvironment
	 */
	@Override
	public void setEnvironment(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	/**
	 * Return the {@code Environment} for this application context in configurable
	 * form, allowing for further customization.
	 * <p>If none specified, a default environment will be initialized via
	 * {@link #createEnvironment()}.
	 */
	@Override
	public ConfigurableEnvironment getEnvironment() {
		if (this.environment == null) {
			this.environment = createEnvironment();
		}
		return this.environment;
	}

	/**
	 * Create and return a new {@link StandardEnvironment}.
	 * <p>Subclasses may override this method in order to supply
	 * a custom {@link ConfigurableEnvironment} implementation.
	 */
	protected ConfigurableEnvironment createEnvironment() {
		return new StandardEnvironment();
	}

	/**
	 * Return this context's internal bean factory as AutowireCapableBeanFactory,
	 * if already available.
	 * @see #getBeanFactory()
	 */
	@Override
	public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
		return getBeanFactory();
	}

	/**
	 * Return the timestamp (ms) when this context was first loaded.
	 */
	@Override
	public long getStartupDate() {
		return this.startupDate;
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be application-specific or a
	 * standard framework event)
	 */
	@Override
	public void publishEvent(ApplicationEvent event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>Note: Listeners get initialized after the MessageSource, to be able
	 * to access it within listener implementations. Thus, MessageSource
	 * implementations cannot publish events.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 */
	@Override
	public void publishEvent(Object event) {
		publishEvent(event, null);
	}

	/**
	 * Publish the given event to all listeners.
	 * <p>This is the internal delegate that all other {@code publishEvent}
	 * methods refer to. It is not meant to be called directly but rather serves
	 * as a propagation mechanism between application contexts in a hierarchy,
	 * potentially overridden in subclasses for a custom propagation arrangement.
	 * @param event the event to publish (may be an {@link ApplicationEvent}
	 * or a payload object to be turned into a {@link PayloadApplicationEvent})
	 * @param typeHint the resolved event type, if known.
	 * The implementation of this method also tolerates a payload type hint for
	 * a payload object to be turned into a {@link PayloadApplicationEvent}.
	 * However, the recommended way is to construct an actual event object via
	 * {@link PayloadApplicationEvent#PayloadApplicationEvent(Object, Object, ResolvableType)}
	 * instead for such scenarios.
	 * @since 4.2
	 * @see ApplicationEventMulticaster#multicastEvent(ApplicationEvent, ResolvableType)
	 */
	protected void publishEvent(Object event, @Nullable ResolvableType typeHint) {
		Assert.notNull(event, "Event must not be null");
		ResolvableType eventType = null;

		// Decorate event as an ApplicationEvent if necessary
		ApplicationEvent applicationEvent;
		if (event instanceof ApplicationEvent applEvent) {
			applicationEvent = applEvent;
			eventType = typeHint;
		}
		else {
			ResolvableType payloadType = null;
			if (typeHint != null && ApplicationEvent.class.isAssignableFrom(typeHint.toClass())) {
				eventType = typeHint;
			}
			else {
				payloadType = typeHint;
			}
			applicationEvent = new PayloadApplicationEvent<>(this, event, payloadType);
		}

		// Determine event type only once (for multicast and parent publish)
		if (eventType == null) {
			eventType = ResolvableType.forInstance(applicationEvent);
			if (typeHint == null) {
				typeHint = eventType;
			}
		}

		// Multicast right now if possible - or lazily once the multicaster is initialized
		if (this.earlyApplicationEvents != null) {
			this.earlyApplicationEvents.add(applicationEvent);
		}
		else if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.multicastEvent(applicationEvent, eventType);
		}

		// Publish event via parent context as well...
		if (this.parent != null) {
			if (this.parent instanceof AbstractApplicationContext abstractApplicationContext) {
				abstractApplicationContext.publishEvent(event, typeHint);
			}
			else {
				this.parent.publishEvent(event);
			}
		}
	}

	/**
	 * Return the internal ApplicationEventMulticaster used by the context.
	 * @return the internal ApplicationEventMulticaster (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	ApplicationEventMulticaster getApplicationEventMulticaster() throws IllegalStateException {
		if (this.applicationEventMulticaster == null) {
			throw new IllegalStateException("ApplicationEventMulticaster not initialized - " +
					"call 'refresh' before multicasting events via the context: " + this);
		}
		return this.applicationEventMulticaster;
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		Assert.notNull(applicationStartup, "ApplicationStartup must not be null");
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	/**
	 * Return the internal LifecycleProcessor used by the context.
	 * @return the internal LifecycleProcessor (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	LifecycleProcessor getLifecycleProcessor() throws IllegalStateException {
		if (this.lifecycleProcessor == null) {
			throw new IllegalStateException("LifecycleProcessor not initialized - " +
					"call 'refresh' before invoking lifecycle methods via the context: " + this);
		}
		return this.lifecycleProcessor;
	}

	/**
	 * Return the ResourcePatternResolver to use for resolving location patterns
	 * into Resource instances. Default is a
	 * {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver},
	 * supporting Ant-style location patterns.
	 * <p>Can be overridden in subclasses, for extended resolution strategies,
	 * for example in a web environment.
	 * <p><b>Do not call this when needing to resolve a location pattern.</b>
	 * Call the context's {@code getResources} method instead, which
	 * will delegate to the ResourcePatternResolver.
	 * @return the ResourcePatternResolver for this context
	 * @see #getResources
	 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
	 */
	protected ResourcePatternResolver getResourcePatternResolver() {
		return new PathMatchingResourcePatternResolver(this);
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the parent of this application context.
	 * <p>The parent {@linkplain ApplicationContext#getEnvironment() environment} is
	 * {@linkplain ConfigurableEnvironment#merge(ConfigurableEnvironment) merged} with
	 * this (child) application context environment if the parent is non-{@code null} and
	 * its environment is an instance of {@link ConfigurableEnvironment}.
	 * @see ConfigurableEnvironment#merge(ConfigurableEnvironment)
	 */
	@Override
	public void setParent(@Nullable ApplicationContext parent) {
		this.parent = parent;
		if (parent != null) {
			Environment parentEnvironment = parent.getEnvironment();
			if (parentEnvironment instanceof ConfigurableEnvironment configurableEnvironment) {
				getEnvironment().merge(configurableEnvironment);
			}
		}
	}

	@Override
	public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
		Assert.notNull(postProcessor, "BeanFactoryPostProcessor must not be null");
		this.beanFactoryPostProcessors.add(postProcessor);
	}

	/**
	 * Return the list of BeanFactoryPostProcessors that will get applied
	 * to the internal BeanFactory.
	 */
	public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
		return this.beanFactoryPostProcessors;
	}

	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		Assert.notNull(listener, "ApplicationListener must not be null");
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.addApplicationListener(listener);
		}
		this.applicationListeners.add(listener);
	}

	@Override
	public void removeApplicationListener(ApplicationListener<?> listener) {
		Assert.notNull(listener, "ApplicationListener must not be null");
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.removeApplicationListener(listener);
		}
		this.applicationListeners.remove(listener);
	}

	/**
	 * Return the list of statically specified ApplicationListeners.
	 */
	public Collection<ApplicationListener<?>> getApplicationListeners() {
		return this.applicationListeners;
	}

	@Override
	public void refresh() throws BeansException, IllegalStateException {
		this.startupShutdownLock.lock();
		try {
			this.startupShutdownThread = Thread.currentThread();

			StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");

			// Prepare this context for refreshing.
			// 准备上下文以进行刷新。
			prepareRefresh();

			// Tell the subclass to refresh the internal bean factory.
			// 告诉子类刷新内部bean工厂。
			ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

			// Prepare the bean factory for use in this context.
			// 为在此上下文中使用准备bean工厂。
			prepareBeanFactory(beanFactory);

			try {
				// Allows post-processing of the bean factory in context subclasses.
				// 允许在上下文子类中对bean工厂进行后处理。
				postProcessBeanFactory(beanFactory);

				StartupStep beanPostProcess = this.applicationStartup.start("spring.context.beans.post-process");
				// Invoke factory processors registered as beans  the context.
				// 调用在上下文中注册为bean的工厂处理器。
				invokeBeanFactoryPostProcessors(beanFactory);
				// Register bean processors that intercept bean creation.
				// 注册拦截bean创建的bean处理器。
				registerBeanPostProcessors(beanFactory);
				beanPostProcess.end();

				// Initialize message source for this context.
				// 为此上下文初始化消息源。
				initMessageSource();

				// Initialize event multicaster for this context.
				// 为此上下文初始化事件广播器。
				initApplicationEventMulticaster();

				// Initialize other special beans in specific context subclasses.
				// 在特定的上下文子类中初始化其他特殊bean。
				onRefresh();

				// Check for listener beans and register them.
				// 检查监听器bean并注册它们。
				registerListeners();

				// Instantiate all remaining (non-lazy-init) singletons.
				// 实例化所有剩余的（非延迟初始化）单例。
				finishBeanFactoryInitialization(beanFactory);

				// Last step: publish corresponding event.
				// 最后一步：发布相应的事件。
				finishRefresh();
			}

			catch (RuntimeException | Error ex ) {
				if (logger.isWarnEnabled()) {
					logger.warn("Exception encountered during context initialization - " +
							"cancelling refresh attempt: " + ex);
				}

				// Destroy already created singletons to avoid dangling resources.
				// 销毁已经创建的单例以避免悬空资源。
				destroyBeans();

				// Reset 'active' flag.
				// 重置'active'标志。
				cancelRefresh(ex);

				// Propagate exception to caller.
				// 将异常传播给调用者。
				throw ex;
			}

			finally {
				contextRefresh.end();
			}
		}
		finally {
			this.startupShutdownThread = null;
			this.startupShutdownLock.unlock();
		}
	}

	/**
	 * Prepare this context for refreshing, setting its startup date and
	 * active flag as well as performing any initialization of property sources.
	 * 准备此上下文以进行刷新，设置其启动日期和活动标志，以及执行属性源的任何初始化。
	 */
	protected void prepareRefresh() {
		// Switch to active.
		// 切换到活动状态。
		this.startupDate = System.currentTimeMillis(); /* 附加注释：记录容器启动时间，用于性能分析和监控 */
		this.closed.set(false); /* 附加注释：标记容器未关闭，允许后续操作执行 */
		this.active.set(true); /* 附加注释：标记容器为活动状态，表示容器可以接收请求和处理业务 */

		/* 附加注释：根据日志级别选择不同的日志输出方式，提高日志的可读性和性能 */
		if (logger.isDebugEnabled()) {
			if (logger.isTraceEnabled()) {
				logger.trace("Refreshing " + this);
			}
			else {
				logger.debug("Refreshing " + getDisplayName());
			}
		}

		// Initialize any placeholder property sources in the context environment.
		// 初始化上下文环境中的任何占位符属性源。
		initPropertySources(); /* 附加注释：初始化属性源，允许子类覆盖此方法来自定义属性源，如Web环境中的ServletContext参数 */

		// Validate that all properties marked as required are resolvable:
		// see ConfigurablePropertyResolver#setRequiredProperties
		// 验证所有标记为必需的属性都是可解析的：
		// 参见 ConfigurablePropertyResolver#setRequiredProperties
		getEnvironment().validateRequiredProperties(); /* 附加注释：验证必需属性是否存在，如果缺少则会抛出异常，确保应用程序配置完整 */

		// Store pre-refresh ApplicationListeners...
		// 存储刷新前的应用程序监听器...
		/* 附加注释：这段代码处理ApplicationListener的初始化，确保在容器刷新过程中能正确处理事件 */
		if (this.earlyApplicationListeners == null) {
			/* 附加注释：首次刷新时，保存原始监听器集合，用于后续可能的重新刷新操作 */
			this.earlyApplicationListeners = new LinkedHashSet<>(this.applicationListeners);
		}
		else {
			// Reset local application listeners to pre-refresh state.
			// 将本地应用程序监听器重置为刷新前的状态。
			/* 附加注释：重新刷新时，恢复到初始状态，避免重复注册监听器导致的内存泄漏或重复处理事件 */
			this.applicationListeners.clear();
			this.applicationListeners.addAll(this.earlyApplicationListeners);
		}

		// Allow for the collection of early ApplicationEvents,
		// to be published once the multicaster is available...
		// 允许收集早期的应用程序事件，
		// 一旦多播器可用就发布...
		this.earlyApplicationEvents = new LinkedHashSet<>(); /* 附加注释：创建一个集合来存储早期事件，因为此时事件广播器尚未初始化，这些事件将在initApplicationEventMulticaster方法执行后发布 */
	}

	/**
	 * <p>Replace any stub property sources with actual instances.
	 * @see org.springframework.core.env.PropertySource.StubPropertySource
	 * @see org.springframework.web.context.support.WebApplicationContextUtils#initServletPropertySources
	 */
	protected void initPropertySources() {
		// For subclasses: do nothing by default.
	}

	/**
	 * Tell the subclass to refresh the internal bean factory.
	 * @return the fresh BeanFactory instance
	 * @see #refreshBeanFactory()
	 * @see #getBeanFactory()
	 */
	protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
		refreshBeanFactory();
		return getBeanFactory();
	}

	/**
	 * Configure the factory's standard context characteristics,
	 * such as the context's ClassLoader and post-processors.
	 * 配置工厂的标准上下文特性，例如上下文的类加载器和后处理器。
	 * @param beanFactory the BeanFactory to configure
	 * @param beanFactory 要配置的BeanFactory
	 */
	protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// Tell the internal bean factory to use the context's class loader etc.
		// 告诉内部bean工厂使用上下文的类加载器等。
		beanFactory.setBeanClassLoader(getClassLoader()); /* 附加注释：设置类加载器，确保Bean的加载使用正确的类加载器，特别是在OSGi等模块化环境中至关重要 */
		beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver(beanFactory.getBeanClassLoader())); /* 附加注释：设置表达式解析器，用于解析Bean定义中的SpEL表达式，如@Value注解中的表达式 */
		beanFactory.addPropertyEditorRegistrar(new ResourceEditorRegistrar(this, getEnvironment())); /* 附加注释：注册属性编辑器，用于在Bean属性注入时进行类型转换，如将String转换为Resource对象 */

		// Configure the bean factory with context callbacks.
		// 使用上下文回调配置bean工厂。
		beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this)); /* 附加注释：添加处理器使Bean能感知ApplicationContext，自动注入ApplicationContext相关的依赖 */
		/* 附加注释：以下代码块用于忽略特定Aware接口的自动装配，因为这些接口已经在上面一行由ApplicationContextAwareProcessor处理了，避免重复注入 */
		beanFactory.ignoreDependencyInterface(EnvironmentAware.class);
		beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
		beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
		beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationStartupAware.class);

		// BeanFactory interface not registered as resolvable type in a plain factory.
		// MessageSource registered (and found for autowiring) as a bean.
		// BeanFactory接口在普通工厂中未注册为可解析类型。
		// MessageSource作为bean注册（并用于自动装配）。
		/* 附加注释：以下代码块注册可解析依赖，使这些接口可以被客户应用使用@Autowired注解自动注入，而不需要通过Aware接口 */
		beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory); /* 附加注释：允许直接注入BeanFactory，方便访问容器 */
		beanFactory.registerResolvableDependency(ResourceLoader.class, this); /* 附加注释：允许直接注入ResourceLoader，用于资源加载 */
		beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this); /* 附加注释：允许直接注入事件发布器，用于发布应用事件 */
		beanFactory.registerResolvableDependency(ApplicationContext.class, this); /* 附加注释：允许直接注入ApplicationContext，获取完整上下文功能 */

		// Register early post-processor for detecting inner beans as ApplicationListeners.
		// 注册早期后处理器，用于检测内部bean作为ApplicationListeners。
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(this)); /* 附加注释：添加监听器检测器，自动将实现ApplicationListener接口的Bean注册为事件监听器 */

		// Detect a LoadTimeWeaver and prepare for weaving, if found.
		// 如果找到LoadTimeWeaver，则检测并准备织入。
		/* 附加注释：以下分支处理AOP加载时织入的场景，仅在非原生镜像模式下且存在LoadTimeWeaver时执行 */
		if (!NativeDetector.inNativeImage() && beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory)); /* 附加注释：添加处理器支持LoadTimeWeaver感知，用于AspectJ加载时织入 */
			// Set a temporary ClassLoader for type matching.
			// 为类型匹配设置临时ClassLoader。
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader())); /* 附加注释：设置临时类加载器，用于类型匹配而不触发类的完全加载，提高性能并避免早期类加载问题 */
		}

		// Register default environment beans.
		// 注册默认环境bean。
		/* 附加注释：以下代码块注册环境相关的单例Bean，使它们可以被依赖注入和通过名称查找 */
		if (!beanFactory.containsLocalBean(ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(ENVIRONMENT_BEAN_NAME, getEnvironment()); /* 附加注释：注册Environment单例，用于访问配置属性和profiles */
		}
		if (!beanFactory.containsLocalBean(SYSTEM_PROPERTIES_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_PROPERTIES_BEAN_NAME, getEnvironment().getSystemProperties()); /* 附加注释：注册系统属性单例，方便直接访问JVM系统属性 */
		}
		if (!beanFactory.containsLocalBean(SYSTEM_ENVIRONMENT_BEAN_NAME)) {
			beanFactory.registerSingleton(SYSTEM_ENVIRONMENT_BEAN_NAME, getEnvironment().getSystemEnvironment()); /* 附加注释：注册系统环境变量单例，方便直接访问操作系统环境变量 */
		}
		if (!beanFactory.containsLocalBean(APPLICATION_STARTUP_BEAN_NAME)) {
			beanFactory.registerSingleton(APPLICATION_STARTUP_BEAN_NAME, getApplicationStartup()); /* 附加注释：注册应用启动指标收集器，用于性能分析和启动过程监控 */
		}
	}

	/**
	 * Modify the application context's internal bean factory after its standard
	 * initialization. The initial definition resources will have been loaded but no
	 * post-processors will have run and no derived bean definitions will have been
	 * registered, and most importantly, no beans will have been instantiated yet.
	 * <p>This template method allows for registering special BeanPostProcessors
	 * etc in certain AbstractApplicationContext subclasses.
	 * @param beanFactory the bean factory used by the application context
	 */
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
	}

	/**
	 * Instantiate and invoke all registered BeanFactoryPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before singleton instantiation.
	 *
	 * 实例化并调用所有已注册的BeanFactoryPostProcessor beans，
	 * 如果给定了显式顺序，则遵循该顺序。
	 * <p>必须在单例实例化之前调用。
	 */
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors()); // 委托给PostProcessorRegistrationDelegate处理所有BeanFactoryPostProcessor的调用

		// Detect a LoadTimeWeaver and prepare for weaving, if found in the meantime
		// (for example, through an @Bean method registered by ConfigurationClassPostProcessor)
		// 检测LoadTimeWeaver并准备织入，如果在此期间发现了LoadTimeWeaver
		// (例如，通过ConfigurationClassPostProcessor注册的@Bean方法)
		if (!NativeDetector.inNativeImage() && beanFactory.getTempClassLoader() == null &&
				beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory)); // 添加LoadTimeWeaverAwareProcessor以支持LoadTimeWeaver感知
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader())); // 设置临时类加载器用于类型匹配
		}
	}

	/**
	 * Instantiate and register all BeanPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before any instantiation of application beans.
	 *
	 * 实例化并注册所有BeanPostProcessor beans，
	 * 如果给定了显式顺序，则遵循该顺序。
	 * <p>必须在应用程序bean的任何实例化之前调用。
	 */
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		PostProcessorRegistrationDelegate.registerBeanPostProcessors(beanFactory, this); /* 附加注释：委托给PostProcessorRegistrationDelegate处理所有BeanPostProcessor的注册，这种委托模式使核心逻辑与ApplicationContext分离，便于维护和测试，同时确保BeanPostProcessor按正确顺序注册，包括优先级排序和内部/外部处理器区分 */
	}

	/**
	 * Initialize the {@link MessageSource}.
	 * <p>Uses parent's {@code MessageSource} if none defined in this context.
	 * @see #MESSAGE_SOURCE_BEAN_NAME
	 *
	 * 初始化{@link MessageSource}。
	 * <p>如果此上下文中未定义，则使用父上下文的{@code MessageSource}。
	 * @see #MESSAGE_SOURCE_BEAN_NAME
	 */
	protected void initMessageSource() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory(); /* 附加注释：获取当前上下文的bean工厂，用于后续检查和注册MessageSource */
		if (beanFactory.containsLocalBean(MESSAGE_SOURCE_BEAN_NAME)) {
			/* 附加注释：如果用户已定义了自己的MessageSource bean，则使用用户定义的实现，这支持自定义国际化消息处理 */
			this.messageSource = beanFactory.getBean(MESSAGE_SOURCE_BEAN_NAME, MessageSource.class); /* 附加注释：从容器中获取用户定义的MessageSource实例 */
			// Make MessageSource aware of parent MessageSource.
			// 使MessageSource感知父MessageSource。
			if (this.parent != null && this.messageSource instanceof HierarchicalMessageSource hms &&
					hms.getParentMessageSource() == null) {
				/* 附加注释：处理层次化消息源场景，确保子上下文的MessageSource能够在本地找不到消息时委托给父上下文的MessageSource */
				// Only set parent context as parent MessageSource if no parent MessageSource
				// registered already.
				// 仅当尚未注册父MessageSource时，才将父上下文设置为父MessageSource。
				hms.setParentMessageSource(getInternalParentMessageSource()); /* 附加注释：设置父消息源，建立消息解析的层次结构，支持消息查找的层级委托机制 */
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Using MessageSource [" + this.messageSource + "]");
			}
		}
		else {
			/* 附加注释：当用户没有定义MessageSource时，创建一个默认的委托实现，确保应用始终有可用的MessageSource */
			// Use empty MessageSource to be able to accept getMessage calls.
			// 使用空MessageSource以能够接受getMessage调用。
			DelegatingMessageSource dms = new DelegatingMessageSource(); /* 附加注释：创建委托式消息源，它本身不包含消息，但可以委托给父消息源 */
			dms.setParentMessageSource(getInternalParentMessageSource()); /* 附加注释：设置父消息源，确保即使是默认MessageSource也能利用父上下文的消息资源 */
			this.messageSource = dms; /* 附加注释：将委托消息源设置为当前上下文的消息源 */
			beanFactory.registerSingleton(MESSAGE_SOURCE_BEAN_NAME, this.messageSource); /* 附加注释：将默认消息源注册为单例，使其可以被依赖注入和通过名称查找 */
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + MESSAGE_SOURCE_BEAN_NAME + "' bean, using [" + this.messageSource + "]");
			}
		}
	}

	/**
	 * Initialize the {@link ApplicationEventMulticaster}.
	 * <p>Uses {@link SimpleApplicationEventMulticaster} if none defined in the context.
	 * @see #APPLICATION_EVENT_MULTICASTER_BEAN_NAME
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 *
	 * 初始化{@link ApplicationEventMulticaster}。
	 * <p>如果上下文中未定义，则使用{@link SimpleApplicationEventMulticaster}。
	 * @see #APPLICATION_EVENT_MULTICASTER_BEAN_NAME
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 */
	protected void initApplicationEventMulticaster() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory(); /* 附加注释：获取当前上下文的bean工厂，用于检查和注册ApplicationEventMulticaster，这是事件发布的核心组件 */
		if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
			/* 附加注释：检查用户是否已定义了自己的事件广播器，如果有则使用用户定义的实现，这支持自定义事件发布策略，如异步事件处理 */
			this.applicationEventMulticaster =
					beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class); /* 附加注释：从容器中获取用户定义的事件广播器实例，支持用户自定义事件分发机制 */
			if (logger.isTraceEnabled()) {
				logger.trace("Using ApplicationEventMulticaster [" + this.applicationEventMulticaster + "]");
			}
		}
		else {
			/* 附加注释：当用户没有定义事件广播器时，创建一个默认的SimpleApplicationEventMulticaster实现，确保应用始终有可用的事件发布机制 */
			this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory); /* 附加注释：创建默认的事件广播器，并传入beanFactory以便它能够解析监听器名称，支持基本的同步事件发布功能 */
			beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster); /* 附加注释：将默认事件广播器注册为单例，使其可以被依赖注入和通过名称查找，确保整个应用上下文共享同一个事件分发器 */
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + APPLICATION_EVENT_MULTICASTER_BEAN_NAME + "' bean, using " +
						"[" + this.applicationEventMulticaster.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Initialize the {@link LifecycleProcessor}.
	 * <p>Uses {@link DefaultLifecycleProcessor} if none defined in the context.
	 * @since 3.0
	 * @see #LIFECYCLE_PROCESSOR_BEAN_NAME
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 */
	protected void initLifecycleProcessor() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(LIFECYCLE_PROCESSOR_BEAN_NAME)) {
			this.lifecycleProcessor = beanFactory.getBean(LIFECYCLE_PROCESSOR_BEAN_NAME, LifecycleProcessor.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using LifecycleProcessor [" + this.lifecycleProcessor + "]");
			}
		}
		else {
			DefaultLifecycleProcessor defaultProcessor = new DefaultLifecycleProcessor();
			defaultProcessor.setBeanFactory(beanFactory);
			this.lifecycleProcessor = defaultProcessor;
			beanFactory.registerSingleton(LIFECYCLE_PROCESSOR_BEAN_NAME, this.lifecycleProcessor);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + LIFECYCLE_PROCESSOR_BEAN_NAME + "' bean, using " +
						"[" + this.lifecycleProcessor.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Template method which can be overridden to add context-specific refresh work.
	 * Called on initialization of special beans, before instantiation of singletons.
	 * <p>This implementation is empty.
	 * @throws BeansException in case of errors
	 * @see #refresh()
	 */
	protected void onRefresh() throws BeansException {
		// For subclasses: do nothing by default.
	}
	/**
	 * Add beans that implement ApplicationListener as listeners.
	 * Doesn't affect other listeners, which can be added without being beans.
	 *
	 * 添加实现ApplicationListener的bean作为监听器。
	 * 不影响其他监听器，这些监听器可以在不作为bean的情况下添加。
	 */
	protected void registerListeners() {
		// Register statically specified listeners first.
		// 首先注册静态指定的监听器。
		for (ApplicationListener<?> listener : getApplicationListeners()) {
			/* 附加注释：将编程方式添加的监听器注册到事件广播器，这些监听器通常是通过ApplicationContext.addApplicationListener()方法添加的，优先级高于配置的bean监听器 */
			getApplicationEventMulticaster().addApplicationListener(listener);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let post-processors apply to them!
		// 不要在这里初始化FactoryBeans：我们需要让所有常规bean保持未初始化状态，以便让后处理器应用于它们！
		String[] listenerBeanNames = getBeanNamesForType(ApplicationListener.class, true, false); /* 附加注释：获取所有类型为ApplicationListener的bean名称，includeNonSingletons=true表示包含非单例bean，allowEagerInit=false表示不初始化懒加载的bean和FactoryBean */
		for (String listenerBeanName : listenerBeanNames) {
			/* 附加注释：将配置的监听器bean名称添加到事件广播器，这种方式不会立即初始化bean，而是在事件发布时才按需初始化，避免了过早初始化可能带来的问题 */
			getApplicationEventMulticaster().addApplicationListenerBean(listenerBeanName);
		}

		// Publish early application events now that we finally have a multicaster...
		// 现在我们终于有了一个广播器，发布早期应用事件...
		Set<ApplicationEvent> earlyEventsToProcess = this.earlyApplicationEvents; /* 附加注释：获取在ApplicationEventMulticaster初始化之前产生的早期事件集合，这些事件被临时存储等待处理 */
		this.earlyApplicationEvents = null; /* 附加注释：清空早期事件集合引用，表明这些事件将被处理，不再需要缓存，同时防止这些事件被重复处理 */
		if (!CollectionUtils.isEmpty(earlyEventsToProcess)) {
			/* 附加注释：当存在早期事件时，通过现在已初始化的事件广播器发布这些事件，确保即使在上下文初始化早期阶段产生的事件也能被正确处理 */
			for (ApplicationEvent earlyEvent : earlyEventsToProcess) {
				getApplicationEventMulticaster().multicastEvent(earlyEvent); /* 附加注释：广播每个早期事件，使所有已注册的监听器能够接收并处理这些事件，完成事件的延迟处理机制 */
			}
		}
	}

	/**
	 * Finish the initialization of this context's bean factory,
	 * initializing all remaining singleton beans.
	 *
	 * 完成此上下文的bean工厂的初始化，
	 * 初始化所有剩余的单例bean。
	 */
	@SuppressWarnings("unchecked")
	protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
		// Initialize bootstrap executor for this context.
		// 为此上下文初始化引导执行器。
		if (beanFactory.containsBean(BOOTSTRAP_EXECUTOR_BEAN_NAME) &&
				beanFactory.isTypeMatch(BOOTSTRAP_EXECUTOR_BEAN_NAME, Executor.class)) {
			/* 附加注释：当用户定义了名为"bootstrapExecutor"的执行器Bean时，将其设置为BeanFactory的引导执行器，用于支持Bean初始化过程中的异步操作，提高启动性能 */
			beanFactory.setBootstrapExecutor(
					beanFactory.getBean(BOOTSTRAP_EXECUTOR_BEAN_NAME, Executor.class)); /* 附加注释：获取并设置用户自定义的引导执行器，使Spring能够利用并发能力加速Bean的初始化过程 */
		}

		// Initialize conversion service for this context.
		// 为此上下文初始化转换服务。
		if (beanFactory.containsBean(CONVERSION_SERVICE_BEAN_NAME) &&
				beanFactory.isTypeMatch(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class)) {
			/* 附加注释：检查并使用用户自定义的类型转换服务，该服务负责在不同类型之间进行转换，如String到Date、String到Number等，支持属性注入和SpEL表达式求值时的类型转换 */
			beanFactory.setConversionService(
					beanFactory.getBean(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class)); /* 附加注释：获取并设置用户定义的转换服务，使其在整个容器中生效，影响所有需要类型转换的场景，如@Value注解中的值转换 */
		}

		// Register a default embedded value resolver if no BeanFactoryPostProcessor
		// (such as a PropertySourcesPlaceholderConfigurer bean) registered any before:
		// at this point, primarily for resolution in annotation attribute values.
		// 如果之前没有BeanFactoryPostProcessor（如PropertySourcesPlaceholderConfigurer bean）
		// 注册过嵌入值解析器，则注册一个默认的嵌入值解析器：
		// 此时，主要用于解析注解属性值。
		if (!beanFactory.hasEmbeddedValueResolver()) {
			/* 附加注释：当没有其他组件注册过占位符解析器时，提供一个默认实现，确保注解中的${...}占位符能被正确解析，如@Value("${property}")中的占位符 */
			beanFactory.addEmbeddedValueResolver(strVal -> getEnvironment().resolvePlaceholders(strVal)); /* 附加注释：添加一个基于当前环境的占位符解析器，将字符串中的${...}替换为环境属性中的实际值，支持配置外部化和环境感知 */
		}

		// Call BeanFactoryInitializer beans early to allow for initializing specific other beans early.
		// 提前调用BeanFactoryInitializer beans，以便允许提前初始化特定的其他bean。
		String[] initializerNames = beanFactory.getBeanNamesForType(BeanFactoryInitializer.class, false, false); /* 附加注释：获取所有BeanFactoryInitializer类型的Bean名称，不包括非单例和FactoryBean产生的Bean，用于提前初始化特定Bean */
		for (String initializerName : initializerNames) {
			/* 附加注释：循环调用每个初始化器的initialize方法，允许在常规单例初始化之前执行特定的初始化逻辑，适用于需要提前准备的基础设施Bean */
			beanFactory.getBean(initializerName, BeanFactoryInitializer.class).initialize(beanFactory); /* 附加注释：获取并执行初始化器，使其能够在标准Bean初始化流程前修改BeanFactory或初始化关键组件，为后续Bean创建提供必要的基础设施 */
		}

		// Initialize LoadTimeWeaverAware beans early to allow for registering their transformers early.
		// 提前初始化LoadTimeWeaverAware beans，以便允许提前注册它们的转换器。
		String[] weaverAwareNames = beanFactory.getBeanNamesForType(LoadTimeWeaverAware.class, false, false); /* 附加注释：获取所有实现了LoadTimeWeaverAware接口的Bean名称，这些Bean需要在类加载时进行字节码增强，如AspectJ LTW */
		for (String weaverAwareName : weaverAwareNames) {
			/* 附加注释：提前初始化与AspectJ加载时织入相关的Bean，确保字节码转换器在任何类被加载前就准备就绪，支持面向切面编程的加载时织入功能 */
			try {
				beanFactory.getBean(weaverAwareName, LoadTimeWeaverAware.class); /* 附加注释：初始化织入感知Bean，触发其setLoadTimeWeaver方法，使其能够注册自定义的类转换器，实现如事务、安全等横切关注点的织入 */
			}
			catch (BeanNotOfRequiredTypeException ex) {
				/* 附加注释：处理类型不匹配异常的情况，这可能发生在Bean名称存在但实际类型与预期不符的情况，通过日志记录而非抛出异常来保证容器初始化过程不被中断 */
				if (logger.isDebugEnabled()) {
					logger.debug("Failed to initialize LoadTimeWeaverAware bean '" + weaverAwareName +
							"' due to unexpected type mismatch: " + ex.getMessage());
				}
			}
		}

		// Stop using the temporary ClassLoader for type matching.
		// 停止使用临时ClassLoader进行类型匹配。
		beanFactory.setTempClassLoader(null); /* 附加注释：清除临时类加载器，之前用于类型检查和元数据解析，此时不再需要因为所有Bean定义已加载完成，释放相关资源并确保后续使用应用的主类加载器 */

		// Allow for caching all bean definition metadata, not expecting further changes.
		// 允许缓存所有bean定义元数据，不期望进一步更改。
		beanFactory.freezeConfiguration(); /* 附加注释：冻结配置，表明Bean定义将不再修改，允许容器对Bean定义进行内部优化和缓存，提高后续Bean创建和类型检查的性能 */

		// Instantiate all remaining (non-lazy-init) singletons.
		// 实例化所有剩余的（非延迟初始化）单例。
		beanFactory.preInstantiateSingletons(); /* 附加注释：预先实例化所有非懒加载的单例Bean，这是Spring容器启动的关键步骤，确保所有单例Bean在应用就绪前被创建并初始化，发现并解决Bean之间的依赖关系 */
	}

	/**
	 * Finish the refresh of this context, invoking the LifecycleProcessor's
	 * onRefresh() method and publishing the
	 * {@link org.springframework.context.event.ContextRefreshedEvent}.
	 *
	 * 完成此上下文的刷新，调用LifecycleProcessor的onRefresh()方法
	 * 并发布{@link org.springframework.context.event.ContextRefreshedEvent}事件。
	 */
	protected void finishRefresh() {
		// Reset common introspection caches in Spring's core infrastructure.
		// 重置Spring核心基础设施中的常见内省缓存。
		resetCommonCaches(); /* 附加注释：清除Spring框架中的反射缓存，避免内存泄漏，特别是在热部署场景下非常重要，确保应用重启后不会使用过时的缓存信息 */

		// Clear context-level resource caches (such as ASM metadata from scanning).
		// 清除上下文级别的资源缓存（例如来自扫描的ASM元数据）。
		clearResourceCaches(); /* 附加注释：清除应用上下文中的资源缓存，如类路径扫描结果、ASM元数据等，确保资源引用不会长期占用内存，尤其在动态模块加载场景中很重要 */

		// Initialize lifecycle processor for this context.
		// 为此上下文初始化生命周期处理器。
		initLifecycleProcessor(); /* 附加注释：初始化或获取生命周期处理器，该处理器负责管理所有实现Lifecycle接口的bean的启动和停止，如果没有自定义处理器则创建默认的DefaultLifecycleProcessor */

		// Propagate refresh to lifecycle processor first.
		// 首先将刷新传播到生命周期处理器。
		getLifecycleProcessor().onRefresh(); /* 附加注释：通知生命周期处理器上下文已刷新完成，触发所有设置为自动启动的Lifecycle bean的start()方法，使组件如定时任务、消息监听器等开始工作 */

		// Publish the final event.
		// 发布最终事件。
		publishEvent(new ContextRefreshedEvent(this)); /* 附加注释：发布ContextRefreshedEvent事件，标志着Spring容器已完全初始化完成，所有单例bean都已创建，监听此事件的组件可以执行需要在容器完全就绪后进行的操作 */
	}

	/**
	 * Cancel this context's refresh attempt, resetting the {@code active} flag
	 * after an exception got thrown.
	 * @param ex the exception that led to the cancellation
	 */
	protected void cancelRefresh(Throwable ex) {
		this.active.set(false);

		// Reset common introspection caches in Spring's core infrastructure.
		resetCommonCaches();
	}

	/**
	 * Reset Spring's common reflection metadata caches, in particular the
	 * {@link ReflectionUtils}, {@link AnnotationUtils}, {@link ResolvableType}
	 * and {@link CachedIntrospectionResults} caches.
	 * @since 4.2
	 * @see ReflectionUtils#clearCache()
	 * @see AnnotationUtils#clearCache()
	 * @see ResolvableType#clearCache()
	 * @see CachedIntrospectionResults#clearClassLoader(ClassLoader)
	 */
	protected void resetCommonCaches() {
		ReflectionUtils.clearCache();
		AnnotationUtils.clearCache();
		ResolvableType.clearCache();
		CachedIntrospectionResults.clearClassLoader(getClassLoader());
	}

	@Override
	public void clearResourceCaches() {
		super.clearResourceCaches();
		if (this.resourcePatternResolver instanceof PathMatchingResourcePatternResolver pmrpr) {
			pmrpr.clearCache();
		}
	}


	/**
	 * Register a shutdown hook {@linkplain Thread#getName() named}
	 * {@code SpringContextShutdownHook} with the JVM runtime, closing this
	 * context on JVM shutdown unless it has already been closed at that time.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * @see Runtime#addShutdownHook
	 * @see ConfigurableApplicationContext#SHUTDOWN_HOOK_THREAD_NAME
	 * @see #close()
	 * @see #doClose()
	 */
	@Override
	public void registerShutdownHook() {
		if (this.shutdownHook == null) {
			// No shutdown hook registered yet.
			this.shutdownHook = new Thread(SHUTDOWN_HOOK_THREAD_NAME) {
				@Override
				public void run() {
					if (isStartupShutdownThreadStuck()) {
						active.set(false);
						return;
					}
					startupShutdownLock.lock();
					try {
						doClose();
					}
					finally {
						startupShutdownLock.unlock();
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(this.shutdownHook);
		}
	}

	/**
	 * Determine whether an active startup/shutdown thread is currently stuck,
	 * for example, through a {@code System.exit} call in a user component.
	 */
	private boolean isStartupShutdownThreadStuck() {
		Thread activeThread = this.startupShutdownThread;
		if (activeThread != null && activeThread.getState() == Thread.State.WAITING) {
			// Indefinitely waiting: might be Thread.join or the like, or System.exit
			activeThread.interrupt();
			try {
				// Leave just a little bit of time for the interruption to show effect
				Thread.sleep(1);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			if (activeThread.getState() == Thread.State.WAITING) {
				// Interrupted but still waiting: very likely a System.exit call
				return true;
			}
		}
		return false;
	}

	/**
	 * Close this application context, destroying all beans in its bean factory.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * Also removes a JVM shutdown hook, if registered, as it's not needed anymore.
	 * @see #doClose()
	 * @see #registerShutdownHook()
	 */
	@Override
	public void close() {
		if (isStartupShutdownThreadStuck()) {
			this.active.set(false);
			return;
		}

		this.startupShutdownLock.lock();
		try {
			this.startupShutdownThread = Thread.currentThread();

			doClose();

			// If we registered a JVM shutdown hook, we don't need it anymore now:
			// We've already explicitly closed the context.
			if (this.shutdownHook != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
				}
				catch (IllegalStateException ex) {
					// ignore - VM is already shutting down
				}
			}
		}
		finally {
			this.startupShutdownThread = null;
			this.startupShutdownLock.unlock();
		}
	}

	/**
	 * Actually performs context closing: publishes a ContextClosedEvent and
	 * destroys the singletons in the bean factory of this application context.
	 * <p>Called by both {@code close()} and a JVM shutdown hook, if any.
	 * @see org.springframework.context.event.ContextClosedEvent
	 * @see #destroyBeans()
	 * @see #close()
	 * @see #registerShutdownHook()
	 */
	protected void doClose() {
		// Check whether an actual close attempt is necessary...
		if (this.active.get() && this.closed.compareAndSet(false, true)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Closing " + this);
			}

			try {
				// Publish shutdown event.
				publishEvent(new ContextClosedEvent(this));
			}
			catch (Throwable ex) {
				logger.warn("Exception thrown from ApplicationListener handling ContextClosedEvent", ex);
			}

			// Stop all Lifecycle beans, to avoid delays during individual destruction.
			if (this.lifecycleProcessor != null) {
				try {
					this.lifecycleProcessor.onClose();
				}
				catch (Throwable ex) {
					logger.warn("Exception thrown from LifecycleProcessor on context close", ex);
				}
			}

			// Destroy all cached singletons in the context's BeanFactory.
			destroyBeans();

			// Close the state of this context itself.
			closeBeanFactory();

			// Let subclasses do some final clean-up if they wish...
			onClose();

			// Reset common introspection caches to avoid class reference leaks.
			resetCommonCaches();

			// Reset local application listeners to pre-refresh state.
			if (this.earlyApplicationListeners != null) {
				this.applicationListeners.clear();
				this.applicationListeners.addAll(this.earlyApplicationListeners);
			}

			// Reset internal delegates.
			this.applicationEventMulticaster = null;
			this.messageSource = null;
			this.lifecycleProcessor = null;

			// Switch to inactive.
			this.active.set(false);
		}
	}

	/**
	 * Template method for destroying all beans that this context manages.
	 * The default implementation destroy all cached singletons in this context,
	 * invoking {@code DisposableBean.destroy()} and/or the specified
	 * "destroy-method".
	 * <p>Can be overridden to add context-specific bean destruction steps
	 * right before or right after standard singleton destruction,
	 * while the context's BeanFactory is still active.
	 * @see #getBeanFactory()
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#destroySingletons()
	 */
	protected void destroyBeans() {
		getBeanFactory().destroySingletons();
	}

	/**
	 * Template method which can be overridden to add context-specific shutdown work.
	 * The default implementation is empty.
	 * <p>Called at the end of {@link #doClose}'s shutdown procedure, after
	 * this context's BeanFactory has been closed. If custom shutdown logic
	 * needs to execute while the BeanFactory is still active, override
	 * the {@link #destroyBeans()} method instead.
	 */
	protected void onClose() {
		// For subclasses: do nothing by default.
	}

	@Override
	public boolean isClosed() {
		return this.closed.get();
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * Assert that this context's BeanFactory is currently active,
	 * throwing an {@link IllegalStateException} if it isn't.
	 * <p>Invoked by all {@link BeanFactory} delegation methods that depend
	 * on an active context, i.e. in particular all bean accessor methods.
	 * <p>The default implementation checks the {@link #isActive() 'active'} status
	 * of this context overall. May be overridden for more specific checks, or for a
	 * no-op if {@link #getBeanFactory()} itself throws an exception in such a case.
	 */
	protected void assertBeanFactoryActive() {
		if (!this.active.get()) {
			if (this.closed.get()) {
				throw new IllegalStateException(getDisplayName() + " has been closed already");
			}
			else {
				throw new IllegalStateException(getDisplayName() + " has not been refreshed yet");
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, requiredType);
	}

	@Override
	public Object getBean(String name, @Nullable Object @Nullable ... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, args);
	}

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType);
	}

	@Override
	public <T> T getBean(Class<T> requiredType, @Nullable Object @Nullable ... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType, args);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public boolean containsBean(String name) {
		return getBeanFactory().containsBean(name);
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isSingleton(name);
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isPrototype(name);
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	public @Nullable Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name);
	}

	@Override
	public @Nullable Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name, allowFactoryBeanInit);
	}

	@Override
	public String[] getAliases(String name) {
		return getBeanFactory().getAliases(name);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		return getBeanFactory().containsBeanDefinition(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return getBeanFactory().getBeanDefinitionCount();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		return getBeanFactory().getBeanDefinitionNames();
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForAnnotation(annotationType);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansWithAnnotation(annotationType);
	}

	@Override
	public <A extends Annotation> @Nullable A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType);
	}

	@Override
	public <A extends Annotation> @Nullable A findAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType, allowFactoryBeanInit);
	}

	@Override
	public <A extends Annotation> Set<A> findAllAnnotationsOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAllAnnotationsOnBean(beanName, annotationType, allowFactoryBeanInit);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public @Nullable BeanFactory getParentBeanFactory() {
		return getParent();
	}

	@Override
	public boolean containsLocalBean(String name) {
		return getBeanFactory().containsLocalBean(name);
	}

	/**
	 * Return the internal bean factory of the parent context if it implements
	 * ConfigurableApplicationContext; else, return the parent context itself.
	 * @see org.springframework.context.ConfigurableApplicationContext#getBeanFactory
	 */
	protected @Nullable BeanFactory getInternalParentBeanFactory() {
		return (getParent() instanceof ConfigurableApplicationContext cac ?
				cac.getBeanFactory() : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of MessageSource interface
	//---------------------------------------------------------------------

	@Override
	public @Nullable String getMessage(String code, Object @Nullable [] args, @Nullable String defaultMessage, Locale locale) {
		return getMessageSource().getMessage(code, args, defaultMessage, locale);
	}

	@Override
	public String getMessage(String code, Object @Nullable [] args, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(code, args, locale);
	}

	@Override
	public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(resolvable, locale);
	}

	/**
	 * Return the internal MessageSource used by the context.
	 * @return the internal MessageSource (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	private MessageSource getMessageSource() throws IllegalStateException {
		if (this.messageSource == null) {
			throw new IllegalStateException("MessageSource not initialized - " +
					"call 'refresh' before accessing messages via the context: " + this);
		}
		return this.messageSource;
	}

	/**
	 * Return the internal message source of the parent context if it is an
	 * AbstractApplicationContext too; else, return the parent context itself.
	 */
	protected @Nullable MessageSource getInternalParentMessageSource() {
		return (getParent() instanceof AbstractApplicationContext abstractApplicationContext ?
				abstractApplicationContext.messageSource : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of ResourcePatternResolver interface
	//---------------------------------------------------------------------

	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		return this.resourcePatternResolver.getResources(locationPattern);
	}


	//---------------------------------------------------------------------
	// Implementation of Lifecycle interface
	//---------------------------------------------------------------------

	@Override
	public void start() {
		getLifecycleProcessor().start();
		publishEvent(new ContextStartedEvent(this));
	}

	@Override
	public void stop() {
		getLifecycleProcessor().stop();
		publishEvent(new ContextStoppedEvent(this));
	}

	@Override
	public void restart() {
		getLifecycleProcessor().onRestart();
		publishEvent(new ContextStartedEvent(this));
	}

	@Override
	public boolean isRunning() {
		return (this.lifecycleProcessor != null && this.lifecycleProcessor.isRunning());
	}


	//---------------------------------------------------------------------
	// Abstract methods that must be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Subclasses must implement this method to perform the actual configuration load.
	 * The method is invoked by {@link #refresh()} before any other initialization work.
	 * <p>A subclass will either create a new bean factory and hold a reference to it,
	 * or return a single BeanFactory instance that it holds. In the latter case, it will
	 * usually throw an IllegalStateException if refreshing the context more than once.
	 * @throws BeansException if initialization of the bean factory failed
	 * @throws IllegalStateException if already initialized and multiple refresh
	 * attempts are not supported
	 */
	protected abstract void refreshBeanFactory() throws BeansException, IllegalStateException;

	/**
	 * Subclasses must implement this method to release their internal bean factory.
	 * This method gets invoked by {@link #close()} after all other shutdown work.
	 * <p>Should never throw an exception but rather log shutdown failures.
	 */
	protected abstract void closeBeanFactory();

	/**
	 * Subclasses must return their internal bean factory here. They should implement the
	 * lookup efficiently, so that it can be called repeatedly without a performance penalty.
	 * <p>Note: Subclasses should check whether the context is still active before
	 * returning the internal bean factory. The internal factory should generally be
	 * considered unavailable once the context has been closed.
	 * @return this application context's internal bean factory (never {@code null})
	 * @throws IllegalStateException if the context does not hold an internal bean factory yet
	 * (usually if {@link #refresh()} has never been called) or if the context has been
	 * closed already
	 * @see #refreshBeanFactory()
	 * @see #closeBeanFactory()
	 */
	@Override
	public abstract ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;


	/**
	 * Return information about this context.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(getDisplayName());
		sb.append(", started on ").append(new Date(getStartupDate()));
		ApplicationContext parent = getParent();
		if (parent != null) {
			sb.append(", parent: ").append(parent.getDisplayName());
		}
		return sb.toString();
	}

}

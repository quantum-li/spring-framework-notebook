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

package org.springframework.beans.factory.support;

import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.core.*;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.log.LogMessage;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Contract;
import org.springframework.util.*;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Spring对{@link ConfigurableListableBeanFactory}和{@link BeanDefinitionRegistry}接口的默认实现：
 * 一个基于bean定义元数据的完整bean工厂，可通过后处理器扩展。
 *
 * <p>典型用法是首先注册所有bean定义（可能从bean定义文件中读取），然后再访问bean。
 * 通过名称查找bean因此是本地bean定义表中的一种低成本操作，操作的是预先解析的bean定义元数据对象。
 *
 * <p>请注意，特定bean定义格式的读取器通常是单独实现的，而不是作为bean工厂的子类：
 * 例如，请参见{@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}。
 *
 * <p>对于{@link org.springframework.beans.factory.ListableBeanFactory}接口的替代实现，
 * 请查看{@link StaticListableBeanFactory}，它管理现有的bean实例，而不是基于bean定义创建新的实例。
 *
 * Spring's default implementation of the {@link ConfigurableListableBeanFactory}
 * and {@link BeanDefinitionRegistry} interfaces: a full-fledged bean factory
 * based on bean definition metadata, extensible through post-processors.
 *
 * <p>Typical usage is registering all bean definitions first (possibly read
 * from a bean definition file), before accessing beans. Bean lookup by name
 * is therefore an inexpensive operation in a local bean definition table,
 * operating on pre-resolved bean definition metadata objects.
 *
 * <p>Note that readers for specific bean definition formats are typically
 * implemented separately rather than as bean factory subclasses: see for example
 * {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 *
 * <p>For an alternative implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} interface,
 * have a look at {@link StaticListableBeanFactory}, which manages existing
 * bean instances rather than creating new ones based on bean definitions.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @since 16 April 2001
 * @see #registerBeanDefinition
 * @see #addBeanPostProcessor
 * @see #getBean
 * @see #resolveDependency
 */
@SuppressWarnings("serial")
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

	/**
	 * System property that instructs Spring to enforce strict locking during bean creation,
	 * rather than the mix of strict and lenient locking that 6.2 applies by default. Setting
	 * this flag to "true" restores 6.1.x style locking in the entire pre-instantiation phase.
	 * <p>By default, the factory infers strict locking from the encountered thread names:
	 * If additional threads have names that match the thread prefix of the main bootstrap thread,
	 * they are considered external (multiple external bootstrap threads calling into the factory)
	 * and therefore have strict locking applied to them. This inference can be turned off through
	 * explicitly setting this flag to "false" rather than leaving it unspecified.
	 * @since 6.2.6
	 * @see #preInstantiateSingletons()
	 */
	public static final String STRICT_LOCKING_PROPERTY_NAME = "spring.locking.strict";

	private static @Nullable Class<?> jakartaInjectProviderClass;

	static {
		try {
			jakartaInjectProviderClass =
					ClassUtils.forName("jakarta.inject.Provider", DefaultListableBeanFactory.class.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - Provider interface simply not supported then.
			jakartaInjectProviderClass = null;
		}
	}


	/** Map from serialized id to factory instance. */
	private static final Map<String, Reference<DefaultListableBeanFactory>> serializableFactories =
			new ConcurrentHashMap<>(8);

	/** Whether strict locking is enforced or relaxed in this factory. */
	private final @Nullable Boolean strictLocking = SpringProperties.checkFlag(STRICT_LOCKING_PROPERTY_NAME);

	/** Optional id for this factory, for serialization purposes. */
	private @Nullable String serializationId;

	/** Whether to allow re-registration of a different definition with the same name. */
	private @Nullable Boolean allowBeanDefinitionOverriding;

	/** Whether to allow eager class loading even for lazy-init beans. */
	private boolean allowEagerClassLoading = true;

	private @Nullable Executor bootstrapExecutor;

	/** Optional OrderComparator for dependency Lists and arrays. */
	private @Nullable Comparator<Object> dependencyComparator;

	/** Resolver to use for checking if a bean definition is an autowire candidate. */
	private AutowireCandidateResolver autowireCandidateResolver = SimpleAutowireCandidateResolver.INSTANCE;

	/** Map from dependency type to corresponding autowired value. */
	private final Map<Class<?>, Object> resolvableDependencies = new ConcurrentHashMap<>(16);

	/** Map of bean definition objects, keyed by bean name. */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

	/** Map from bean name to merged BeanDefinitionHolder. */
	private final Map<String, BeanDefinitionHolder> mergedBeanDefinitionHolders = new ConcurrentHashMap<>(256);

	/** Set of bean definition names with a primary marker. */
	private final Set<String> primaryBeanNames = ConcurrentHashMap.newKeySet(16);

	/** Map of singleton and non-singleton bean names, keyed by dependency type. */
	private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<>(64);

	/** Map of singleton-only bean names, keyed by dependency type. */
	private final Map<Class<?>, String[]> singletonBeanNamesByType = new ConcurrentHashMap<>(64);

	/** List of bean definition names, in registration order. */
	private volatile List<String> beanDefinitionNames = new ArrayList<>(256);

	/** List of names of manually registered singletons, in registration order. */
	private volatile Set<String> manualSingletonNames = new LinkedHashSet<>(16);

	/** Cached array of bean definition names in case of frozen configuration. */
	private volatile String @Nullable [] frozenBeanDefinitionNames;

	/** Whether bean definition metadata may be cached for all beans. */
	private volatile boolean configurationFrozen;

	/** Name prefix of main thread: only set during pre-instantiation phase. */
	private volatile @Nullable String mainThreadPrefix;

	private final NamedThreadLocal<PreInstantiation> preInstantiationThread =
			new NamedThreadLocal<>("Pre-instantiation thread marker");


	/**
	 * Create a new DefaultListableBeanFactory.
	 */
	public DefaultListableBeanFactory() {
		super();
	}

	/**
	 * Create a new DefaultListableBeanFactory with the given parent.
	 * @param parentBeanFactory the parent BeanFactory
	 */
	public DefaultListableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		super(parentBeanFactory);
	}


	/**
	 * Specify an id for serialization purposes, allowing this BeanFactory to be
	 * deserialized from this id back into the BeanFactory object, if needed.
	 */
	public void setSerializationId(@Nullable String serializationId) {
		if (serializationId != null) {
			serializableFactories.put(serializationId, new WeakReference<>(this));
		}
		else if (this.serializationId != null) {
			serializableFactories.remove(this.serializationId);
		}
		this.serializationId = serializationId;
	}

	/**
	 * Return an id for serialization purposes, if specified, allowing this BeanFactory
	 * to be deserialized from this id back into the BeanFactory object, if needed.
	 * @since 4.1.2
	 */
	public @Nullable String getSerializationId() {
		return this.serializationId;
	}

	/**
	 * Set whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * If not, an exception will be thrown. This also applies to overriding aliases.
	 * <p>Default is "true".
	 * @see #registerBeanDefinition
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Return whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * @since 4.1.2
	 */
	public boolean isAllowBeanDefinitionOverriding() {
		return !Boolean.FALSE.equals(this.allowBeanDefinitionOverriding);
	}

	/**
	 * Set whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>Default is "true". Turn this flag off to suppress class loading
	 * for lazy-init beans unless such a bean is explicitly requested.
	 * In particular, by-type lookups will then simply ignore bean definitions
	 * without resolved class name, instead of loading the bean classes on
	 * demand just to perform a type check.
	 * @see AbstractBeanDefinition#setLazyInit
	 */
	public void setAllowEagerClassLoading(boolean allowEagerClassLoading) {
		this.allowEagerClassLoading = allowEagerClassLoading;
	}

	/**
	 * Return whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * @since 4.1.2
	 */
	public boolean isAllowEagerClassLoading() {
		return this.allowEagerClassLoading;
	}

	@Override
	public void setBootstrapExecutor(@Nullable Executor bootstrapExecutor) {
		this.bootstrapExecutor = bootstrapExecutor;
	}

	@Override
	public @Nullable Executor getBootstrapExecutor() {
		return this.bootstrapExecutor;
	}

	/**
	 * Set a {@link java.util.Comparator} for dependency Lists and arrays.
	 * @since 4.0
	 * @see org.springframework.core.OrderComparator
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 */
	public void setDependencyComparator(@Nullable Comparator<Object> dependencyComparator) {
		this.dependencyComparator = dependencyComparator;
	}

	/**
	 * Return the dependency comparator for this BeanFactory (may be {@code null}).
	 * @since 4.0
	 */
	public @Nullable Comparator<Object> getDependencyComparator() {
		return this.dependencyComparator;
	}

	/**
	 * Set a custom autowire candidate resolver for this BeanFactory to use
	 * when deciding whether a bean definition should be considered as a
	 * candidate for autowiring.
	 */
	public void setAutowireCandidateResolver(AutowireCandidateResolver autowireCandidateResolver) {
		Assert.notNull(autowireCandidateResolver, "AutowireCandidateResolver must not be null");
		if (autowireCandidateResolver instanceof BeanFactoryAware beanFactoryAware) {
			beanFactoryAware.setBeanFactory(this);
		}
		this.autowireCandidateResolver = autowireCandidateResolver;
	}

	/**
	 * Return the autowire candidate resolver for this BeanFactory (never {@code null}).
	 */
	public AutowireCandidateResolver getAutowireCandidateResolver() {
		return this.autowireCandidateResolver;
	}


	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof DefaultListableBeanFactory otherListableFactory) {
			this.allowBeanDefinitionOverriding = otherListableFactory.allowBeanDefinitionOverriding;
			this.allowEagerClassLoading = otherListableFactory.allowEagerClassLoading;
			this.bootstrapExecutor = otherListableFactory.bootstrapExecutor;
			this.dependencyComparator = otherListableFactory.dependencyComparator;
			// A clone of the AutowireCandidateResolver since it is potentially BeanFactoryAware
			setAutowireCandidateResolver(otherListableFactory.getAutowireCandidateResolver().cloneIfNecessary());
			// Make resolvable dependencies (for example, ResourceLoader) available here as well
			this.resolvableDependencies.putAll(otherListableFactory.resolvableDependencies);
		}
	}


	//---------------------------------------------------------------------
	// Implementation of remaining BeanFactory methods
	//---------------------------------------------------------------------

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		return getBean(requiredType, (Object[]) null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getBean(Class<T> requiredType, @Nullable Object @Nullable ... args) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		Object resolved = resolveBean(ResolvableType.forRawClass(requiredType), args, false);
		if (resolved == null) {
			throw new NoSuchBeanDefinitionException(requiredType);
		}
		return (T) resolved;
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType), true);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		return getBeanProvider(requiredType, true);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		String[] frozenNames = this.frozenBeanDefinitionNames;
		if (frozenNames != null) {
			return frozenNames.clone();
		}
		else {
			return StringUtils.toStringArray(this.beanDefinitionNames);
		}
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType), allowEagerInit);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		return new BeanObjectProvider<>() {
			@Override
			public T getObject() throws BeansException {
				T resolved = resolveBean(requiredType, null, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			public T getObject(@Nullable Object... args) throws BeansException {
				T resolved = resolveBean(requiredType, args, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			public @Nullable T getIfAvailable() throws BeansException {
				try {
					return resolveBean(requiredType, null, false);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifAvailable(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfAvailable();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@Override
			public @Nullable T getIfUnique() throws BeansException {
				try {
					return resolveBean(requiredType, null, true);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifUnique(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfUnique();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> stream() {
				return Arrays.stream(beanNamesForStream(requiredType, true, allowEagerInit))
						.map(name -> (T) resolveBean(name, requiredType))
						.filter(bean -> !(bean instanceof NullBean));
			}
			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> orderedStream() {
				String[] beanNames = beanNamesForStream(requiredType, true, allowEagerInit);
				if (beanNames.length == 0) {
					return Stream.empty();
				}
				Map<String, T> matchingBeans = CollectionUtils.newLinkedHashMap(beanNames.length);
				for (String beanName : beanNames) {
					Object beanInstance = resolveBean(beanName, requiredType);
					if (!(beanInstance instanceof NullBean)) {
						matchingBeans.put(beanName, (T) beanInstance);
					}
				}
				Stream<T> stream = matchingBeans.values().stream();
				return stream.sorted(adaptOrderComparator(matchingBeans));
			}
			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> stream(Predicate<Class<?>> customFilter, boolean includeNonSingletons) {
				return Arrays.stream(beanNamesForStream(requiredType, includeNonSingletons, allowEagerInit))
						.filter(name -> customFilter.test(getType(name)))
						.map(name -> (T) resolveBean(name, requiredType))
						.filter(bean -> !(bean instanceof NullBean));
			}
			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> orderedStream(Predicate<Class<?>> customFilter, boolean includeNonSingletons) {
				String[] beanNames = beanNamesForStream(requiredType, includeNonSingletons, allowEagerInit);
				if (beanNames.length == 0) {
					return Stream.empty();
				}
				Map<String, T> matchingBeans = CollectionUtils.newLinkedHashMap(beanNames.length);
				for (String beanName : beanNames) {
					if (customFilter.test(getType(beanName))) {
						Object beanInstance = resolveBean(beanName, requiredType);
						if (!(beanInstance instanceof NullBean)) {
							matchingBeans.put(beanName, (T) beanInstance);
						}
					}
				}
				return matchingBeans.values().stream().sorted(adaptOrderComparator(matchingBeans));
			}
		};
	}

	private <T> @Nullable T resolveBean(ResolvableType requiredType, @Nullable Object @Nullable [] args, boolean nonUniqueAsNull) {
		NamedBeanHolder<T> namedBean = resolveNamedBean(requiredType, args, nonUniqueAsNull);
		if (namedBean != null) {
			return namedBean.getBeanInstance();
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory dlbf) {
			return dlbf.resolveBean(requiredType, args, nonUniqueAsNull);
		}
		else if (parent != null) {
			ObjectProvider<T> parentProvider = parent.getBeanProvider(requiredType);
			if (args != null) {
				return parentProvider.getObject(args);
			}
			else {
				return (nonUniqueAsNull ? parentProvider.getIfUnique() : parentProvider.getIfAvailable());
			}
		}
		return null;
	}

	private String[] beanNamesForStream(ResolvableType requiredType, boolean includeNonSingletons, boolean allowEagerInit) {
		return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(this, requiredType, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		return getBeanNamesForType(type, true, true);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		Class<?> resolved = type.resolve();
		if (resolved != null && !type.hasGenerics()) {
			return getBeanNamesForType(resolved, includeNonSingletons, allowEagerInit);
		}
		else {
			return doGetBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		}
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		return getBeanNamesForType(type, true, true);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
			return doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit);
		}
		Map<Class<?>, String[]> cache =
				(includeNonSingletons ? this.allBeanNamesByType : this.singletonBeanNamesByType);
		String[] resolvedBeanNames = cache.get(type);
		if (resolvedBeanNames != null) {
			return resolvedBeanNames;
		}
		resolvedBeanNames = doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, true);
		if (ClassUtils.isCacheSafe(type, getBeanClassLoader())) {
			cache.put(type, resolvedBeanNames);
		}
		return resolvedBeanNames;
	}

	private String[] doGetBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		List<String> result = new ArrayList<>();

		// Check all bean definitions.
		for (String beanName : this.beanDefinitionNames) {
			// Only consider bean as eligible if the bean name is not defined as alias for some other bean.
			if (!isAlias(beanName)) {
				try {
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// Only check bean definition if it is complete.
					if (!mbd.isAbstract() && (allowEagerInit ||
							(mbd.hasBeanClass() || !mbd.isLazyInit() || isAllowEagerClassLoading()) &&
									!requiresEagerInitForType(mbd.getFactoryBeanName()))) {
						boolean isFactoryBean = isFactoryBean(beanName, mbd);
						BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
						boolean matchFound = false;
						boolean allowFactoryBeanInit = (allowEagerInit || containsSingleton(beanName));
						boolean isNonLazyDecorated = (dbd != null && !mbd.isLazyInit());
						if (!isFactoryBean) {
							if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						else {
							if (includeNonSingletons || isNonLazyDecorated) {
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
							else if (allowFactoryBeanInit) {
								// Type check before singleton check, avoiding FactoryBean instantiation
								// for early FactoryBean.isSingleton() calls on non-matching beans.
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit) &&
										isSingleton(beanName, mbd, dbd);
							}
							if (!matchFound) {
								// In case of FactoryBean, try to match FactoryBean instance itself next.
								beanName = FACTORY_BEAN_PREFIX + beanName;
								if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
									matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
								}
							}
						}
						if (matchFound) {
							result.add(beanName);
						}
					}
				}
				catch (CannotLoadBeanClassException | BeanDefinitionStoreException ex) {
					if (allowEagerInit) {
						throw ex;
					}
					// Probably a placeholder: let's ignore it for type matching purposes.
					LogMessage message = (ex instanceof CannotLoadBeanClassException ?
							LogMessage.format("Ignoring bean class loading failure for bean '%s'", beanName) :
							LogMessage.format("Ignoring unresolvable metadata in bean definition '%s'", beanName));
					logger.trace(message, ex);
					// Register exception, in case the bean was accidentally unresolvable.
					onSuppressedException(ex);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Bean definition got removed while we were iterating -> ignore.
				}
			}
		}

		// Check manually registered singletons too.
		for (String beanName : this.manualSingletonNames) {
			try {
				// In case of FactoryBean, match object created by FactoryBean.
				if (isFactoryBean(beanName)) {
					if ((includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type)) {
						result.add(beanName);
						// Match found for this bean: do not match FactoryBean itself anymore.
						continue;
					}
					// In case of FactoryBean, try to match FactoryBean itself next.
					beanName = FACTORY_BEAN_PREFIX + beanName;
				}
				// Match raw bean instance (might be raw FactoryBean).
				if (isTypeMatch(beanName, type)) {
					result.add(beanName);
				}
			}
			catch (NoSuchBeanDefinitionException ex) {
				// Shouldn't happen - probably a result of circular reference resolution...
				logger.trace(LogMessage.format(
						"Failed to check manually registered singleton with name '%s'", beanName), ex);
			}
		}

		return StringUtils.toStringArray(result);
	}

	private boolean isSingleton(String beanName, RootBeanDefinition mbd, @Nullable BeanDefinitionHolder dbd) {
		return (dbd != null ? mbd.isSingleton() : isSingleton(beanName));
	}

	/**
	 * Check whether the specified bean would need to be eagerly initialized
	 * in order to determine its type.
	 * @param factoryBeanName a factory-bean reference that the bean definition
	 * defines a factory method for
	 * @return whether eager initialization is necessary
	 */
	private boolean requiresEagerInitForType(@Nullable String factoryBeanName) {
		return (factoryBeanName != null && isFactoryBean(factoryBeanName) && !containsSingleton(factoryBeanName));
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		return getBeansOfType(type, true, true);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<String, T> getBeansOfType(
			@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit) throws BeansException {

		String[] beanNames = getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		Map<String, T> result = CollectionUtils.newLinkedHashMap(beanNames.length);
		for (String beanName : beanNames) {
			try {
				Object beanInstance = (type != null ? getBean(beanName, type) : getBean(beanName));
				if (!(beanInstance instanceof NullBean)) {
					result.put(beanName, (T) beanInstance);
				}
			}
			catch (BeanNotOfRequiredTypeException ex) {
				// Ignore - probably a NullBean
			}
			catch (BeanCreationException ex) {
				Throwable rootCause = ex.getMostSpecificCause();
				if (rootCause instanceof BeanCurrentlyInCreationException bce) {
					String exBeanName = bce.getBeanName();
					if (exBeanName != null && isCurrentlyInCreation(exBeanName)) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring match to currently created bean '" + exBeanName + "': " +
									ex.getMessage());
						}
						onSuppressedException(ex);
						// Ignore: indicates a circular reference when autowiring constructors.
						// We want to find matches other than the currently created bean itself.
						continue;
					}
				}
				throw ex;
			}
		}
		return result;
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		List<String> result = new ArrayList<>();
		for (String beanName : this.beanDefinitionNames) {
			BeanDefinition bd = this.beanDefinitionMap.get(beanName);
			if (bd != null && !bd.isAbstract() && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		for (String beanName : this.manualSingletonNames) {
			if (!result.contains(beanName) && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		return StringUtils.toStringArray(result);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
		String[] beanNames = getBeanNamesForAnnotation(annotationType);
		Map<String, Object> result = CollectionUtils.newLinkedHashMap(beanNames.length);
		for (String beanName : beanNames) {
			Object beanInstance = getBean(beanName);
			if (!(beanInstance instanceof NullBean)) {
				result.put(beanName, beanInstance);
			}
		}
		return result;
	}

	@Override
	public <A extends Annotation> @Nullable A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		return findAnnotationOnBean(beanName, annotationType, true);
	}

	@Override
	public <A extends Annotation> @Nullable A findAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		Class<?> beanType = getType(beanName, allowFactoryBeanInit);
		if (beanType != null) {
			MergedAnnotation<A> annotation =
					MergedAnnotations.from(beanType, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
			if (annotation.isPresent()) {
				return annotation.synthesize();
			}
		}
		if (containsBeanDefinition(beanName)) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// Check raw bean class, for example, in case of a proxy.
			if (bd.hasBeanClass() && bd.getFactoryMethodName() == null) {
				Class<?> beanClass = bd.getBeanClass();
				if (beanClass != beanType) {
					MergedAnnotation<A> annotation =
							MergedAnnotations.from(beanClass, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
					if (annotation.isPresent()) {
						return annotation.synthesize();
					}
				}
			}
			// Check annotations declared on factory method, if any.
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				MergedAnnotation<A> annotation =
						MergedAnnotations.from(factoryMethod, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
				if (annotation.isPresent()) {
					return annotation.synthesize();
				}
			}
		}
		return null;
	}

	@Override
	public <A extends Annotation> Set<A> findAllAnnotationsOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		Set<A> annotations = new LinkedHashSet<>();
		Class<?> beanType = getType(beanName, allowFactoryBeanInit);
		if (beanType != null) {
			MergedAnnotations.from(beanType, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
					.stream(annotationType)
					.filter(MergedAnnotation::isPresent)
					.forEach(mergedAnnotation -> annotations.add(mergedAnnotation.synthesize()));
		}
		if (containsBeanDefinition(beanName)) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// Check raw bean class, for example, in case of a proxy.
			if (bd.hasBeanClass() && bd.getFactoryMethodName() == null) {
				Class<?> beanClass = bd.getBeanClass();
				if (beanClass != beanType) {
					MergedAnnotations.from(beanClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
							.stream(annotationType)
							.filter(MergedAnnotation::isPresent)
							.forEach(mergedAnnotation -> annotations.add(mergedAnnotation.synthesize()));
				}
			}
			// Check annotations declared on factory method, if any.
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				MergedAnnotations.from(factoryMethod, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
						.stream(annotationType)
						.filter(MergedAnnotation::isPresent)
						.forEach(mergedAnnotation -> annotations.add(mergedAnnotation.synthesize()));
			}
		}
		return annotations;
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void registerResolvableDependency(Class<?> dependencyType, @Nullable Object autowiredValue) {
		Assert.notNull(dependencyType, "Dependency type must not be null");
		if (autowiredValue != null) {
			if (!(autowiredValue instanceof ObjectFactory || dependencyType.isInstance(autowiredValue))) {
				throw new IllegalArgumentException("Value [" + autowiredValue +
						"] does not implement specified dependency type [" + dependencyType.getName() + "]");
			}
			this.resolvableDependencies.put(dependencyType, autowiredValue);
		}
	}

	@Override
	public boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {

		return isAutowireCandidate(beanName, descriptor, getAutowireCandidateResolver());
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * @param beanName the name of the bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(
			String beanName, DependencyDescriptor descriptor, AutowireCandidateResolver resolver)
			throws NoSuchBeanDefinitionException {

		String bdName = transformedBeanName(beanName);
		if (containsBeanDefinition(bdName)) {
			return isAutowireCandidate(beanName, getMergedLocalBeanDefinition(bdName), descriptor, resolver);
		}
		else if (containsSingleton(beanName)) {
			return isAutowireCandidate(beanName, new RootBeanDefinition(getType(beanName)), descriptor, resolver);
		}

		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory dlbf) {
			// No bean definition found in this factory -> delegate to parent.
			return dlbf.isAutowireCandidate(beanName, descriptor, resolver);
		}
		else if (parent instanceof ConfigurableListableBeanFactory clbf) {
			// If no DefaultListableBeanFactory, can't pass the resolver along.
			return clbf.isAutowireCandidate(beanName, descriptor);
		}
		else {
			return true;
		}
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * @param beanName the name of the bean definition to check
	 * @param mbd the merged bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(String beanName, RootBeanDefinition mbd,
			DependencyDescriptor descriptor, AutowireCandidateResolver resolver) {

		String bdName = transformedBeanName(beanName);
		resolveBeanClass(mbd, bdName);
		if (mbd.isFactoryMethodUnique && mbd.factoryMethodToIntrospect == null) {
			new ConstructorResolver(this).resolveFactoryMethodIfPossible(mbd);
		}
		BeanDefinitionHolder holder = (beanName.equals(bdName) ?
				this.mergedBeanDefinitionHolders.computeIfAbsent(beanName,
						key -> new BeanDefinitionHolder(mbd, beanName, getAliases(bdName))) :
				new BeanDefinitionHolder(mbd, beanName, getAliases(bdName)));
		return resolver.isAutowireCandidate(holder, descriptor);
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}
		return bd;
	}

	@Override
	public Iterator<String> getBeanNamesIterator() {
		CompositeIterator<String> iterator = new CompositeIterator<>();
		iterator.add(this.beanDefinitionNames.iterator());
		iterator.add(this.manualSingletonNames.iterator());
		return iterator;
	}

	@Override
	protected void clearMergedBeanDefinition(String beanName) {
		super.clearMergedBeanDefinition(beanName);
		this.mergedBeanDefinitionHolders.remove(beanName);
	}

	@Override
	public void clearMetadataCache() {
		super.clearMetadataCache();
		this.mergedBeanDefinitionHolders.clear();
		clearByTypeCache();
	}

	@Override
	public void freezeConfiguration() {
		clearMetadataCache();
		this.configurationFrozen = true;
		this.frozenBeanDefinitionNames = StringUtils.toStringArray(this.beanDefinitionNames);
	}

	@Override
	public boolean isConfigurationFrozen() {
		return this.configurationFrozen;
	}

	/**
	 * Considers all beans as eligible for metadata caching
	 * if the factory's configuration has been marked as frozen.
	 * @see #freezeConfiguration()
	 */
	@Override
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return (this.configurationFrozen || super.isBeanEligibleForMetadataCaching(beanName));
	}

	@Override
	protected @Nullable Object obtainInstanceFromSupplier(Supplier<?> supplier, String beanName, RootBeanDefinition mbd)
			throws Exception {

		if (supplier instanceof InstanceSupplier<?> instanceSupplier) {
			return instanceSupplier.get(RegisteredBean.of(this, beanName, mbd));
		}
		return super.obtainInstanceFromSupplier(supplier, beanName, mbd);
	}

	@Override
	protected void cacheMergedBeanDefinition(RootBeanDefinition mbd, String beanName) {
		super.cacheMergedBeanDefinition(mbd, beanName);
		if (mbd.isPrimary()) {
			this.primaryBeanNames.add(beanName);
		}
	}

	@Override
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, @Nullable Object @Nullable [] args) {
		super.checkMergedBeanDefinition(mbd, beanName, args);

		if (mbd.isBackgroundInit()) {
			if (this.preInstantiationThread.get() == PreInstantiation.MAIN && getBootstrapExecutor() != null) {
				throw new BeanCurrentlyInCreationException(beanName, "Bean marked for background " +
						"initialization but requested in mainline thread - declare ObjectProvider " +
						"or lazy injection point in dependent mainline beans");
			}
		}
		else {
			// Bean intended to be initialized in main bootstrap thread.
			if (this.preInstantiationThread.get() == PreInstantiation.BACKGROUND) {
				throw new BeanCurrentlyInCreationException(beanName, "Bean marked for mainline initialization " +
						"but requested in background thread - enforce early instantiation in mainline thread " +
						"through depends-on '" + beanName + "' declaration for dependent background beans");
			}
		}
	}

	@Override
	protected @Nullable Boolean isCurrentThreadAllowedToHoldSingletonLock() {
		String mainThreadPrefix = this.mainThreadPrefix;
		if (mainThreadPrefix != null) {
			// We only differentiate in the preInstantiateSingletons phase, using
			// the volatile mainThreadPrefix field as an indicator for that phase.

			PreInstantiation preInstantiation = this.preInstantiationThread.get();
			if (preInstantiation != null) {
				// A Spring-managed bootstrap thread:
				// MAIN is allowed to lock (true) or even forced to lock (null),
				// BACKGROUND is never allowed to lock (false).
				return switch (preInstantiation) {
					case MAIN -> (Boolean.TRUE.equals(this.strictLocking) ? null : true);
					case BACKGROUND -> false;
				};
			}

			// Not a Spring-managed bootstrap thread...
			if (Boolean.FALSE.equals(this.strictLocking)) {
				// Explicitly configured to use lenient locking wherever possible.
				return true;
			}
			else if (this.strictLocking == null) {
				// No explicit locking configuration -> infer appropriate locking.
				if (!getThreadNamePrefix().equals(mainThreadPrefix)) {
					// An unmanaged thread (assumed to be application-internal) with lenient locking,
					// and not part of the same thread pool that provided the main bootstrap thread
					// (excluding scenarios where we are hit by multiple external bootstrap threads).
					return true;
				}
			}
		}

		// Traditional behavior: forced to always hold a full lock.
		return null;
	}

	@Override
	public void preInstantiateSingletons() throws BeansException {
		if (logger.isTraceEnabled()) {
			logger.trace("Pre-instantiating singletons in " + this);
		}

		// Iterate over a copy to allow for init methods which in turn register new bean definitions.
		// While this may not be part of the regular factory bootstrap, it does otherwise work fine.
		// 迭代一个副本以允许初始化方法反过来注册新的bean定义。
		// 虽然这可能不是常规工厂引导的一部分，但它在其他方面运行良好。
		/* 附加注释：使用副本是为了防止在初始化过程中新增的bean定义影响迭代过程，确保线程安全 */
		List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

		// Trigger initialization of all non-lazy singleton beans...
		// 触发所有非延迟单例bean的初始化...
		/* 附加注释：用于存储后台初始化的Future对象，以便后续等待它们完成 */
		List<CompletableFuture<?>> futures = new ArrayList<>();

		/* 附加注释：标记当前线程为主初始化线程，用于区分主线程和后台线程的bean初始化权限 */
		this.preInstantiationThread.set(PreInstantiation.MAIN);
		/* 附加注释：记录主线程的前缀，用于后续线程识别和锁控制策略 */
		this.mainThreadPrefix = getThreadNamePrefix();
		try {
			for (String beanName : beanNames) {
				/* 附加注释：获取合并后的bean定义，包含了所有继承的属性和本地定义的属性 */
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				/* 附加注释：只处理非抽象且是单例的bean定义 */
				if (!mbd.isAbstract() && mbd.isSingleton()) {
					/* 附加注释：尝试预实例化单例bean，如果是后台初始化的bean则返回对应的Future */
					CompletableFuture<?> future = preInstantiateSingleton(beanName, mbd);
					if (future != null) {
						futures.add(future);
					}
				}
			}
		}
		finally {
			/* 附加注释：清理线程标记，防止内存泄漏和状态混乱 */
			this.mainThreadPrefix = null;
			this.preInstantiationThread.remove();
		}

		/* 附加注释：等待所有后台初始化的bean完成，确保在进行下一步前所有bean都已初始化完成 */
		if (!futures.isEmpty()) {
			try {
				/* 附加注释：使用CompletableFuture.allOf等待所有后台任务完成，提高并发性能 */
				CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
			}
			catch (CompletionException ex) {
				/* 附加注释：提取并重新抛出原始异常，保持异常栈的清晰 */
				ReflectionUtils.rethrowRuntimeException(ex.getCause());
			}
		}

		// Trigger post-initialization callback for all applicable beans...
		// 为所有适用的bean触发后初始化回调...
		for (String beanName : beanNames) {
			/* 附加注释：获取已初始化的单例实例，不触发新的初始化 */
			Object singletonInstance = getSingleton(beanName, false);
			/* 附加注释：处理实现了SmartInitializingSingleton接口的bean，在所有单例bean初始化后调用它们的回调方法 */
			if (singletonInstance instanceof SmartInitializingSingleton smartSingleton) {
				/* 附加注释：记录启动步骤，用于性能分析和监控 */
				StartupStep smartInitialize = getApplicationStartup().start("spring.beans.smart-initialize")
						.tag("beanName", beanName);
				/* 附加注释：调用SmartInitializingSingleton接口的afterSingletonsInstantiated方法，允许bean在所有单例初始化后执行特定逻辑 */
				smartSingleton.afterSingletonsInstantiated();
				smartInitialize.end();
			}
		}
	}

	private @Nullable CompletableFuture<?> preInstantiateSingleton(String beanName, RootBeanDefinition mbd) {
		if (mbd.isBackgroundInit()) {
			Executor executor = getBootstrapExecutor();
			if (executor != null) {
				String[] dependsOn = mbd.getDependsOn();
				if (dependsOn != null) {
					for (String dep : dependsOn) {
						getBean(dep);
					}
				}
				CompletableFuture<?> future = CompletableFuture.runAsync(
						() -> instantiateSingletonInBackgroundThread(beanName), executor);
				addSingletonFactory(beanName, () -> {
					try {
						future.join();
					}
					catch (CompletionException ex) {
						ReflectionUtils.rethrowRuntimeException(ex.getCause());
					}
					return future;  // not to be exposed, just to lead to ClassCastException in case of mismatch
				});
				return (!mbd.isLazyInit() ? future : null);
			}
			else if (logger.isInfoEnabled()) {
				logger.info("Bean '" + beanName + "' marked for background initialization " +
						"without bootstrap executor configured - falling back to mainline initialization");
			}
		}

		if (!mbd.isLazyInit()) {
			try {
				instantiateSingleton(beanName);
			}
			catch (BeanCurrentlyInCreationException ex) {
				logger.info("Bean '" + beanName + "' marked for pre-instantiation (not lazy-init) " +
						"but currently initialized by other thread - skipping it in mainline thread");
			}
		}
		return null;
	}

	private void instantiateSingletonInBackgroundThread(String beanName) {
		this.preInstantiationThread.set(PreInstantiation.BACKGROUND);
		try {
			instantiateSingleton(beanName);
		}
		catch (RuntimeException | Error ex) {
			if (logger.isWarnEnabled()) {
				logger.warn("Failed to instantiate singleton bean '" + beanName + "' in background thread", ex);
			}
			throw ex;
		}
		finally {
			this.preInstantiationThread.remove();
		}
	}

	private void instantiateSingleton(String beanName) {
		/* 附加注释：检查是否为FactoryBean，FactoryBean需要特殊处理，因为它们可以创建其他bean */
		if (isFactoryBean(beanName)) {
			/* 附加注释：获取FactoryBean实例本身，通过添加'&'前缀来访问工厂而非其产品 */
			Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
			/* 附加注释：检查是否为SmartFactoryBean且需要急切初始化，这种情况下需要主动初始化其产品 */
			if (bean instanceof SmartFactoryBean<?> smartFactoryBean && smartFactoryBean.isEagerInit()) {
				/* 附加注释：通过调用getBean获取FactoryBean生产的实际bean实例，触发其初始化过程 */
				getBean(beanName);
			}
		}
		/* 附加注释：处理普通bean的情况，直接初始化bean实例 */
		else {
			/* 附加注释：调用getBean触发bean的创建和初始化过程，确保单例bean被完全实例化 */
			getBean(beanName);
		}
	}

	private Object resolveBean(String beanName, ResolvableType requiredType) {
		try {
			// Need to provide required type for SmartFactoryBean
			return getBean(beanName, requiredType.toClass());
		}
		catch (BeanNotOfRequiredTypeException ex) {
			// Probably a null bean...
			return getBean(beanName);
		}
	}

	private static String getThreadNamePrefix() {
		String name = Thread.currentThread().getName();
		int numberSeparator = name.lastIndexOf('-');
		return (numberSeparator >= 0 ? name.substring(0, numberSeparator) : name);
	}


	//---------------------------------------------------------------------
	// Implementation of BeanDefinitionRegistry interface
	//---------------------------------------------------------------------

	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {

		Assert.hasText(beanName, "Bean name must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");

		if (beanDefinition instanceof AbstractBeanDefinition abd) {
			try {
				abd.validate();
			}
			catch (BeanDefinitionValidationException ex) {
				throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
						"Validation of bean definition failed", ex);
			}
		}

		BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
		if (existingDefinition != null) {
			if (!isBeanDefinitionOverridable(beanName)) {
				throw new BeanDefinitionOverrideException(beanName, beanDefinition, existingDefinition);
			}
			else {
				logBeanDefinitionOverriding(beanName, beanDefinition, existingDefinition);
			}
			this.beanDefinitionMap.put(beanName, beanDefinition);
		}
		else {
			if (isAlias(beanName)) {
				String aliasedName = canonicalName(beanName);
				if (!isBeanDefinitionOverridable(aliasedName)) {
					if (containsBeanDefinition(aliasedName)) {  // alias for existing bean definition
						throw new BeanDefinitionOverrideException(
								beanName, beanDefinition, getBeanDefinition(aliasedName));
					}
					else {  // alias pointing to non-existing bean definition
						throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
								"Cannot register bean definition for bean '" + beanName +
								"' since there is already an alias for bean '" + aliasedName + "' bound.");
					}
				}
				else {
					if (logger.isInfoEnabled()) {
						logger.info("Removing alias '" + beanName + "' for bean '" + aliasedName +
								"' due to registration of bean definition for bean '" + beanName + "': [" +
								beanDefinition + "]");
					}
					removeAlias(beanName);
				}
			}
			if (hasBeanCreationStarted()) {
				// Cannot modify startup-time collection elements anymore (for stable iteration)
				synchronized (this.beanDefinitionMap) {
					this.beanDefinitionMap.put(beanName, beanDefinition);
					List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames.size() + 1);
					updatedDefinitions.addAll(this.beanDefinitionNames);
					updatedDefinitions.add(beanName);
					this.beanDefinitionNames = updatedDefinitions;
					removeManualSingletonName(beanName);
				}
			}
			else {
				// Still in startup registration phase
				this.beanDefinitionMap.put(beanName, beanDefinition);
				this.beanDefinitionNames.add(beanName);
				removeManualSingletonName(beanName);
			}
			this.frozenBeanDefinitionNames = null;
		}

		if (existingDefinition != null || containsSingleton(beanName)) {
			resetBeanDefinition(beanName);
		}
		else if (isConfigurationFrozen()) {
			clearByTypeCache();
		}

		// Cache a primary marker for the given bean.
		if (beanDefinition.isPrimary()) {
			this.primaryBeanNames.add(beanName);
		}
	}

	private void logBeanDefinitionOverriding(String beanName, BeanDefinition beanDefinition,
			BeanDefinition existingDefinition) {

		boolean explicitBeanOverride = (this.allowBeanDefinitionOverriding != null);
		if (existingDefinition.getRole() < beanDefinition.getRole()) {
			// for example, was ROLE_APPLICATION, now overriding with ROLE_SUPPORT or ROLE_INFRASTRUCTURE
			if (logger.isInfoEnabled()) {
				logger.info("Overriding user-defined bean definition for bean '" + beanName +
						"' with a framework-generated bean definition: replacing [" +
						existingDefinition + "] with [" + beanDefinition + "]");
			}
		}
		else if (!beanDefinition.equals(existingDefinition)) {
			if (explicitBeanOverride && logger.isInfoEnabled()) {
				logger.info("Overriding bean definition for bean '" + beanName +
						"' with a different definition: replacing [" + existingDefinition +
						"] with [" + beanDefinition + "]");
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Overriding bean definition for bean '" + beanName +
						"' with a different definition: replacing [" + existingDefinition +
						"] with [" + beanDefinition + "]");
			}
		}
		else {
			if (explicitBeanOverride && logger.isInfoEnabled()) {
				logger.info("Overriding bean definition for bean '" + beanName +
						"' with an equivalent definition: replacing [" + existingDefinition +
						"] with [" + beanDefinition + "]");
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Overriding bean definition for bean '" + beanName +
						"' with an equivalent definition: replacing [" + existingDefinition +
						"] with [" + beanDefinition + "]");
			}
		}
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		Assert.hasText(beanName, "'beanName' must not be empty");

		BeanDefinition bd = this.beanDefinitionMap.remove(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}

		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames);
				updatedDefinitions.remove(beanName);
				this.beanDefinitionNames = updatedDefinitions;
			}
		}
		else {
			// Still in startup registration phase
			this.beanDefinitionNames.remove(beanName);
		}
		this.frozenBeanDefinitionNames = null;

		resetBeanDefinition(beanName);
	}

	/**
	 * Reset all bean definition caches for the given bean,
	 * including the caches of beans that are derived from it.
	 * <p>Called after an existing bean definition has been replaced or removed,
	 * triggering {@link #clearMergedBeanDefinition}, {@link #destroySingleton}
	 * and {@link MergedBeanDefinitionPostProcessor#resetBeanDefinition} on the
	 * given bean and on all bean definitions that have the given bean as parent.
	 * @param beanName the name of the bean to reset
	 * @see #registerBeanDefinition
	 * @see #removeBeanDefinition
	 */
	protected void resetBeanDefinition(String beanName) {
		// Remove the merged bean definition for the given bean, if already created.
		clearMergedBeanDefinition(beanName);

		// Remove corresponding bean from singleton cache, if any. Shouldn't usually
		// be necessary, rather just meant for overriding a context's default beans
		// (for example, the default StaticMessageSource in a StaticApplicationContext).
		destroySingleton(beanName);

		// Remove a cached primary marker for the given bean.
		this.primaryBeanNames.remove(beanName);

		// Notify all post-processors that the specified bean definition has been reset.
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.resetBeanDefinition(beanName);
		}

		// Reset all bean definitions that have the given bean as parent (recursively).
		for (String bdName : this.beanDefinitionNames) {
			if (!beanName.equals(bdName)) {
				BeanDefinition bd = this.beanDefinitionMap.get(bdName);
				// Ensure bd is non-null due to potential concurrent modification of beanDefinitionMap.
				if (bd != null && beanName.equals(bd.getParentName())) {
					resetBeanDefinition(bdName);
				}
			}
		}
	}

	/**
	 * This implementation returns {@code true} if bean definition overriding
	 * is generally allowed.
	 * @see #setAllowBeanDefinitionOverriding
	 */
	@Override
	public boolean isBeanDefinitionOverridable(String beanName) {
		return isAllowBeanDefinitionOverriding();
	}

	/**
	 * Only allows alias overriding if bean definition overriding is allowed.
	 * @see #setAllowBeanDefinitionOverriding
	 */
	@Override
	protected boolean allowAliasOverriding() {
		return isAllowBeanDefinitionOverriding();
	}

	/**
	 * Also checks for an alias overriding a bean definition of the same name.
	 */
	@Override
	protected void checkForAliasCircle(String name, String alias) {
		super.checkForAliasCircle(name, alias);
		if (!isBeanDefinitionOverridable(alias) && containsBeanDefinition(alias)) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Alias would override bean definition '" + alias + "'");
		}
	}

	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		super.registerSingleton(beanName, singletonObject);
		updateManualSingletonNames(set -> set.add(beanName), set -> !this.beanDefinitionMap.containsKey(beanName));
		clearByTypeCache();
	}

	@Override
	public void destroySingletons() {
		super.destroySingletons();
		updateManualSingletonNames(Set::clear, set -> !set.isEmpty());
		clearByTypeCache();
	}

	@Override
	public void destroySingleton(String beanName) {
		super.destroySingleton(beanName);
		removeManualSingletonName(beanName);
		clearByTypeCache();
	}

	private void removeManualSingletonName(String beanName) {
		updateManualSingletonNames(set -> set.remove(beanName), set -> set.contains(beanName));
	}

	/**
	 * Update the factory's internal set of manual singleton names.
	 * @param action the modification action
	 * @param condition a precondition for the modification action
	 * (if this condition does not apply, the action can be skipped)
	 */
	private void updateManualSingletonNames(Consumer<Set<String>> action, Predicate<Set<String>> condition) {
		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				if (condition.test(this.manualSingletonNames)) {
					Set<String> updatedSingletons = new LinkedHashSet<>(this.manualSingletonNames);
					action.accept(updatedSingletons);
					this.manualSingletonNames = updatedSingletons;
				}
			}
		}
		else {
			// Still in startup registration phase
			if (condition.test(this.manualSingletonNames)) {
				action.accept(this.manualSingletonNames);
			}
		}
	}

	/**
	 * Remove any assumptions about by-type mappings.
	 */
	private void clearByTypeCache() {
		this.allBeanNamesByType.clear();
		this.singletonBeanNamesByType.clear();
	}


	//---------------------------------------------------------------------
	// Dependency resolution functionality
	//---------------------------------------------------------------------

	@Override
	public <T> NamedBeanHolder<T> resolveNamedBean(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		NamedBeanHolder<T> namedBean = resolveNamedBean(ResolvableType.forRawClass(requiredType), null, false);
		if (namedBean != null) {
			return namedBean;
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof AutowireCapableBeanFactory acbf) {
			return acbf.resolveNamedBean(requiredType);
		}
		throw new NoSuchBeanDefinitionException(requiredType);
	}

	@SuppressWarnings("unchecked")
	private <T> @Nullable NamedBeanHolder<T> resolveNamedBean(
			ResolvableType requiredType, @Nullable Object @Nullable [] args, boolean nonUniqueAsNull) throws BeansException {

		Assert.notNull(requiredType, "Required type must not be null");
		String[] candidateNames = getBeanNamesForType(requiredType);

		if (candidateNames.length > 1) {
			List<String> autowireCandidates = new ArrayList<>(candidateNames.length);
			for (String beanName : candidateNames) {
				if (!containsBeanDefinition(beanName) || getBeanDefinition(beanName).isAutowireCandidate()) {
					autowireCandidates.add(beanName);
				}
			}
			if (!autowireCandidates.isEmpty()) {
				candidateNames = StringUtils.toStringArray(autowireCandidates);
			}
		}

		if (candidateNames.length == 1) {
			return resolveNamedBean(candidateNames[0], requiredType, args);
		}
		else if (candidateNames.length > 1) {
			Map<String, Object> candidates = CollectionUtils.newLinkedHashMap(candidateNames.length);
			for (String beanName : candidateNames) {
				if (containsSingleton(beanName) && args == null) {
					Object beanInstance = resolveBean(beanName, requiredType);
					candidates.put(beanName, (beanInstance instanceof NullBean ? null : beanInstance));
				}
				else {
					candidates.put(beanName, getType(beanName));
				}
			}
			String candidateName = determinePrimaryCandidate(candidates, requiredType.toClass());
			if (candidateName == null) {
				candidateName = determineHighestPriorityCandidate(candidates, requiredType.toClass());
			}
			if (candidateName == null) {
				candidateName = determineDefaultCandidate(candidates);
			}
			if (candidateName != null) {
				Object beanInstance = candidates.get(candidateName);
				if (beanInstance == null) {
					return null;
				}
				if (beanInstance instanceof Class) {
					return resolveNamedBean(candidateName, requiredType, args);
				}
				return new NamedBeanHolder<>(candidateName, (T) beanInstance);
			}
			if (!nonUniqueAsNull) {
				throw new NoUniqueBeanDefinitionException(requiredType, candidates.keySet());
			}
		}

		return null;
	}

	private <T> @Nullable NamedBeanHolder<T> resolveNamedBean(
			String beanName, ResolvableType requiredType, @Nullable Object @Nullable [] args) throws BeansException {

		Object bean = (args != null ? getBean(beanName, args) : resolveBean(beanName, requiredType));
		if (bean instanceof NullBean) {
			return null;
		}
		return new NamedBeanHolder<>(beanName, adaptBeanInstance(beanName, bean, requiredType.toClass()));
	}

	@Override
	public @Nullable Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {

		descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());
		/* 附加注释：初始化参数名称发现器，用于解析依赖描述符中的参数名称，在后续处理中可能需要用到参数名称信息 */

		// 处理特殊依赖类型的情况
		if (Optional.class == descriptor.getDependencyType()) {
			/* 附加注释：处理Java 8 Optional类型的依赖，将依赖包装在Optional中，允许依赖可能不存在 */
			return createOptionalDependency(descriptor, requestingBeanName);
		}
		else if (ObjectFactory.class == descriptor.getDependencyType() ||
				ObjectProvider.class == descriptor.getDependencyType()) {
			/* 附加注释：处理ObjectFactory或ObjectProvider类型的依赖，这些是Spring提供的延迟查找接口，
			   允许在需要时才获取实际bean实例，有助于解决循环依赖问题 */
			return new DependencyObjectProvider(descriptor, requestingBeanName);
		}
		else if (jakartaInjectProviderClass == descriptor.getDependencyType()) {
			/* 附加注释：处理Jakarta Inject Provider类型的依赖，提供对JSR-330标准的支持，
			   使Spring能够与Jakarta依赖注入标准兼容 */
			return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
		}
		else if (descriptor.supportsLazyResolution()) {
			/* 附加注释：检查依赖描述符是否支持延迟解析，例如@Lazy注解的依赖 */
			Object result = getAutowireCandidateResolver().getLazyResolutionProxyIfNecessary(
					descriptor, requestingBeanName);
			/* 附加注释：如果支持延迟解析，尝试创建一个延迟解析代理，这样实际bean只有在首次使用时才会被创建 */
			if (result != null) {
				return result;
			}
		}
		/* 附加注释：如果以上特殊情况都不适用，则执行标准依赖解析过程，处理常规bean依赖 */
		return doResolveDependency(descriptor, requestingBeanName, autowiredBeanNames, typeConverter);
	}

	@SuppressWarnings("NullAway") // Dataflow analysis limitation
	// 数据流分析限制
	public @Nullable Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {

		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		/* 附加注释：保存当前注入点并设置新的注入点，用于在依赖解析过程中跟踪当前正在处理的依赖项，便于错误处理和调试 */
		try {
			// Step 1: pre-resolved shortcut for single bean match, for example, from @Autowired
			// 步骤1：预解析的单个bean匹配的快捷方式，例如来自@Autowired注解
			Object shortcut = descriptor.resolveShortcut(this);
			/* 附加注释：尝试获取依赖描述符的快捷解析结果，某些自定义依赖描述符可能已经预先解析了依赖关系 */
			if (shortcut != null) {
				return shortcut;
			}

			Class<?> type = descriptor.getDependencyType();
			/* 附加注释：获取需要注入的依赖类型，这是解析过程中最基本的信息 */

			// Step 2: pre-defined value or expression, for example, from @Value
			// 步骤2：预定义的值或表达式，例如来自@Value注解
			Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
			/* 附加注释：检查是否有建议值，通常来自@Value注解或其他值提供机制 */
			if (value != null) {
				if (value instanceof String strValue) {
					String resolvedValue = resolveEmbeddedValue(strValue);
					/* 附加注释：解析字符串中的占位符，如${property}形式的属性占位符 */
					BeanDefinition bd = (beanName != null && containsBean(beanName) ?
							getMergedBeanDefinition(beanName) : null);
					/* 附加注释：获取当前bean的合并定义，用于后续表达式评估的上下文 */
					value = evaluateBeanDefinitionString(resolvedValue, bd);
					/* 附加注释：评估字符串表达式，例如SpEL表达式，将其转换为实际值 */
				}
				TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
				/* 附加注释：获取类型转换器，优先使用传入的转换器，否则使用容器默认的转换器 */
				try {
					return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
					/* 附加注释：尝试将值转换为所需类型，使用TypeDescriptor提供更详细的类型信息 */
				}
				catch (UnsupportedOperationException ex) {
					// A custom TypeConverter which does not support TypeDescriptor resolution...
					// 自定义TypeConverter不支持TypeDescriptor解析...
					return (descriptor.getField() != null ?
							converter.convertIfNecessary(value, type, descriptor.getField()) :
							converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
					/* 附加注释：当TypeDescriptor不被支持时的降级处理，分别尝试使用Field或MethodParameter进行转换 */
				}
			}

			// Step 3: shortcut for declared dependency name or qualifier-suggested name matching target bean name
			// 步骤3：声明的依赖名称或限定符建议的名称匹配目标bean名称的快捷方式
			if (descriptor.usesStandardBeanLookup()) {
				/* 附加注释：检查是否使用标准bean查找，即是否可以通过名称直接查找bean */
				String dependencyName = descriptor.getDependencyName();
				/* 附加注释：获取依赖名称，可能来自@Qualifier注解或字段/参数名称 */
				if (dependencyName == null || !containsBean(dependencyName)) {
					String suggestedName = getAutowireCandidateResolver().getSuggestedName(descriptor);
					/* 附加注释：当直接指定的名称不存在时，尝试获取自动装配候选解析器建议的名称 */
					dependencyName = (suggestedName != null && containsBean(suggestedName) ? suggestedName : null);
				}
				if (dependencyName != null) {
					dependencyName = canonicalName(dependencyName);  // dependency name can be alias of target name
					/* 附加注释：获取规范名称，因为依赖名称可能是目标bean的别名 */
					if (isTypeMatch(dependencyName, type) && isAutowireCandidate(dependencyName, descriptor) &&
							!isFallback(dependencyName) && !hasPrimaryConflict(dependencyName, type) &&
							!isSelfReference(beanName, dependencyName)) {
						/* 附加注释：进行多重检查确保bean是合适的候选者：
						   1. 类型匹配
						   2. 是自动装配候选者
						   3. 不是后备bean
						   4. 没有主要冲突
						   5. 不是自引用 */
						if (autowiredBeanNames != null) {
							autowiredBeanNames.add(dependencyName);
							/* 附加注释：记录已自动装配的bean名称，用于后续处理和调试 */
						}
						Object dependencyBean = resolveBean(dependencyName, descriptor.getResolvableType());
						/* 附加注释：解析指定名称的bean，使用ResolvableType提供更精确的类型信息 */
						return resolveInstance(dependencyBean, descriptor, type, dependencyName);
						/* 附加注释：处理解析结果，包括类型检查和必要的错误处理 */
					}
				}
			}

			// Step 4a: multiple beans as stream / array / standard collection / plain map
			// 步骤4a：多个bean作为流/数组/标准集合/普通映射
			Object multipleBeans = resolveMultipleBeans(descriptor, beanName, autowiredBeanNames, typeConverter);
			/* 附加注释：尝试解析多个bean的情况，处理Stream、数组、Collection或Map类型的依赖 */
			if (multipleBeans != null) {
				return multipleBeans;
			}
			// Step 4b: direct bean matches, possibly direct beans of type Collection / Map
			// 步骤4b：直接bean匹配，可能是Collection/Map类型的直接bean
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			/* 附加注释：查找所有匹配依赖类型的候选bean，返回名称到实例或类的映射 */
			if (matchingBeans.isEmpty()) {
				// Step 4c (fallback): custom Collection / Map declarations for collecting multiple beans
				// 步骤4c（后备）：用于收集多个bean的自定义Collection/Map声明
				multipleBeans = resolveMultipleBeansFallback(descriptor, beanName, autowiredBeanNames, typeConverter);
				/* 附加注释：当没有找到直接匹配时，尝试后备策略解析集合类型的依赖 */
				if (multipleBeans != null) {
					return multipleBeans;
				}
				// Raise exception if nothing found for required injection point
				// 如果没有为必需的注入点找到任何内容，则引发异常
				if (isRequired(descriptor)) {
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
					/* 附加注释：对于必需的依赖，如果没有找到匹配项则抛出异常 */
				}
				return null;
			}

			String autowiredBeanName;
			Object instanceCandidate;

			// Step 5: determine single candidate
			// 步骤5：确定单个候选者
			if (matchingBeans.size() > 1) {
				/* 附加注释：当找到多个匹配的bean时，需要确定使用哪一个 */
				autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
				/* 附加注释：根据优先级、限定符等因素确定最佳候选bean */
				if (autowiredBeanName == null) {
					if (isRequired(descriptor) || !indicatesArrayCollectionOrMap(type)) {
						// Raise exception if no clear match found for required injection point
						// 如果没有为必需的注入点找到明确匹配，则引发异常
						return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);
						/* 附加注释：当无法确定唯一候选者且依赖是必需的或不是集合类型时，让描述符处理非唯一情况 */
					}
					else {
						// In case of an optional Collection/Map, silently ignore a non-unique case:
						// possibly it was meant to be an empty collection of multiple regular beans
						// (before 4.3 in particular when we didn't even look for collection beans).
						// 对于可选的Collection/Map，静默忽略非唯一情况：
						// 可能它本来是多个常规bean的空集合
						// （特别是在4.3之前，当我们甚至不查找集合bean时）
						return null;
						/* 附加注释：对于可选的集合类型依赖，当有多个候选者但无法确定时，返回null表示空集合 */
					}
				}
				instanceCandidate = matchingBeans.get(autowiredBeanName);
				/* 附加注释：获取选定的bean实例或类 */
			}
			else {
				// We have exactly one match.
				// 我们恰好有一个匹配项。
				Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
				/* 附加注释：当只有一个匹配项时，直接获取该项 */
				autowiredBeanName = entry.getKey();
				instanceCandidate = entry.getValue();
			}

			// Step 6: validate single result
			// 步骤6：验证单个结果
			if (autowiredBeanNames != null) {
				autowiredBeanNames.add(autowiredBeanName);
				/* 附加注释：记录已自动装配的bean名称 */
			}
			if (instanceCandidate instanceof Class) {
				instanceCandidate = descriptor.resolveCandidate(autowiredBeanName, type, this);
				/* 附加注释：如果候选者是Class类型，则需要实例化该bean */
			}
			return resolveInstance(instanceCandidate, descriptor, type, autowiredBeanName);
			/* 附加注释：最终处理实例，包括类型检查和必要的错误处理 */
		}
		finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
			/* 附加注释：恢复之前的注入点，确保线程安全和正确的上下文管理 */
		}
	}

	private @Nullable Object resolveInstance(Object candidate, DependencyDescriptor descriptor, Class<?> type, String name) {
		Object result = candidate;
		if (result instanceof NullBean) {
			// Raise exception if null encountered for required injection point
			if (isRequired(descriptor)) {
				raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
			}
			result = null;
		}
		if (!ClassUtils.isAssignableValue(type, result)) {
			throw new BeanNotOfRequiredTypeException(name, type, candidate.getClass());
		}
		return result;
	}

	private @Nullable Object resolveMultipleBeans(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {

		Class<?> type = descriptor.getDependencyType();
		/* 附加注释：获取依赖的类型，用于后续判断如何处理不同类型的依赖注入 */

		if (descriptor instanceof StreamDependencyDescriptor streamDependencyDescriptor) {
			// Handle Stream dependencies
			// 处理Stream依赖
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			/* 附加注释：查找所有匹配Stream类型依赖的候选bean，返回名称到实例的映射 */
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
				/* 附加注释：记录所有自动装配的bean名称，用于后续跟踪和调试 */
			}
			Stream<Object> stream = matchingBeans.keySet().stream()
					.map(name -> descriptor.resolveCandidate(name, type, this))
					/* 附加注释：将bean名称转换为实际的bean实例 */
					.filter(bean -> !(bean instanceof NullBean));
					/* 附加注释：过滤掉NullBean实例，确保流中只包含有效的bean */
			if (streamDependencyDescriptor.isOrdered()) {
				// Sort stream elements according to dependency comparator
				// 根据依赖比较器对流元素进行排序
				stream = stream.sorted(adaptOrderComparator(matchingBeans));
				/* 附加注释：当需要有序Stream时，使用OrderComparator对流中的元素进行排序，保证注入的顺序性 */
			}
			return stream;
		}
		else if (type.isArray()) {
			// Handle array dependencies
			// 处理数组依赖
			Class<?> componentType = type.componentType();
			/* 附加注释：获取数组的组件类型，用于查找匹配的bean */
			ResolvableType resolvableType = descriptor.getResolvableType();
			/* 附加注释：获取可解析类型，包含泛型信息，用于更精确地解析依赖类型 */
			Class<?> resolvedArrayType = resolvableType.resolve(type);
			/* 附加注释：解析实际的数组类型，处理可能存在的类型变量 */
			if (resolvedArrayType != type) {
				componentType = resolvableType.getComponentType().resolve();
				/* 附加注释：当解析后的数组类型与原始类型不同时，需要重新获取组件类型，确保类型匹配正确 */
			}
			if (componentType == null) {
				return null;
				/* 附加注释：如果无法确定组件类型，则无法进行注入，返回null */
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, componentType,
					new MultiElementDescriptor(descriptor));
			/* 附加注释：使用MultiElementDescriptor查找匹配组件类型的候选bean，适用于集合元素的查找 */
			if (matchingBeans.isEmpty()) {
				return null;
				/* 附加注释：如果没有找到匹配的bean，则返回null，表示无法注入 */
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
				/* 附加注释：记录所有自动装配的bean名称 */
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			/* 附加注释：获取类型转换器，用于将bean集合转换为数组类型 */
			Object result = converter.convertIfNecessary(matchingBeans.values(), resolvedArrayType);
			/* 附加注释：将匹配的bean值集合转换为所需的数组类型 */
			if (result instanceof Object[] array && array.length > 1) {
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				/* 附加注释：获取适配后的依赖比较器，用于排序数组元素 */
				if (comparator != null) {
					Arrays.sort(array, comparator);
					/* 附加注释：当数组有多个元素时，根据比较器对数组进行排序，确保注入顺序的一致性 */
				}
			}
			return result;
		}
		else if (Collection.class == type || Set.class == type || List.class == type) {
			// Handle Collection dependencies
			// 处理Collection依赖
			return resolveMultipleBeanCollection(descriptor, beanName, autowiredBeanNames, typeConverter);
			/* 附加注释：对于Collection、Set或List类型的依赖，委托给专门的方法处理，实现集合类型的依赖注入 */
		}
		else if (Map.class == type) {
			// Handle Map dependencies
			// 处理Map依赖
			return resolveMultipleBeanMap(descriptor, beanName, autowiredBeanNames, typeConverter);
			/* 附加注释：对于Map类型的依赖，委托给专门的方法处理，实现键值对映射的依赖注入 */
		}
		return null;
		/* 附加注释：如果依赖类型不是Stream、数组、Collection或Map，则无法处理多个bean的情况，返回null */
	}


	private @Nullable Object resolveMultipleBeansFallback(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {

		Class<?> type = descriptor.getDependencyType();

		if (Collection.class.isAssignableFrom(type) && type.isInterface()) {
			return resolveMultipleBeanCollection(descriptor, beanName, autowiredBeanNames, typeConverter);
		}
		else if (Map.class.isAssignableFrom(type) && type.isInterface()) {
			return resolveMultipleBeanMap(descriptor, beanName, autowiredBeanNames, typeConverter);
		}
		return null;
	}

	private @Nullable Object resolveMultipleBeanCollection(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {

		Class<?> elementType = descriptor.getResolvableType().asCollection().resolveGeneric();
		if (elementType == null) {
			return null;
		}
		Map<String, Object> matchingBeans = findAutowireCandidates(beanName, elementType,
				new MultiElementDescriptor(descriptor));
		if (matchingBeans.isEmpty()) {
			return null;
		}
		if (autowiredBeanNames != null) {
			autowiredBeanNames.addAll(matchingBeans.keySet());
		}
		TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
		Object result = converter.convertIfNecessary(matchingBeans.values(), descriptor.getDependencyType());
		if (result instanceof List<?> list && list.size() > 1) {
			Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
			if (comparator != null) {
				list.sort(comparator);
			}
		}
		return result;
	}

	private @Nullable Object resolveMultipleBeanMap(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {

		ResolvableType mapType = descriptor.getResolvableType().asMap();
		Class<?> keyType = mapType.resolveGeneric(0);
		if (String.class != keyType) {
			return null;
		}
		Class<?> valueType = mapType.resolveGeneric(1);
		if (valueType == null) {
			return null;
		}
		Map<String, Object> matchingBeans = findAutowireCandidates(beanName, valueType,
				new MultiElementDescriptor(descriptor));
		if (matchingBeans.isEmpty()) {
			return null;
		}
		if (autowiredBeanNames != null) {
			autowiredBeanNames.addAll(matchingBeans.keySet());
		}
		TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
		return converter.convertIfNecessary(matchingBeans, descriptor.getDependencyType());
	}

	private boolean indicatesArrayCollectionOrMap(Class<?> type) {
		return (type.isArray() || (type.isInterface() &&
				(Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))));
	}

	private boolean isRequired(DependencyDescriptor descriptor) {
		return getAutowireCandidateResolver().isRequired(descriptor);
	}

	private @Nullable Comparator<Object> adaptDependencyComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator orderComparator) {
			return orderComparator.withSourceProvider(
					createFactoryAwareOrderSourceProvider(matchingBeans));
		}
		else {
			return comparator;
		}
	}

	private Comparator<Object> adaptOrderComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> dependencyComparator = getDependencyComparator();
		OrderComparator comparator = (dependencyComparator instanceof OrderComparator orderComparator ?
				orderComparator : OrderComparator.INSTANCE);
		return comparator.withSourceProvider(createFactoryAwareOrderSourceProvider(matchingBeans));
	}

	private OrderComparator.OrderSourceProvider createFactoryAwareOrderSourceProvider(Map<String, ?> beans) {
		IdentityHashMap<Object, String> instancesToBeanNames = new IdentityHashMap<>();
		beans.forEach((beanName, instance) -> instancesToBeanNames.put(instance, beanName));
		return new FactoryAwareOrderSourceProvider(instancesToBeanNames);
	}

	/**
	 * Find bean instances that match the required type.
	 * Called during autowiring for the specified bean.
	 * @param beanName the name of the bean that is about to be wired
	 * @param requiredType the actual type of bean to look for
	 * (may be an array component type or collection element type)
	 * @param descriptor the descriptor of the dependency to resolve
	 * @return a Map of candidate names and candidate instances that match
	 * the required type (never {@code null})
	 * @throws BeansException in case of errors
	 * @see #autowireByType
	 * @see #autowireConstructor
	 *
	 * 查找匹配所需类型的bean实例。
	 * 在为指定bean进行自动装配时调用。
	 * @param beanName 即将被装配的bean的名称
	 * @param requiredType 要查找的bean的实际类型
	 * (可能是数组组件类型或集合元素类型)
	 * @param descriptor 要解析的依赖描述符
	 * @return 包含匹配所需类型的候选名称和候选实例的Map(永不为{@code null})
	 * @throws BeansException 如果出现错误
	 * @see #autowireByType
	 * @see #autowireConstructor
	 */
	protected Map<String, Object> findAutowireCandidates(
			@Nullable String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {

		/* 附加注释：获取所有匹配requiredType类型的bean名称，包括当前BeanFactory及其祖先BeanFactory中的bean，
		   isEager参数决定是否急切初始化FactoryBeans，用于类型检查 */
		String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				this, requiredType, true, descriptor.isEager());
		/* 附加注释：创建一个LinkedHashMap来存储候选bean，大小为candidateNames的长度，保持插入顺序 */
		Map<String, Object> result = CollectionUtils.newLinkedHashMap(candidateNames.length);

		/* 附加注释：首先检查已解析的依赖项(resolvableDependencies)，这些是直接注册的特殊依赖，如BeanFactory本身 */
		for (Map.Entry<Class<?>, Object> classObjectEntry : this.resolvableDependencies.entrySet()) {
			Class<?> autowiringType = classObjectEntry.getKey();
			if (autowiringType.isAssignableFrom(requiredType)) {
				Object autowiringValue = classObjectEntry.getValue();
				/* 附加注释：解析自动装配值，处理ObjectFactory或FactoryBean等特殊情况 */
				autowiringValue = AutowireUtils.resolveAutowiringValue(autowiringValue, requiredType);
				if (requiredType.isInstance(autowiringValue)) {
					/* 附加注释：如果解析后的值是所需类型的实例，则添加到结果中，使用对象的身份字符串作为键 */
					result.put(ObjectUtils.identityToString(autowiringValue), autowiringValue);
					break;
				}
			}
		}

		/* 附加注释：遍历所有候选bean名称，检查非自引用且是自动装配候选者的bean */
		for (String candidate : candidateNames) {
			if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, descriptor)) {
				/* 附加注释：将符合条件的候选bean添加到结果Map中，可能是bean实例或仅是类型信息 */
				addCandidateEntry(result, candidate, descriptor, requiredType);
			}
		}

		/* 附加注释：如果第一轮没有找到任何匹配项，考虑回退匹配策略 */
		if (result.isEmpty()) {
			/* 附加注释：检查所需类型是否表示数组、集合或Map，这些类型可以接受多个bean */
			boolean multiple = indicatesArrayCollectionOrMap(requiredType);
			/* 附加注释：创建一个用于回退匹配的描述符，放宽匹配条件 */
			DependencyDescriptor fallbackDescriptor = descriptor.forFallbackMatch();

			/* 附加注释：使用回退描述符再次尝试查找候选bean */
			for (String candidate : candidateNames) {
				if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, fallbackDescriptor) &&
						(!multiple || getAutowireCandidateResolver().hasQualifier(descriptor))) {
					/* 附加注释：对于集合类型，只有当描述符有限定符时才添加候选项 */
					addCandidateEntry(result, candidate, descriptor, requiredType);
				}
			}

			/* 附加注释：如果仍然没有找到匹配项且不是集合类型，考虑自引用作为最后的尝试 */
			if (result.isEmpty() && !multiple) {
				for (String candidate : candidateNames) {
					if (isSelfReference(beanName, candidate) &&
							(!(descriptor instanceof MultiElementDescriptor) || !beanName.equals(candidate)) &&
							isAutowireCandidate(candidate, fallbackDescriptor)) {
						/* 附加注释：对于MultiElementDescriptor(集合元素)，不考虑与beanName完全相同的候选项，避免循环依赖 */
						addCandidateEntry(result, candidate, descriptor, requiredType);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Add an entry to the candidate map: a bean instance if available or just the resolved
	 * type, preventing early bean initialization ahead of primary candidate selection.
	 */
	private void addCandidateEntry(Map<String, Object> candidates, String candidateName,
			DependencyDescriptor descriptor, Class<?> requiredType) {

		if (descriptor instanceof MultiElementDescriptor) {
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			if (!(beanInstance instanceof NullBean)) {
				candidates.put(candidateName, beanInstance);
			}
		}
		else if (containsSingleton(candidateName) ||
				(descriptor instanceof StreamDependencyDescriptor streamDescriptor && streamDescriptor.isOrdered())) {
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			candidates.put(candidateName, beanInstance);
		}
		else {
			candidates.put(candidateName, getType(candidateName));
		}
	}

	/**
	 * Determine the autowire candidate in the given set of beans.
	 * <p>Looks for {@code @Primary} and {@code @Priority} (in that order).
	 * @param candidates a Map of candidate names and candidate instances
	 * that match the required type, as returned by {@link #findAutowireCandidates}
	 * @param descriptor the target dependency to match against
	 * @return the name of the autowire candidate, or {@code null} if none found
	 */
	protected @Nullable String determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor descriptor) {
		Class<?> requiredType = descriptor.getDependencyType();
		// Step 1: check primary candidate
		String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
		if (primaryCandidate != null) {
			return primaryCandidate;
		}
		// Step 2a: match bean name against declared dependency name
		String dependencyName = descriptor.getDependencyName();
		if (dependencyName != null) {
			for (String beanName : candidates.keySet()) {
				if (matchesBeanName(beanName, dependencyName)) {
					return beanName;
				}
			}
		}
		// Step 2b: match bean name against qualifier-suggested name
		String suggestedName = getAutowireCandidateResolver().getSuggestedName(descriptor);
		if (suggestedName != null) {
			for (String beanName : candidates.keySet()) {
				if (matchesBeanName(beanName, suggestedName)) {
					return beanName;
				}
			}
		}
		// Step 3: check highest priority candidate
		String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
		if (priorityCandidate != null) {
			return priorityCandidate;
		}
		// Step 4: pick unique default-candidate
		String defaultCandidate = determineDefaultCandidate(candidates);
		if (defaultCandidate != null) {
			return defaultCandidate;
		}
		// Step 5: pick directly registered dependency
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (beanInstance != null && this.resolvableDependencies.containsValue(beanInstance)) {
				return candidateName;
			}
		}
		return null;
	}

	/**
	 * Determine the primary candidate in the given set of beans.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the primary candidate, or {@code null} if none found
	 * @see #isPrimary(String, Object)
	 */
	protected @Nullable String determinePrimaryCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String primaryBeanName = null;
		// First pass: identify unique primary candidate
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (isPrimary(candidateBeanName, beanInstance)) {
				if (primaryBeanName != null) {
					boolean candidateLocal = containsBeanDefinition(candidateBeanName);
					boolean primaryLocal = containsBeanDefinition(primaryBeanName);
					if (candidateLocal == primaryLocal) {
						throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
								"more than one 'primary' bean found among candidates: " + candidates.keySet());
					}
					else if (candidateLocal) {
						primaryBeanName = candidateBeanName;
					}
				}
				else {
					primaryBeanName = candidateBeanName;
				}
			}
		}
		// Second pass: identify unique non-fallback candidate
		if (primaryBeanName == null) {
			for (String candidateBeanName : candidates.keySet()) {
				if (!isFallback(candidateBeanName)) {
					if (primaryBeanName != null) {
						return null;
					}
					primaryBeanName = candidateBeanName;
				}
			}
		}
		return primaryBeanName;
	}

	/**
	 * Determine the candidate with the highest priority in the given set of beans.
	 * <p>Based on {@code @jakarta.annotation.Priority}. As defined by the related
	 * {@link org.springframework.core.Ordered} interface, the lowest value has
	 * the highest priority.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the candidate with the highest priority,
	 * or {@code null} if none found
	 * @throws NoUniqueBeanDefinitionException if multiple beans are detected with
	 * the same highest priority value
	 * @see #getPriority(Object)
	 */
	protected @Nullable String determineHighestPriorityCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String highestPriorityBeanName = null;
		Integer highestPriority = null;
		boolean highestPriorityConflictDetected = false;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (beanInstance != null) {
				Integer candidatePriority = getPriority(beanInstance);
				if (candidatePriority != null) {
					if (highestPriority != null) {
						if (candidatePriority.equals(highestPriority)) {
							highestPriorityConflictDetected = true;
						}
						else if (candidatePriority < highestPriority) {
							highestPriorityBeanName = candidateBeanName;
							highestPriority = candidatePriority;
							highestPriorityConflictDetected = false;
						}
					}
					else {
						highestPriorityBeanName = candidateBeanName;
						highestPriority = candidatePriority;
					}
				}
			}
		}

		if (highestPriorityConflictDetected) {
			throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
					"Multiple beans found with the same highest priority (" + highestPriority +
					") among candidates: " + candidates.keySet());

		}
		return highestPriorityBeanName;
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a primary bean.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance (can be {@code null})
	 * @return whether the given bean qualifies as primary
	 */
	protected boolean isPrimary(String beanName, Object beanInstance) {
		String transformedBeanName = transformedBeanName(beanName);
		if (containsBeanDefinition(transformedBeanName)) {
			return getMergedLocalBeanDefinition(transformedBeanName).isPrimary();
		}
		return (getParentBeanFactory() instanceof DefaultListableBeanFactory parent &&
				parent.isPrimary(transformedBeanName, beanInstance));
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a fallback bean.
	 * @param beanName the name of the bean
	 * @since 6.2
	 */
	private boolean isFallback(String beanName) {
		String transformedBeanName = transformedBeanName(beanName);
		if (containsBeanDefinition(transformedBeanName)) {
			return getMergedLocalBeanDefinition(transformedBeanName).isFallback();
		}
		return (getParentBeanFactory() instanceof DefaultListableBeanFactory parent &&
				parent.isFallback(transformedBeanName));
	}

	/**
	 * Return the priority assigned for the given bean instance by
	 * the {@code jakarta.annotation.Priority} annotation.
	 * <p>The default implementation delegates to the specified
	 * {@link #setDependencyComparator dependency comparator}, checking its
	 * {@link OrderComparator#getPriority method} if it is an extension of
	 * Spring's common {@link OrderComparator} - typically, an
	 * {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}.
	 * If no such comparator is present, this implementation returns {@code null}.
	 * @param beanInstance the bean instance to check (can be {@code null})
	 * @return the priority assigned to that bean or {@code null} if none is set
	 */
	protected @Nullable Integer getPriority(Object beanInstance) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator orderComparator) {
			return orderComparator.getPriority(beanInstance);
		}
		return null;
	}

	/**
	 * Return a unique "default-candidate" among remaining non-default candidates.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type
	 * @return the name of the default candidate, or {@code null} if none found
	 * @since 6.2.4
	 * @see AbstractBeanDefinition#isDefaultCandidate()
	 */
	@Nullable
	private String determineDefaultCandidate(Map<String, Object> candidates) {
		String defaultBeanName = null;
		for (String candidateBeanName : candidates.keySet()) {
			if (AutowireUtils.isDefaultCandidate(this, candidateBeanName)) {
				if (defaultBeanName != null) {
					return null;
				}
				defaultBeanName = candidateBeanName;
			}
		}
		return defaultBeanName;
	}

	/**
	 * Determine whether the given candidate name matches the bean name or the aliases
	 * stored in this bean definition.
	 */
	protected boolean matchesBeanName(String beanName, @Nullable String candidateName) {
		return (candidateName != null &&
				(candidateName.equals(beanName) || ObjectUtils.containsElement(getAliases(beanName), candidateName)));
	}

	/**
	 * Determine whether the given beanName/candidateName pair indicates a self reference,
	 * i.e. whether the candidate points back to the original bean or to a factory method
	 * on the original bean.
	 */
	@Contract("null, _ -> false; _, null -> false;")
	private boolean isSelfReference(@Nullable String beanName, @Nullable String candidateName) {
		return (beanName != null && candidateName != null &&
				(beanName.equals(candidateName) || (containsBeanDefinition(candidateName) &&
						beanName.equals(getMergedLocalBeanDefinition(candidateName).getFactoryBeanName()))));
	}

	/**
	 * Determine whether there is a primary bean registered for the given dependency type,
	 * not matching the given bean name.
	 */
	private boolean hasPrimaryConflict(String beanName, Class<?> dependencyType) {
		for (String candidate : this.primaryBeanNames) {
			if (isTypeMatch(candidate, dependencyType) && !candidate.equals(beanName)) {
				return true;
			}
		}
		return (getParentBeanFactory() instanceof DefaultListableBeanFactory parent &&
				parent.hasPrimaryConflict(beanName, dependencyType));
	}

	/**
	 * Raise a NoSuchBeanDefinitionException or BeanNotOfRequiredTypeException
	 * for an unresolvable dependency.
	 */
	private void raiseNoMatchingBeanFound(
			Class<?> type, ResolvableType resolvableType, DependencyDescriptor descriptor) throws BeansException {

		checkBeanNotOfRequiredType(type, descriptor);

		throw new NoSuchBeanDefinitionException(resolvableType,
				"expected at least 1 bean which qualifies as autowire candidate. " +
				"Dependency annotations: " + ObjectUtils.nullSafeToString(descriptor.getAnnotations()));
	}

	/**
	 * Raise a BeanNotOfRequiredTypeException for an unresolvable dependency, if applicable,
	 * i.e. if the target type of the bean would match but an exposed proxy doesn't.
	 */
	private void checkBeanNotOfRequiredType(Class<?> type, DependencyDescriptor descriptor) {
		for (String beanName : this.beanDefinitionNames) {
			try {
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				Class<?> targetType = mbd.getTargetType();
				if (targetType != null && type.isAssignableFrom(targetType) &&
						isAutowireCandidate(beanName, mbd, descriptor, getAutowireCandidateResolver())) {
					// Probably a proxy interfering with target type match -> throw meaningful exception.
					Object beanInstance = getSingleton(beanName, false);
					Class<?> beanType = (beanInstance != null && beanInstance.getClass() != NullBean.class ?
							beanInstance.getClass() : predictBeanType(beanName, mbd));
					if (beanType != null && !type.isAssignableFrom(beanType)) {
						throw new BeanNotOfRequiredTypeException(beanName, type, beanType);
					}
				}
			}
			catch (NoSuchBeanDefinitionException ex) {
				// Bean definition got removed while we were iterating -> ignore.
			}
		}

		if (getParentBeanFactory() instanceof DefaultListableBeanFactory parent) {
			parent.checkBeanNotOfRequiredType(type, descriptor);
		}
	}

	/**
	 * Create an {@link Optional} wrapper for the specified dependency.
	 */
	private Optional<?> createOptionalDependency(
			DependencyDescriptor descriptor, @Nullable String beanName, final @Nullable Object... args) {

		DependencyDescriptor descriptorToUse = new NestedDependencyDescriptor(descriptor) {
			@Override
			public boolean isRequired() {
				return false;
			}
			@Override
			public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
				return (!ObjectUtils.isEmpty(args) ? beanFactory.getBean(beanName, args) :
						super.resolveCandidate(beanName, requiredType, beanFactory));
			}
			@Override
			public boolean usesStandardBeanLookup() {
				return ObjectUtils.isEmpty(args);
			}
		};
		Object result = doResolveDependency(descriptorToUse, beanName, null, null);
		return (result instanceof Optional<?> optional ? optional : Optional.ofNullable(result));
	}

	/**
	 * Public method to determine the applicable order value for a given bean.
	 * <p>This variant implicitly obtains a corresponding bean instance from this factory.
	 * @param beanName the name of the bean
	 * @return the corresponding order value (default is {@link Ordered#LOWEST_PRECEDENCE})
	 * @since 7.0
	 * @see #getOrder(String, Object)
	 */
	public int getOrder(String beanName) {
		return getOrder(beanName, getBean(beanName));
	}

	/**
	 * Public method to determine the applicable order value for a given bean.
	 * @param beanName the name of the bean
	 * @param beanInstance the bean instance to check
	 * @return the corresponding order value (default is {@link Ordered#LOWEST_PRECEDENCE})
	 * @since 7.0
	 * @see #getOrder(String)
	 */
	public int getOrder(String beanName, Object beanInstance) {
		OrderComparator comparator = (getDependencyComparator() instanceof OrderComparator orderComparator ?
				orderComparator : OrderComparator.INSTANCE);
		return comparator.getOrder(beanInstance,
				new FactoryAwareOrderSourceProvider(Collections.singletonMap(beanInstance, beanName)));
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(ObjectUtils.identityToString(this));
		sb.append(": defining beans [");
		sb.append(StringUtils.collectionToCommaDelimitedString(this.beanDefinitionNames));
		sb.append("]; ");
		BeanFactory parent = getParentBeanFactory();
		if (parent == null) {
			sb.append("root of factory hierarchy");
		}
		else {
			sb.append("parent: ").append(ObjectUtils.identityToString(parent));
		}
		return sb.toString();
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	@Serial
	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("DefaultListableBeanFactory itself is not deserializable - " +
				"just a SerializedBeanFactoryReference is");
	}

	@Serial
	protected Object writeReplace() throws ObjectStreamException {
		if (this.serializationId != null) {
			return new SerializedBeanFactoryReference(this.serializationId);
		}
		else {
			throw new NotSerializableException("DefaultListableBeanFactory has no serialization id");
		}
	}


	/**
	 * Minimal id reference to the factory.
	 * Resolved to the actual factory instance on deserialization.
	 */
	private static class SerializedBeanFactoryReference implements Serializable {

		private final String id;

		public SerializedBeanFactoryReference(String id) {
			this.id = id;
		}

		private Object readResolve() {
			Reference<?> ref = serializableFactories.get(this.id);
			if (ref != null) {
				Object result = ref.get();
				if (result != null) {
					return result;
				}
			}
			// Lenient fallback: dummy factory in case of original factory not found...
			DefaultListableBeanFactory dummyFactory = new DefaultListableBeanFactory();
			dummyFactory.serializationId = this.id;
			return dummyFactory;
		}
	}


	/**
	 * A dependency descriptor marker for nested elements.
	 */
	private static class NestedDependencyDescriptor extends DependencyDescriptor {

		public NestedDependencyDescriptor(DependencyDescriptor original) {
			super(original);
			increaseNestingLevel();
		}

		@Override
		public boolean usesStandardBeanLookup() {
			return true;
		}
	}


	/**
	 * A dependency descriptor for a multi-element declaration with nested elements.
	 */
	private static class MultiElementDescriptor extends NestedDependencyDescriptor {

		public MultiElementDescriptor(DependencyDescriptor original) {
			super(original);
		}
	}


	/**
	 * A dependency descriptor marker for stream access to multiple elements.
	 */
	private static class StreamDependencyDescriptor extends DependencyDescriptor {

		private final boolean ordered;

		public StreamDependencyDescriptor(DependencyDescriptor original, boolean ordered) {
			super(original);
			this.ordered = ordered;
		}

		public boolean isOrdered() {
			return this.ordered;
		}
	}


	private interface BeanObjectProvider<T> extends ObjectProvider<T>, Serializable {
	}


	/**
	 * Serializable ObjectFactory/ObjectProvider for lazy resolution of a dependency.
	 */
	private class DependencyObjectProvider implements BeanObjectProvider<Object> {

		private final DependencyDescriptor descriptor;

		private final boolean optional;

		private final @Nullable String beanName;

		public DependencyObjectProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			this.descriptor = new NestedDependencyDescriptor(descriptor);
			this.optional = (this.descriptor.getDependencyType() == Optional.class);
			this.beanName = beanName;
		}

		@Override
		public Object getObject() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				Object result = doResolveDependency(this.descriptor, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		public Object getObject(final @Nullable Object... args) throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName, args);
			}
			else {
				DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
					@Override
					public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
						return beanFactory.getBean(beanName, args);
					}
				};
				Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		public @Nullable Object getIfAvailable() throws BeansException {
			try {
				if (this.optional) {
					return createOptionalDependency(this.descriptor, this.beanName);
				}
				else {
					DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
						@Override
						public boolean isRequired() {
							return false;
						}
						@Override
						public boolean usesStandardBeanLookup() {
							return true;
						}
					};
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifAvailable(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfAvailable();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Override
		public @Nullable Object getIfUnique() throws BeansException {
			DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
				@Override
				public boolean isRequired() {
					return false;
				}
				@Override
				public boolean usesStandardBeanLookup() {
					return true;
				}
				@Override
				public @Nullable Object resolveNotUnique(ResolvableType type, Map<String, Object> matchingBeans) {
					return null;
				}
			};
			try {
				if (this.optional) {
					return createOptionalDependency(descriptorToUse, this.beanName);
				}
				else {
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifUnique(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfUnique();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		protected @Nullable Object getValue() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				return doResolveDependency(this.descriptor, this.beanName, null, null);
			}
		}

		@Override
		public Stream<Object> stream() {
			return resolveStream(false);
		}

		@Override
		public Stream<Object> orderedStream() {
			return resolveStream(true);
		}

		@SuppressWarnings({"rawtypes", "unchecked"})
		private Stream<Object> resolveStream(boolean ordered) {
			DependencyDescriptor descriptorToUse = new StreamDependencyDescriptor(this.descriptor, ordered);
			Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
			return (result instanceof Stream stream ? stream : Stream.of(result));
		}

		@Override
		public Stream<Object> stream(Predicate<Class<?>> customFilter, boolean includeNonSingletons) {
			ResolvableType type = this.descriptor.getResolvableType();
			return Arrays.stream(beanNamesForStream(type, includeNonSingletons, true))
					.filter(name -> AutowireUtils.isAutowireCandidate(DefaultListableBeanFactory.this, name))
					.filter(name -> customFilter.test(getType(name)))
					.map(name -> resolveBean(name, type))
					.filter(bean -> !(bean instanceof NullBean));
		}

		@Override
		public Stream<Object> orderedStream(Predicate<Class<?>> customFilter, boolean includeNonSingletons) {
			ResolvableType type = this.descriptor.getResolvableType();
			String[] beanNames = beanNamesForStream(type, includeNonSingletons, true);
			if (beanNames.length == 0) {
				return Stream.empty();
			}
			Map<String, Object> matchingBeans = CollectionUtils.newLinkedHashMap(beanNames.length);
			for (String beanName : beanNames) {
				if (AutowireUtils.isAutowireCandidate(DefaultListableBeanFactory.this, beanName) &&
						customFilter.test(getType(beanName))) {
					Object beanInstance = resolveBean(beanName, type);
					if (!(beanInstance instanceof NullBean)) {
						matchingBeans.put(beanName, beanInstance);
					}
				}
			}
			return matchingBeans.values().stream().sorted(adaptOrderComparator(matchingBeans));
		}
	}


	/**
	 * Separate inner class for avoiding a hard dependency on the {@code jakarta.inject} API.
	 * Actual {@code jakarta.inject.Provider} implementation is nested here in order to make it
	 * invisible for Graal's introspection of DefaultListableBeanFactory's nested classes.
	 */
	private class Jsr330Factory implements Serializable {

		public Object createDependencyProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			return new Jsr330Provider(descriptor, beanName);
		}

		private class Jsr330Provider extends DependencyObjectProvider implements Provider<Object> {

			public Jsr330Provider(DependencyDescriptor descriptor, @Nullable String beanName) {
				super(descriptor, beanName);
			}

			@Override
			public @Nullable Object get() throws BeansException {
				return getValue();
			}
		}
	}


	/**
	 * An {@link org.springframework.core.OrderComparator.OrderSourceProvider} implementation
	 * that is aware of the bean metadata of the instances to sort.
	 * <p>Lookup for the method factory of an instance to sort, if any, and let the
	 * comparator retrieve the {@link org.springframework.core.annotation.Order}
	 * value defined on it.
	 * <p>As of 6.1.2, this class takes the {@link AbstractBeanDefinition#ORDER_ATTRIBUTE}
	 * attribute into account.
	 */
	private class FactoryAwareOrderSourceProvider implements OrderComparator.OrderSourceProvider {

		private final Map<Object, String> instancesToBeanNames;

		public FactoryAwareOrderSourceProvider(Map<Object, String> instancesToBeanNames) {
			this.instancesToBeanNames = instancesToBeanNames;
		}

		@Override
		public @Nullable Object getOrderSource(Object obj) {
			String beanName = this.instancesToBeanNames.get(obj);
			if (beanName == null) {
				return null;
			}
			try {
				BeanDefinition beanDefinition = getMergedBeanDefinition(beanName);
				List<Object> sources = new ArrayList<>(3);
				Object orderAttribute = beanDefinition.getAttribute(AbstractBeanDefinition.ORDER_ATTRIBUTE);
				if (orderAttribute != null) {
					if (orderAttribute instanceof Integer order) {
						sources.add((Ordered) () -> order);
					}
					else {
						throw new IllegalStateException("Invalid value type for attribute '" +
								AbstractBeanDefinition.ORDER_ATTRIBUTE + "': " + orderAttribute.getClass().getName());
					}
				}
				if (beanDefinition instanceof RootBeanDefinition rootBeanDefinition) {
					Method factoryMethod = rootBeanDefinition.getResolvedFactoryMethod();
					if (factoryMethod != null) {
						sources.add(factoryMethod);
					}
					Class<?> targetType = rootBeanDefinition.getTargetType();
					if (targetType != null && targetType != obj.getClass()) {
						sources.add(targetType);
					}
				}
				return sources.toArray();
			}
			catch (NoSuchBeanDefinitionException ex) {
				return null;
			}
		}
	}


	private enum PreInstantiation {

		MAIN, BACKGROUND
	}

}

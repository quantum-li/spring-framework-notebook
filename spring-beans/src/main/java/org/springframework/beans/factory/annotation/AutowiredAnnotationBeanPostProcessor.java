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

package org.springframework.beans.factory.annotation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.generate.AccessControl;
import org.springframework.aot.generate.GeneratedClass;
import org.springframework.aot.generate.GeneratedMethod;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.support.ClassHintUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.aot.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.*;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.javapoet.ClassName;
import org.springframework.javapoet.CodeBlock;
import org.springframework.util.*;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor}
 * implementation that autowires annotated fields, setter methods, and arbitrary
 * config methods. Such members to be injected are detected through annotations:
 * by default, Spring's {@link Autowired @Autowired} and {@link Value @Value}
 * annotations.
 *
 * <p>Also supports the common {@link jakarta.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with
 * the 'required' attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the 'required' attribute
 * is set to {@code true}, only a single constructor may be annotated with
 * {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. If a class only
 * declares a single constructor to begin with, it will always be used, even if not
 * annotated. An annotated constructor does not have to be public.
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * <h3>Annotation Config vs. XML Config</h3>
 * <p>A default {@code AutowiredAnnotationBeanPostProcessor} will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom {@code AutowiredAnnotationBeanPostProcessor} bean definition.
 *
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * <h3>{@literal @}Lookup Methods</h3>
 * <p>In addition to regular injection points as discussed above, this post-processor
 * also handles Spring's {@link Lookup @Lookup} annotation which identifies lookup
 * methods to be replaced by the container at runtime. This is essentially a type-safe
 * version of {@code getBean(Class, args)} and {@code getBean(String, args)}.
 * See {@link Lookup @Lookup's javadoc} for details.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 2.5
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 */
public class AutowiredAnnotationBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor,
		MergedBeanDefinitionPostProcessor, BeanRegistrationAotProcessor, PriorityOrdered, BeanFactoryAware {

	private static final Constructor<?>[] EMPTY_CONSTRUCTOR_ARRAY = new Constructor<?>[0];


	protected final Log logger = LogFactory.getLog(getClass());

	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes = CollectionUtils.newLinkedHashSet(4);

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	private @Nullable ConfigurableListableBeanFactory beanFactory;

	private @Nullable MetadataReaderFactory metadataReaderFactory;

	private final Set<String> lookupMethodsChecked = ConcurrentHashMap.newKeySet(256);

	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache = new ConcurrentHashMap<>(256);

	private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new {@code AutowiredAnnotationBeanPostProcessor} for Spring's
	 * standard {@link Autowired @Autowired} and {@link Value @Value} annotations.
	 * <p>Also supports the common {@link jakarta.inject.Inject @Inject} annotation
	 * if available.
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		this.autowiredAnnotationTypes.add(Autowired.class);
		this.autowiredAnnotationTypes.add(Value.class);

		ClassLoader classLoader = AutowiredAnnotationBeanPostProcessor.class.getClassLoader();
		try {
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("jakarta.inject.Inject", classLoader));
			logger.trace("'jakarta.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// jakarta.inject API not available - simply skip.
		}
	}


	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as the common {@code @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as the common {@code @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of an attribute of the annotation that specifies whether it is required.
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required.
	 * <p>For example if using 'required=true' (the default), this value should be
	 * {@code true}; but if using 'optional=false', this value should be {@code false}.
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory clbf)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = clbf;
		this.metadataReaderFactory = MetadataReaderFactory.create(clbf.getBeanClassLoader());
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		// Register externally managed config members on bean definition.
		// 在bean定义上注册外部管理的配置成员。
		findInjectionMetadata(beanName, beanType, beanDefinition);
		/* 附加注释：调用findInjectionMetadata方法来查找并注册bean的注入元数据，这是为了确保所有标记了@Autowired等注解的字段和方法都被正确识别并注册 */

		// Use opportunity to clear caches which are not needed after singleton instantiation.
		// The injectionMetadataCache itself is left intact since it cannot be reliably
		// reconstructed in terms of externally managed config members otherwise.
		// 利用这个机会清除单例实例化后不再需要的缓存。
		// 保留injectionMetadataCache本身，因为就外部管理的配置成员而言，它无法可靠地重建。
		if (beanDefinition.isSingleton()) {
			/* 附加注释：对于单例bean，我们可以清除一些缓存以释放内存，因为单例bean只会被实例化一次 */
			this.candidateConstructorsCache.remove(beanType);
			/* 附加注释：移除该bean类型的候选构造函数缓存，因为单例bean实例化后不再需要这些构造函数信息 */

			// With actual lookup overrides, keep it intact along with bean definition.
			// 对于有实际查找方法重写的情况，与bean定义一起保持完整。
			if (!beanDefinition.hasMethodOverrides()) {
				/* 附加注释：只有当bean定义没有方法重写时才移除lookupMethodsChecked缓存，因为有方法重写的bean在运行时可能仍需要这些信息 */
				this.lookupMethodsChecked.remove(beanName);
			}
		}
	}

	@Override
	public void resetBeanDefinition(String beanName) {
		this.lookupMethodsChecked.remove(beanName);
		this.injectionMetadataCache.remove(beanName);
	}

	@Override
	public @Nullable BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
		Class<?> beanClass = registeredBean.getBeanClass();
		String beanName = registeredBean.getBeanName();
		RootBeanDefinition beanDefinition = registeredBean.getMergedBeanDefinition();
		InjectionMetadata metadata = findInjectionMetadata(beanName, beanClass, beanDefinition);
		Collection<AutowiredElement> autowiredElements = getAutowiredElements(metadata,
				beanDefinition.getPropertyValues());
		if (!ObjectUtils.isEmpty(autowiredElements)) {
			return new AotContribution(beanClass, autowiredElements, getAutowireCandidateResolver());
		}
		return null;
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Collection<AutowiredElement> getAutowiredElements(InjectionMetadata metadata, PropertyValues propertyValues) {
		return (Collection) metadata.getInjectedElements(propertyValues);
	}

	private @Nullable AutowireCandidateResolver getAutowireCandidateResolver() {
		if (this.beanFactory instanceof DefaultListableBeanFactory lbf) {
			return lbf.getAutowireCandidateResolver();
		}
		return null;
	}
	/**
	 * Find injection metadata for the specified bean.
	 * 查找指定bean的注入元数据。
	 */
	private InjectionMetadata findInjectionMetadata(String beanName, Class<?> beanType, RootBeanDefinition beanDefinition) {
		/* 附加注释：beanName是要处理的bean的名称，在依赖注入过程中用于标识和查找bean */
		/* 附加注释：beanType是bean的类型，用于反射查找需要注入的字段和方法 */
		/* 附加注释：beanDefinition包含bean的完整定义信息，用于注册和检查配置成员 */

		// 调用findAutowiringMetadata方法查找bean的自动装配元数据
		InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
		/* 附加注释：这里调用findAutowiringMetadata方法来获取bean的注入元数据，该方法会查找所有标记了@Autowired等注解的字段和方法，并将它们封装成InjectionMetadata对象 */

		// 检查并注册配置成员到bean定义中
		metadata.checkConfigMembers(beanDefinition);
		/* 附加注释：调用checkConfigMembers方法将找到的注入点注册到bean定义中，这样Spring可以在后续的bean生命周期中正确处理这些注入点 */

		return metadata;
	}

	@Override
	public Class<?> determineBeanType(Class<?> beanClass, String beanName) throws BeanCreationException {
		checkLookupMethods(beanClass, beanName);

		// Pick up subclass with fresh lookup method override from above
		if (this.beanFactory instanceof AbstractAutowireCapableBeanFactory aacBeanFactory) {
			RootBeanDefinition mbd = (RootBeanDefinition) this.beanFactory.getMergedBeanDefinition(beanName);
			if (mbd.getFactoryMethodName() == null && mbd.hasBeanClass()) {
				return aacBeanFactory.getInstantiationStrategy().getActualBeanClass(mbd, beanName, aacBeanFactory);
			}
		}
		return beanClass;
	}

	@Override
	public Constructor<?> @Nullable [] determineCandidateConstructors(Class<?> beanClass, final String beanName)
			throws BeanCreationException {

		checkLookupMethods(beanClass, beanName);

		// Quick check on the concurrent map first, with minimal locking.
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		if (candidateConstructors == null) {
			// Fully synchronized resolution now...
			synchronized (this.candidateConstructorsCache) {
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				if (candidateConstructors == null) {
					Constructor<?>[] rawCandidates;
					try {
						rawCandidates = beanClass.getDeclaredConstructors();
					}
					catch (Throwable ex) {
						throw new BeanCreationException(beanName,
								"Resolution of declared constructors on bean Class [" + beanClass.getName() +
								"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
					}
					List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
					Constructor<?> requiredConstructor = null;
					Constructor<?> defaultConstructor = null;
					Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
					int nonSyntheticConstructors = 0;
					for (Constructor<?> candidate : rawCandidates) {
						if (!candidate.isSynthetic()) {
							nonSyntheticConstructors++;
						}
						else if (primaryConstructor != null) {
							continue;
						}
						MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);
						if (ann == null) {
							Class<?> userClass = ClassUtils.getUserClass(beanClass);
							if (userClass != beanClass) {
								try {
									Constructor<?> superCtor =
											userClass.getDeclaredConstructor(candidate.getParameterTypes());
									ann = findAutowiredAnnotation(superCtor);
								}
								catch (NoSuchMethodException ex) {
									// Simply proceed, no equivalent superclass constructor found...
								}
							}
						}
						if (ann != null) {
							if (requiredConstructor != null) {
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
										". Found constructor with 'required' Autowired annotation already: " +
										requiredConstructor);
							}
							boolean required = determineRequiredStatus(ann);
							if (required) {
								if (!candidates.isEmpty()) {
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
											". Found constructor with 'required' Autowired annotation: " +
											candidate);
								}
								requiredConstructor = candidate;
							}
							candidates.add(candidate);
						}
						else if (candidate.getParameterCount() == 0) {
							defaultConstructor = candidate;
						}
					}
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						if (requiredConstructor == null) {
							if (defaultConstructor != null) {
								candidates.add(defaultConstructor);
							}
							else if (candidates.size() == 1 && logger.isInfoEnabled()) {
								logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - " +
										"this constructor is effectively required since there is no " +
										"default constructor to fall back to: " + candidates.get(0));
							}
						}
						candidateConstructors = candidates.toArray(EMPTY_CONSTRUCTOR_ARRAY);
					}
					else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
						candidateConstructors = new Constructor<?>[] {rawCandidates[0]};
					}
					else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
							defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor, defaultConstructor};
					}
					else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor};
					}
					else {
						candidateConstructors = EMPTY_CONSTRUCTOR_ARRAY;
					}
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		return (candidateConstructors.length > 0 ? candidateConstructors : null);
	}

	private void checkLookupMethods(Class<?> beanClass, final String beanName) throws BeanCreationException {
		if (!this.lookupMethodsChecked.contains(beanName)) {
			if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
				try {
					Class<?> targetClass = beanClass;
					do {
						ReflectionUtils.doWithLocalMethods(targetClass, method -> {
							Lookup lookup = method.getAnnotation(Lookup.class);
							if (lookup != null) {
								Assert.state(this.beanFactory != null, "No BeanFactory available");
								LookupOverride override = new LookupOverride(method, lookup.value());
								try {
									RootBeanDefinition mbd = (RootBeanDefinition)
											this.beanFactory.getMergedBeanDefinition(beanName);
									mbd.getMethodOverrides().addOverride(override);
								}
								catch (NoSuchBeanDefinitionException ex) {
									throw new BeanCreationException(beanName,
											"Cannot apply @Lookup to beans without corresponding bean definition");
								}
							}
						});
						targetClass = targetClass.getSuperclass();
					}
					while (targetClass != null && targetClass != Object.class);

				}
				catch (IllegalStateException ex) {
					throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
				}
			}
			this.lookupMethodsChecked.add(beanName);
		}
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
		try {
			metadata.inject(bean, beanName, pvs);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		return pvs;
	}

	/**
	 * <em>Native</em> processing method for direct calls with an arbitrary target
	 * instance, resolving all of its fields and methods which are annotated with
	 * one of the configured 'autowired' annotation types.
	 * @param bean the target instance to process
	 * @throws BeanCreationException if autowiring failed
	 * @see #setAutowiredAnnotationTypes(Set)
	 */
	public void processInjection(Object bean) throws BeanCreationException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					"Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}

	/**
	 * Find autowiring metadata for the specified bean instance by bean name or type.
	 * 查找指定bean实例的自动装配元数据，通过bean名称或类型。
	 * @param beanName the name of the bean
	 * @param clazz the target class to look for annotations
	 * @param pvs the property values to register autowired dependencies before they get applied
	 */
	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		// 回退到类名作为缓存键，为了向后兼容自定义调用者。
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		/* 附加注释：cacheKey用于在缓存中查找和存储InjectionMetadata，优先使用beanName，如果为空则使用类名 */

		// Quick check on the concurrent map first, with minimal locking.
		// 首先在并发Map上进行快速检查，使用最小的锁定。
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		/* 附加注释：从缓存中获取注入元数据，避免重复构建，提高性能 */

		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			/* 附加注释：当元数据为null或者元数据的类与当前类不匹配时，需要刷新元数据 */
			synchronized (this.injectionMetadataCache) {
				/* 附加注释：使用同步块确保线程安全，防止多线程同时构建相同的元数据 */
				metadata = this.injectionMetadataCache.get(cacheKey);
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					/* 附加注释：双重检查锁定模式，确保在获取锁后仍然需要刷新 */
					if (metadata != null) {
						metadata.clear(pvs);
						/* 附加注释：清除旧的元数据，避免内存泄漏，同时处理属性值 */
					}
					metadata = buildAutowiringMetadata(clazz);
					/* 附加注释：构建新的自动装配元数据，分析类中的字段和方法上的自动装配注解 */
					this.injectionMetadataCache.put(cacheKey, metadata);
					/* 附加注释：将新构建的元数据放入缓存，供后续使用 */
				}
			}
		}
		return metadata;
	}

	/**
	 * Build autowiring metadata for the specified class.
	 * 为指定的类构建自动装配元数据。
	 * @param clazz the class to analyze
	 * @return InjectionMetadata instance, possibly empty but never {@code null}
	 */
	private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
		if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
			/* 附加注释：快速检查类是否包含任何自动装配注解，如果不包含则直接返回空元数据，避免不必要的反射操作 */
			return InjectionMetadata.EMPTY;
		}

		final List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		/* 附加注释：用于收集所有需要注入的元素（字段和方法），最终用于构建InjectionMetadata */
		Class<?> targetClass = clazz;
		/* 附加注释：从当前类开始，逐级向上遍历类层次结构，确保捕获所有继承的注入点 */

		do {
			final List<InjectionMetadata.InjectedElement> fieldElements = new ArrayList<>();
			/* 附加注释：存储当前类中所有需要自动装配的字段元素 */
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				/* 附加注释：遍历当前类的所有字段，不包括继承的字段，检查是否需要自动装配 */
				MergedAnnotation<?> ann = findAutowiredAnnotation(field);
				/* 附加注释：查找字段上的自动装配注解，如@Autowired、@Value等，返回合并后的注解信息 */
				if (ann != null) {
					if (Modifier.isStatic(field.getModifiers())) {
						/* 附加注释：自动装配不支持静态字段，因为静态字段属于类而非实例 */
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static fields: " + field);
						}
						return;
					}
					boolean required = determineRequiredStatus(ann);
					/* 附加注释：确定该注入是否为必需的，通常基于注解的required属性，如@Autowired(required=false) */
					fieldElements.add(new AutowiredFieldElement(field, required));
					/* 附加注释：创建字段注入元素并添加到集合中，后续会用于实际的依赖注入 */
				}
			});

			final List<InjectionMetadata.InjectedElement> methodElements = new ArrayList<>();
			/* 附加注释：存储当前类中所有需要自动装配的方法元素 */
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				/* 附加注释：遍历当前类的所有方法，不包括继承的方法，检查是否需要自动装配 */
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				/* 附加注释：处理桥接方法，在泛型场景下确保找到原始方法，避免重复注入 */
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					/* 附加注释：如果不是可见性桥接方法对，则跳过，防止处理不必要的合成方法 */
					return;
				}
				MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
				/* 附加注释：查找方法上的自动装配注解，使用桥接方法确保注解被正确识别 */
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					/* 附加注释：确保只处理最具体的方法实现，避免在继承层次中重复注入 */
					if (Modifier.isStatic(method.getModifiers())) {
						/* 附加注释：自动装配不支持静态方法，因为静态方法属于类而非实例 */
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static methods: " + method);
						}
						return;
					}
					if (method.getParameterCount() == 0) {
						/* 附加注释：检查方法是否有参数，无参方法通常不需要自动装配 */
						if (method.getDeclaringClass().isRecord()) {
							/* 附加注释：特殊处理Record类，忽略其访问器方法上的注解，因为这些可能来自紧凑构造函数参数 */
							// Annotations on the compact constructor arguments made available on accessors, ignoring.
							// 紧凑构造函数参数上的注解在访问器上可用，忽略。
							return;
						}
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation should only be used on methods with parameters: " +
									method);
						}
					}
					boolean required = determineRequiredStatus(ann);
					/* 附加注释：确定该注入是否为必需的，与字段注入类似 */
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					/* 附加注释：尝试将方法解析为JavaBean属性，支持setter注入模式 */
					methodElements.add(new AutowiredMethodElement(method, required, pd));
					/* 附加注释：创建方法注入元素并添加到集合中，后续会用于实际的依赖注入 */
				}
			});

			elements.addAll(0, sortMethodElements(methodElements, targetClass));
			/* 附加注释：对方法元素进行排序并添加到总元素列表的前面，确保方法注入在字段注入之前执行 */
			elements.addAll(0, fieldElements);
			/* 附加注释：将字段元素添加到总元素列表的前面，使得执行顺序为：父类字段->父类方法->子类字段->子类方法 */
			targetClass = targetClass.getSuperclass();
			/* 附加注释：移动到父类，继续处理继承链上的注入点 */
		}
		while (targetClass != null && targetClass != Object.class);
		/* 附加注释：循环直到处理完整个类层次结构，但不包括Object类 */

		return InjectionMetadata.forElements(elements, clazz);
		/* 附加注释：根据收集的所有注入元素创建最终的InjectionMetadata对象，用于后续实际执行依赖注入 */
	}

	private @Nullable MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
		MergedAnnotations annotations = MergedAnnotations.from(ao);
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			MergedAnnotation<?> annotation = annotations.get(type);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		return null;
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 */
	protected boolean determineRequiredStatus(MergedAnnotation<?> ann) {
		return (ann.getValue(this.requiredParameterName).isEmpty() ||
				this.requiredParameterValue == ann.getBoolean(this.requiredParameterName));
	}

	/**
	 * Sort the method elements via ASM for deterministic declaration order if possible.
	 */
	private List<InjectionMetadata.InjectedElement> sortMethodElements(
			List<InjectionMetadata.InjectedElement> methodElements, Class<?> targetClass) {

		if (this.metadataReaderFactory != null && methodElements.size() > 1) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			try {
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(targetClass.getName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Autowired.class.getName());
				if (asmMethods.size() >= methodElements.size()) {
					List<InjectionMetadata.InjectedElement> candidateMethods = new ArrayList<>(methodElements);
					List<InjectionMetadata.InjectedElement> selectedMethods = new ArrayList<>(asmMethods.size());
					for (MethodMetadata asmMethod : asmMethods) {
						for (Iterator<InjectionMetadata.InjectedElement> it = candidateMethods.iterator(); it.hasNext();) {
							InjectionMetadata.InjectedElement element = it.next();
							if (element.getMember().getName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(element);
								it.remove();
								break;
							}
						}
					}
					if (selectedMethods.size() == methodElements.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						return selectedMethods;
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Autowired method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return methodElements;
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 */
	private void registerDependentBeans(@Nullable String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			for (String autowiredBeanName : autowiredBeanNames) {
				if (this.beanFactory != null && this.beanFactory.containsBean(autowiredBeanName)) {
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	private @Nullable Object resolveCachedArgument(@Nullable String beanName, @Nullable Object cachedArgument) {
		if (cachedArgument instanceof DependencyDescriptor descriptor) {
			Assert.state(this.beanFactory != null, "No BeanFactory available");
			return this.beanFactory.resolveDependency(descriptor, beanName, null, null);
		}
		else {
			return cachedArgument;
		}
	}


	/**
	 * Base class representing injection information.
	 */
	private abstract static class AutowiredElement extends InjectionMetadata.InjectedElement {

		protected final boolean required;

		protected AutowiredElement(Member member, @Nullable PropertyDescriptor pd, boolean required) {
			super(member, pd);
			this.required = required;
		}
	}


	/**
	 * Class representing injection information about an annotated field.
	 */
	private class AutowiredFieldElement extends AutowiredElement {

		private volatile boolean cached;

		private volatile @Nullable Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null, required);
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			Field field = (Field) this.member;
			Object value;
			if (this.cached) {
				try {
					value = resolveCachedArgument(beanName, this.cachedFieldValue);
				}
				catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					this.cached = false;
					logger.debug("Failed to resolve cached argument", ex);
					value = resolveFieldValue(field, bean, beanName);
				}
			}
			else {
				value = resolveFieldValue(field, bean, beanName);
			}
			if (value != null) {
				ReflectionUtils.makeAccessible(field);
				field.set(bean, value);
			}
		}

		private @Nullable Object resolveFieldValue(Field field, Object bean, @Nullable String beanName) {
			DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
			desc.setContainingClass(bean.getClass());
			Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
			Assert.state(beanFactory != null, "No BeanFactory available");
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			Object value;
			try {
				value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
			}
			synchronized (this) {
				if (!this.cached) {
					if (value != null || this.required) {
						Object cachedFieldValue = desc;
						registerDependentBeans(beanName, autowiredBeanNames);
						if (value != null && autowiredBeanNames.size() == 1) {
							String autowiredBeanName = autowiredBeanNames.iterator().next();
							if (beanFactory.containsBean(autowiredBeanName) &&
									beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
								cachedFieldValue = new ShortcutDependencyDescriptor(desc, autowiredBeanName);
							}
						}
						this.cachedFieldValue = cachedFieldValue;
						this.cached = true;
					}
					else {
						this.cachedFieldValue = null;
						// cached flag remains false
					}
				}
			}
			return value;
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 */
	private class AutowiredMethodElement extends AutowiredElement {

		private volatile boolean cached;

		private volatile Object @Nullable [] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, @Nullable PropertyDescriptor pd) {
			super(method, pd, required);
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			if (!shouldInject(pvs)) {
				return;
			}
			Method method = (Method) this.member;
			@Nullable Object[] arguments;
			if (this.cached) {
				try {
					arguments = resolveCachedArguments(beanName, this.cachedMethodArguments);
				}
				catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					this.cached = false;
					logger.debug("Failed to resolve cached argument", ex);
					arguments = resolveMethodArguments(method, bean, beanName);
				}
			}
			else {
				arguments = resolveMethodArguments(method, bean, beanName);
			}
			if (arguments != null) {
				try {
					ReflectionUtils.makeAccessible(method);
					method.invoke(bean, arguments);
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		private @Nullable Object @Nullable [] resolveCachedArguments(@Nullable String beanName, Object @Nullable [] cachedMethodArguments) {
			if (cachedMethodArguments == null) {
				return null;
			}
			@Nullable Object[] arguments = new Object[cachedMethodArguments.length];
			for (int i = 0; i < arguments.length; i++) {
				arguments[i] = resolveCachedArgument(beanName, cachedMethodArguments[i]);
			}
			return arguments;
		}

		private @Nullable Object @Nullable [] resolveMethodArguments(Method method, Object bean, @Nullable String beanName) {
			int argumentCount = method.getParameterCount();
			@Nullable Object[] arguments = new Object[argumentCount];
			DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
			Set<String> autowiredBeanNames = CollectionUtils.newLinkedHashSet(argumentCount);
			Assert.state(beanFactory != null, "No BeanFactory available");
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			for (int i = 0; i < arguments.length; i++) {
				MethodParameter methodParam = new MethodParameter(method, i);
				DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, this.required);
				currDesc.setContainingClass(bean.getClass());
				descriptors[i] = currDesc;
				try {
					Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeanNames, typeConverter);
					if (arg == null && !this.required && !methodParam.isOptional()) {
						arguments = null;
						break;
					}
					arguments[i] = arg;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
				}
			}
			synchronized (this) {
				if (!this.cached) {
					if (arguments != null) {
						DependencyDescriptor[] cachedMethodArguments = Arrays.copyOf(descriptors, argumentCount);
						registerDependentBeans(beanName, autowiredBeanNames);
						if (autowiredBeanNames.size() == argumentCount) {
							Iterator<String> it = autowiredBeanNames.iterator();
							Class<?>[] paramTypes = method.getParameterTypes();
							for (int i = 0; i < paramTypes.length; i++) {
								String autowiredBeanName = it.next();
								if (arguments[i] != null && beanFactory.containsBean(autowiredBeanName) &&
										beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
									cachedMethodArguments[i] = new ShortcutDependencyDescriptor(
											descriptors[i], autowiredBeanName);
								}
							}
						}
						this.cachedMethodArguments = cachedMethodArguments;
						this.cached = true;
					}
					else {
						this.cachedMethodArguments = null;
						// cached flag remains false
					}
				}
			}
			return arguments;
		}
	}


	/**
	 * DependencyDescriptor variant with a pre-resolved target bean name.
	 */
	@SuppressWarnings("serial")
	private static class ShortcutDependencyDescriptor extends DependencyDescriptor {

		private final String shortcut;

		public ShortcutDependencyDescriptor(DependencyDescriptor original, String shortcut) {
			super(original);
			this.shortcut = shortcut;
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			return beanFactory.getBean(this.shortcut, getDependencyType());
		}
	}


	/**
	 * {@link BeanRegistrationAotContribution} to autowire fields and methods.
	 */
	private static class AotContribution implements BeanRegistrationAotContribution {

		private static final String REGISTERED_BEAN_PARAMETER = "registeredBean";

		private static final String INSTANCE_PARAMETER = "instance";

		private final Class<?> target;

		private final Collection<AutowiredElement> autowiredElements;

		private final @Nullable AutowireCandidateResolver candidateResolver;

		AotContribution(Class<?> target, Collection<AutowiredElement> autowiredElements,
				@Nullable AutowireCandidateResolver candidateResolver) {

			this.target = target;
			this.autowiredElements = autowiredElements;
			this.candidateResolver = candidateResolver;
		}

		@Override
		public void applyTo(GenerationContext generationContext, BeanRegistrationCode beanRegistrationCode) {
			GeneratedClass generatedClass = generationContext.getGeneratedClasses()
					.addForFeatureComponent("Autowiring", this.target, type -> {
						type.addJavadoc("Autowiring for {@link $T}.", this.target);
						type.addModifiers(javax.lang.model.element.Modifier.PUBLIC);
					});
			GeneratedMethod generateMethod = generatedClass.getMethods().add("apply", method -> {
				method.addJavadoc("Apply the autowiring.");
				method.addModifiers(javax.lang.model.element.Modifier.PUBLIC,
						javax.lang.model.element.Modifier.STATIC);
				method.addParameter(RegisteredBean.class, REGISTERED_BEAN_PARAMETER);
				method.addParameter(this.target, INSTANCE_PARAMETER);
				method.returns(this.target);
				CodeWarnings codeWarnings = new CodeWarnings();
				codeWarnings.detectDeprecation(this.target);
				method.addCode(generateMethodCode(codeWarnings,
						generatedClass.getName(), generationContext.getRuntimeHints()));
				codeWarnings.suppress(method);
			});
			beanRegistrationCode.addInstancePostProcessor(generateMethod.toMethodReference());

			if (this.candidateResolver != null) {
				registerHints(generationContext.getRuntimeHints());
			}
		}

		private CodeBlock generateMethodCode(CodeWarnings codeWarnings,
				ClassName targetClassName, RuntimeHints hints) {

			CodeBlock.Builder code = CodeBlock.builder();
			for (AutowiredElement autowiredElement : this.autowiredElements) {
				code.addStatement(generateMethodStatementForElement(
						codeWarnings, targetClassName, autowiredElement, hints));
			}
			code.addStatement("return $L", INSTANCE_PARAMETER);
			return code.build();
		}

		private CodeBlock generateMethodStatementForElement(CodeWarnings codeWarnings,
				ClassName targetClassName, AutowiredElement autowiredElement, RuntimeHints hints) {

			Member member = autowiredElement.getMember();
			boolean required = autowiredElement.required;
			if (member instanceof Field field) {
				return generateMethodStatementForField(
						codeWarnings, targetClassName, field, required, hints);
			}
			if (member instanceof Method method) {
				return generateMethodStatementForMethod(
						codeWarnings, targetClassName, method, required, hints);
			}
			throw new IllegalStateException(
					"Unsupported member type " + member.getClass().getName());
		}

		private CodeBlock generateMethodStatementForField(CodeWarnings codeWarnings,
				ClassName targetClassName, Field field, boolean required, RuntimeHints hints) {

			hints.reflection().registerField(field);
			CodeBlock resolver = CodeBlock.of("$T.$L($S)",
					AutowiredFieldValueResolver.class,
					(!required ? "forField" : "forRequiredField"), field.getName());
			AccessControl accessControl = AccessControl.forMember(field);
			if (!accessControl.isAccessibleFrom(targetClassName)) {
				return CodeBlock.of("$L.resolveAndSet($L, $L)", resolver,
						REGISTERED_BEAN_PARAMETER, INSTANCE_PARAMETER);
			}
			else {
				codeWarnings.detectDeprecation(field);
				return CodeBlock.of("$L.$L = $L.resolve($L)", INSTANCE_PARAMETER,
						field.getName(), resolver, REGISTERED_BEAN_PARAMETER);
			}
		}

		private CodeBlock generateMethodStatementForMethod(CodeWarnings codeWarnings,
				ClassName targetClassName, Method method, boolean required, RuntimeHints hints) {

			CodeBlock.Builder code = CodeBlock.builder();
			code.add("$T.$L", AutowiredMethodArgumentsResolver.class,
					(!required ? "forMethod" : "forRequiredMethod"));
			code.add("($S", method.getName());
			if (method.getParameterCount() > 0) {
				codeWarnings.detectDeprecation(method.getParameterTypes());
				code.add(", $L", generateParameterTypesCode(method.getParameterTypes()));
			}
			code.add(")");
			AccessControl accessControl = AccessControl.forMember(method);
			if (!accessControl.isAccessibleFrom(targetClassName)) {
				hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
				code.add(".resolveAndInvoke($L, $L)", REGISTERED_BEAN_PARAMETER, INSTANCE_PARAMETER);
			}
			else {
				codeWarnings.detectDeprecation(method);
				hints.reflection().registerType(method.getDeclaringClass());
				CodeBlock arguments = new AutowiredArgumentsCodeGenerator(this.target,
						method).generateCode(method.getParameterTypes());
				CodeBlock injectionCode = CodeBlock.of("args -> $L.$L($L)",
						INSTANCE_PARAMETER, method.getName(), arguments);
				code.add(".resolve($L, $L)", REGISTERED_BEAN_PARAMETER, injectionCode);
			}
			return code.build();
		}

		private CodeBlock generateParameterTypesCode(Class<?>[] parameterTypes) {
			return CodeBlock.join(Arrays.stream(parameterTypes)
					.map(parameterType -> CodeBlock.of("$T.class", parameterType))
					.toList(), ", ");
		}

		private void registerHints(RuntimeHints runtimeHints) {
			this.autowiredElements.forEach(autowiredElement -> {
				boolean required = autowiredElement.required;
				Member member = autowiredElement.getMember();
				if (member instanceof Field field) {
					DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(field, required);
					registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
				}
				if (member instanceof Method method) {
					Class<?>[] parameterTypes = method.getParameterTypes();
					for (int i = 0; i < parameterTypes.length; i++) {
						MethodParameter methodParam = new MethodParameter(method, i);
						DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(methodParam, required);
						registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
					}
				}
			});
		}

		private void registerProxyIfNecessary(RuntimeHints runtimeHints, DependencyDescriptor dependencyDescriptor) {
			if (this.candidateResolver != null) {
				Class<?> proxyClass =
						this.candidateResolver.getLazyResolutionProxyClass(dependencyDescriptor, null);
				if (proxyClass != null) {
					ClassHintUtils.registerProxyIfNecessary(proxyClass, runtimeHints);
				}
			}
		}
	}

}

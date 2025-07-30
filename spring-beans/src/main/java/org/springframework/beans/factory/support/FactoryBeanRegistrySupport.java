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

import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.*;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.ResolvableType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Support base class for singleton registries which need to handle
 * {@link org.springframework.beans.factory.FactoryBean} instances,
 * integrated with {@link DefaultSingletonBeanRegistry}'s singleton management.
 *
 * <p>Serves as base class for {@link AbstractBeanFactory}.
 *
 * @author Juergen Hoeller
 * @since 2.5.1
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/** Cache of singleton objects created by FactoryBeans: FactoryBean name to object. */
	/** 由FactoryBean创建的单例对象的缓存：FactoryBean名称到对象的映射。 */
	/* 附加注释：这个Map用于缓存FactoryBean创建的单例对象，避免重复创建。当Spring容器中的FactoryBean创建了一个单例对象后，
	   会将该对象存储在这个缓存中，键为bean的名称，值为FactoryBean创建的实际对象。使用ConcurrentHashMap是为了保证线程安全，
	   因为在多线程环境下可能会同时访问这个缓存。初始容量设为16是HashMap的默认初始容量，适合大多数应用场景。 */
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);


	/**
	 * Determine the type for the given FactoryBean.
	 * @param factoryBean the FactoryBean instance to check
	 * @return the FactoryBean's object type,
	 * or {@code null} if the type cannot be determined yet
	 */
	protected @Nullable Class<?> getTypeForFactoryBean(FactoryBean<?> factoryBean) {
		try {
			return factoryBean.getObjectType();
		}
		catch (Throwable ex) {
			// Thrown from the FactoryBean's getObjectType implementation.
			logger.info("FactoryBean threw exception from getObjectType, despite the contract saying " +
					"that it should return null if the type of its object cannot be determined yet", ex);
			return null;
		}
	}

	/**
	 * Determine the bean type for a FactoryBean by inspecting its attributes for a
	 * {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} value.
	 * @param attributes the attributes to inspect
	 * @return a {@link ResolvableType} extracted from the attributes or
	 * {@code ResolvableType.NONE}
	 * @since 5.2
	 */
	ResolvableType getTypeForFactoryBeanFromAttributes(AttributeAccessor attributes) {
		Object attribute = attributes.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
		if (attribute == null) {
			return ResolvableType.NONE;
		}
		if (attribute instanceof ResolvableType resolvableType) {
			return resolvableType;
		}
		if (attribute instanceof Class<?> clazz) {
			return ResolvableType.forClass(clazz);
		}
		throw new IllegalArgumentException("Invalid value type for attribute '" +
				FactoryBean.OBJECT_TYPE_ATTRIBUTE + "': " + attribute.getClass().getName());
	}

	/**
	 * Determine the FactoryBean object type from the given generic declaration.
	 * @param type the FactoryBean type
	 * @return the nested object type, or {@code NONE} if not resolvable
	 */
	ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		return (type != null ? type.as(FactoryBean.class).getGeneric() : ResolvableType.NONE);
	}

	/**
	 * Obtain an object to expose from the given FactoryBean, if available
	 * in cached form. Quick check for minimal synchronization.
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean,
	 * or {@code null} if not available
	 */
	protected @Nullable Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}
	/**
	 * Obtain an object to expose from the given FactoryBean.
	 * 从给定的FactoryBean获取要暴露的对象。
	 * @param factory the FactoryBean instance
	 * FactoryBean实例
	 * @param beanName the name of the bean
	 * bean的名称
	 * @param shouldPostProcess whether the bean is subject to post-processing
	 * 是否应该对bean进行后处理
	 * @return the object obtained from the FactoryBean
	 * 从FactoryBean获取的对象
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * 如果FactoryBean对象创建失败则抛出异常
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, @Nullable Class<?> requiredType,
			String beanName, boolean shouldPostProcess) {

		/* 附加注释：首先检查是否是单例且已经在单例注册表中，只有目标bean和factorybean都是单例模式才可以从缓存获取或创建并缓存对象，其他场景都需要每次调用创建一个目标bean。 */
		if (factory.isSingleton() && containsSingleton(beanName)) {
			/* 附加注释：获取当前线程是否允许持有单例锁的标志，用于处理并发访问单例bean的情况 */
			Boolean lockFlag = isCurrentThreadAllowedToHoldSingletonLock();
			boolean locked;
			/* 附加注释：根据锁标志决定获取锁的方式，如果为null则直接加锁，否则尝试获取锁 */
			if (lockFlag == null) {
				this.singletonLock.lock();
				locked = true;
			}
			else {
				locked = (lockFlag && this.singletonLock.tryLock());
			}
			try {
				// A SmartFactoryBean may return multiple object types -> do not cache.
				// SmartFactoryBean可能返回多种对象类型 -> 不进行缓存。
				/* 附加注释：检查是否是SmartFactoryBean，SmartFactoryBean可能根据请求类型返回不同对象，因此不适合缓存 */
				boolean smart = (factory instanceof SmartFactoryBean<?>);
				/* 附加注释：如果不是SmartFactoryBean，尝试从缓存中获取对象 */
				Object object = (!smart ? this.factoryBeanObjectCache.get(beanName) : null);
				if (object == null) {
					/* 附加注释：缓存中没有找到对象，调用doGetObjectFromFactoryBean方法从FactoryBean获取对象 */
					object = doGetObjectFromFactoryBean(factory, requiredType, beanName);
					// Only post-process and store if not put there already during getObject() call above
					// (for example, because of circular reference processing triggered by custom getBean calls)
					// 只有在上面的getObject()调用期间尚未放入缓存的情况下才进行后处理和存储
					// (例如，由于自定义getBean调用触发的循环引用处理)
					/* 附加注释：再次检查缓存，防止在获取对象过程中由于循环依赖等原因已经有其他线程放入了对象 */
					Object alreadyThere = (!smart ? this.factoryBeanObjectCache.get(beanName) : null);
					if (alreadyThere != null) {
						object = alreadyThere;
					}
					else {
						/* 附加注释：如果需要后处理，执行后处理逻辑 */
						if (shouldPostProcess) {
							if (locked) {
								/* 附加注释：检查是否存在循环创建，如果是则临时返回未经后处理的对象，避免死循环 */
								if (isSingletonCurrentlyInCreation(beanName)) {
									// Temporarily return non-post-processed object, not storing it yet
									// 临时返回未经后处理的对象，暂不存储
									return object;
								}
								/* 附加注释：标记bean正在创建中，防止并发创建同一个bean */
								beforeSingletonCreation(beanName);
							}
							try {
								/* 附加注释：调用后处理方法，允许子类对从FactoryBean获取的对象进行自定义处理 */
								object = postProcessObjectFromFactoryBean(object, beanName);
							}
							catch (Throwable ex) {
								throw new BeanCreationException(beanName,
										"Post-processing of FactoryBean's singleton object failed", ex);
							}
							finally {
								if (locked) {
									/* 附加注释：移除bean正在创建的标记 */
									afterSingletonCreation(beanName);
								}
							}
						}
						/* 附加注释：如果不是SmartFactoryBean且是单例，则将处理后的对象放入缓存 */
						if (!smart && containsSingleton(beanName)) {
							this.factoryBeanObjectCache.put(beanName, object);
						}
					}
				}
				return object;
			}
			finally {
				if (locked) {
					/* 附加注释：确保在所有情况下都释放锁，防止死锁 */
					this.singletonLock.unlock();
				}
			}
		}
		else {
			/* 附加注释：处理非单例bean或未注册的bean，直接从FactoryBean获取对象而不缓存 */
			Object object = doGetObjectFromFactoryBean(factory, requiredType, beanName);
			if (shouldPostProcess) {
				try {
					/* 附加注释：对非单例bean也执行后处理，但不缓存结果 */
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(beanName, "Post-processing of FactoryBean's object failed", ex);
				}
			}
			return object;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 * @param factory the FactoryBean instance
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, @Nullable Class<?> requiredType, String beanName)
			throws BeanCreationException {

		Object object;
		try {
			object = (requiredType != null && factory instanceof SmartFactoryBean<?> smartFactoryBean ?
					smartFactoryBean.getObject(requiredType) : factory.getObject());
		}
		catch (FactoryBeanNotInitializedException ex) {
			throw new BeanCurrentlyInCreationException(beanName, ex.toString());
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "FactoryBean threw exception on object creation", ex);
		}

		// Do not accept a null value for a FactoryBean that's not fully
		// initialized yet: Many FactoryBeans just return null then.
		if (object == null) {
			if (isSingletonCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(
						beanName, "FactoryBean which is currently in creation returned null from getObject");
			}
			object = new NullBean();
		}
		return object;
	}

	/**
	 * Post-process the given object that has been obtained from the FactoryBean.
	 * The resulting object will get exposed for bean references.
	 * <p>The default implementation simply returns the given object as-is.
	 * Subclasses may override this, for example, to apply post-processors.
	 * @param object the object obtained from the FactoryBean.
	 * @param beanName the name of the bean
	 * @return the object to expose
	 * @throws org.springframework.beans.BeansException if any post-processing failed
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) throws BeansException {
		return object;
	}

	/**
	 * Get a FactoryBean for the given bean if possible.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance
	 * @return the bean instance as FactoryBean
	 * @throws BeansException if the given bean cannot be exposed as a FactoryBean
	 */
	protected FactoryBean<?> getFactoryBean(String beanName, Object beanInstance) throws BeansException {
		if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
			throw new BeanCreationException(beanName,
					"Bean instance of type [" + beanInstance.getClass() + "] is not a FactoryBean");
		}
		return factoryBean;
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		super.removeSingleton(beanName);
		this.factoryBeanObjectCache.remove(beanName);
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		super.clearSingletonCache();
		this.factoryBeanObjectCache.clear();
	}

}

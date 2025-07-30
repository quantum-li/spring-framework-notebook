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

package org.springframework.beans.factory.config;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;

/**
 * Factory hook that allows for custom modification of new bean instances &mdash;
 * for example, checking for marker interfaces or wrapping beans with proxies.
 *
 * 工厂钩子，允许对新的bean实例进行自定义修改 —— 例如，检查标记接口或使用代理包装bean。
 *
 * <p>Typically, post-processors that populate beans via marker interfaces
 * or the like will implement {@link #postProcessBeforeInitialization},
 * while post-processors that wrap beans with proxies will normally
 * implement {@link #postProcessAfterInitialization}.
 *
 * 通常，通过标记接口或类似方式填充bean的后处理器将实现{@link #postProcessBeforeInitialization}，
 * 而使用代理包装bean的后处理器通常会实现{@link #postProcessAfterInitialization}。
 *
 * <h3>Registration</h3>
 * <p>An {@code ApplicationContext} can autodetect {@code BeanPostProcessor} beans
 * in its bean definitions and apply those post-processors to any beans subsequently
 * created. A plain {@code BeanFactory} allows for programmatic registration of
 * post-processors, applying them to all beans created through the bean factory.
 *
 * <h3>注册</h3>
 * <p>{@code ApplicationContext}可以在其bean定义中自动检测{@code BeanPostProcessor} bean，
 * 并将这些后处理器应用于随后创建的任何bean。普通的{@code BeanFactory}允许以编程方式注册
 * 后处理器，将它们应用于通过bean工厂创建的所有bean。
 *
 * <h3>Ordering</h3>
 * <p>{@code BeanPostProcessor} beans that are autodetected in an
 * {@code ApplicationContext} will be ordered according to
 * {@link org.springframework.core.PriorityOrdered} and
 * {@link org.springframework.core.Ordered} semantics. In contrast,
 * {@code BeanPostProcessor} beans that are registered programmatically with a
 * {@code BeanFactory} will be applied in the order of registration; any ordering
 * semantics expressed through implementing the
 * {@code PriorityOrdered} or {@code Ordered} interface will be ignored for
 * programmatically registered post-processors. Furthermore, the
 * {@link org.springframework.core.annotation.Order @Order} annotation is not
 * taken into account for {@code BeanPostProcessor} beans.
 *
 * <h3>排序</h3>
 * <p>在{@code ApplicationContext}中自动检测到的{@code BeanPostProcessor} bean将根据
 * {@link org.springframework.core.PriorityOrdered}和{@link org.springframework.core.Ordered}
 * 语义进行排序。相比之下，以编程方式在{@code BeanFactory}中注册的{@code BeanPostProcessor} bean
 * 将按照注册顺序应用；通过实现{@code PriorityOrdered}或{@code Ordered}接口表达的任何排序语义
 * 将被忽略，这适用于以编程方式注册的后处理器。此外，{@code BeanPostProcessor} bean不会考虑
 * {@link org.springframework.core.annotation.Order @Order}注解。
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 10.10.2003
 * @see InstantiationAwareBeanPostProcessor
 * @see DestructionAwareBeanPostProcessor
 * @see ConfigurableBeanFactory#addBeanPostProcessor
 * @see BeanFactoryPostProcessor
 */
public interface BeanPostProcessor {

	/**
	 * Apply this {@code BeanPostProcessor} to the given new bean instance <i>before</i> any bean
	 * initialization callbacks (like InitializingBean's {@code afterPropertiesSet}
	 * or a custom init-method). The bean will already be populated with property values.
	 * The returned bean instance may be a wrapper around the original.
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the new bean instance
	 * @param beanName the name of the bean
	 * @return the bean instance to use, either the original or a wrapped one;
	 * if {@code null}, no subsequent BeanPostProcessors will be invoked
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 *
	 * 在任何bean初始化回调（如InitializingBean的{@code afterPropertiesSet}或自定义init方法）
	 * <i>之前</i>将此{@code BeanPostProcessor}应用于给定的新bean实例。bean已经被填充了属性值。
	 * 返回的bean实例可能是原始实例的包装器。
	 * <p>默认实现按原样返回给定的{@code bean}。
	 * @param bean 新的bean实例
	 * @param beanName bean的名称
	 * @return 要使用的bean实例，可以是原始的或包装过的；
	 * 如果为{@code null}，则不会调用后续的BeanPostProcessors
	 * @throws org.springframework.beans.BeansException 出错时抛出
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 */
	default @Nullable Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		/* 附加注释：这是一个默认方法，在bean初始化前被调用，可以用于修改bean属性、添加功能等。在AOP、事务等场景中非常重要，默认实现直接返回原始bean，子类可以重写此方法实现自定义逻辑 */
		return bean;
	}

	/**
	 * Apply this {@code BeanPostProcessor} to the given new bean instance <i>after</i> any bean
	 * initialization callbacks (like InitializingBean's {@code afterPropertiesSet}
	 * or a custom init-method). The bean will already be populated with property values.
	 * The returned bean instance may be a wrapper around the original.
	 * <p>In case of a FactoryBean, this callback will be invoked for both the FactoryBean
	 * instance and the objects created by the FactoryBean (as of Spring 2.0). The
	 * post-processor can decide whether to apply to either the FactoryBean or created
	 * objects or both through corresponding {@code bean instanceof FactoryBean} checks.
	 * <p>This callback will also be invoked after a short-circuiting triggered by a
	 * {@link InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation} method,
	 * in contrast to all other {@code BeanPostProcessor} callbacks.
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the new bean instance
	 * @param beanName the name of the bean
	 * @return the bean instance to use, either the original or a wrapped one;
	 * if {@code null}, no subsequent BeanPostProcessors will be invoked
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 * @see org.springframework.beans.factory.FactoryBean
	 *
	 * 在任何bean初始化回调（如InitializingBean的{@code afterPropertiesSet}或自定义init方法）
	 * <i>之后</i>将此{@code BeanPostProcessor}应用于给定的新bean实例。bean已经被填充了属性值。
	 * 返回的bean实例可能是原始实例的包装器。
	 * <p>对于FactoryBean，此回调将同时为FactoryBean实例和由FactoryBean创建的对象调用（从Spring 2.0开始）。
	 * 后处理器可以通过相应的{@code bean instanceof FactoryBean}检查来决定是应用于FactoryBean还是创建的对象，或两者都应用。
	 * <p>与所有其他{@code BeanPostProcessor}回调不同，此回调也将在由
	 * {@link InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation}方法触发的短路之后被调用。
	 * <p>默认实现按原样返回给定的{@code bean}。
	 * @param bean 新的bean实例
	 * @param beanName bean的名称
	 * @return 要使用的bean实例，可以是原始的或包装过的；
	 * 如果为{@code null}，则不会调用后续的BeanPostProcessors
	 * @throws org.springframework.beans.BeansException 出错时抛出
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 * @see org.springframework.beans.factory.FactoryBean
	 */
	default @Nullable Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		/* 附加注释：这是一个默认方法，在bean初始化后被调用，常用于创建代理对象。在Spring AOP中，这个方法被用来创建目标对象的代理，以便在方法调用时应用切面逻辑。默认实现直接返回原始bean，不做任何处理 */
		return bean;
	}

}

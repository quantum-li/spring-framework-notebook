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

import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Post-processor callback interface for <i>merged</i> bean definitions at runtime.
 * {@link BeanPostProcessor} implementations may implement this sub-interface in order
 * to post-process the merged bean definition (a processed copy of the original bean
 * definition) that the Spring {@code BeanFactory} uses to create a bean instance.
 *
 * <p>The {@link #postProcessMergedBeanDefinition} method may for example introspect
 * the bean definition in order to prepare some cached metadata before post-processing
 * actual instances of a bean. It is also allowed to modify the bean definition but
 * <i>only</i> for definition properties which are actually intended for concurrent
 * modification. Essentially, this only applies to operations defined on the
 * {@link RootBeanDefinition} itself but not to the properties of its base classes.
 *
 * 运行时<i>合并的</i>bean定义的后处理回调接口。
 * {@link BeanPostProcessor}实现可以实现这个子接口，以便对Spring {@code BeanFactory}用于创建bean实例的
 * 合并bean定义（原始bean定义的处理副本）进行后处理。
 *
 * <p>{@link #postProcessMergedBeanDefinition}方法可以例如检查bean定义，以便在后处理bean的实际实例之前
 * 准备一些缓存的元数据。也允许修改bean定义，但<i>仅限于</i>那些实际上打算进行并发修改的定义属性。
 * 本质上，这只适用于在{@link RootBeanDefinition}本身上定义的操作，而不适用于其基类的属性。
 *
 * @author Juergen Hoeller
 * @since 2.5
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#getMergedBeanDefinition
 */
public interface MergedBeanDefinitionPostProcessor extends BeanPostProcessor {

	/**
	 * Post-process the given merged bean definition for the specified bean.
	 * @param beanDefinition the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @see AbstractAutowireCapableBeanFactory#applyMergedBeanDefinitionPostProcessors
	 *
	 * 对指定bean的给定合并bean定义进行后处理。
	 * @param beanDefinition bean的合并bean定义
	 * @param beanType 管理的bean实例的实际类型
	 * @param beanName bean的名称
	 * @see AbstractAutowireCapableBeanFactory#applyMergedBeanDefinitionPostProcessors
	 */
	/* 附加注释：此方法在Spring容器创建bean实例前被调用，用于处理合并后的bean定义，
	   常用于提取bean中的注解信息、验证bean定义或准备元数据缓存，如@Autowired注解的处理 */
	void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName);

	/**
	 * A notification that the bean definition for the specified name has been reset,
	 * and that this post-processor should clear any metadata for the affected bean.
	 * <p>The default implementation is empty.
	 * @param beanName the name of the bean
	 * @since 5.1
	 * @see DefaultListableBeanFactory#resetBeanDefinition
	 *
	 * 通知指定名称的bean定义已被重置，此后处理器应清除受影响bean的任何元数据。
	 * <p>默认实现为空。
	 * @param beanName bean的名称
	 * @since 5.1
	 * @see DefaultListableBeanFactory#resetBeanDefinition
	 */
	/* 附加注释：当bean定义被重置时（如在Spring应用上下文刷新时），此方法被调用以清除缓存的元数据，
	   确保后续处理使用最新的bean定义信息，默认为空实现，子类可根据需要覆盖 */
	default void resetBeanDefinition(String beanName) {
	}

}

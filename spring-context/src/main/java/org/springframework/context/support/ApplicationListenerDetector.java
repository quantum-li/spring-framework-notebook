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
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code BeanPostProcessor} that detects beans which implement the {@code ApplicationListener}
 * interface. This catches beans that can't reliably be detected by {@code getBeanNamesForType}
 * and related operations which only work against top-level beans.
 *
 * <p>With standard Java serialization, this post-processor won't get serialized as part of
 * {@code DisposableBeanAdapter} to begin with. However, with alternative serialization
 * mechanisms, {@code DisposableBeanAdapter.writeReplace} might not get used at all, so we
 * defensively mark this post-processor's field state as {@code transient}.
 *
 * @author Juergen Hoeller
 * @since 4.3.4
 */
/**
 * {@code BeanPostProcessor}，用于检测实现了{@code ApplicationListener}接口的bean。
 * 这可以捕获那些无法通过{@code getBeanNamesForType}和相关操作可靠检测到的bean，
 * 因为这些操作只对顶级bean有效。
 *
 * <p>使用标准Java序列化时，这个后处理器不会作为{@code DisposableBeanAdapter}的一部分被序列化。
 * 然而，使用替代序列化机制时，{@code DisposableBeanAdapter.writeReplace}可能根本不会被使用，
 * 因此我们防御性地将此后处理器的字段状态标记为{@code transient}。
 *
 * @author Juergen Hoeller
 * @since 4.3.4
 */
class ApplicationListenerDetector implements DestructionAwareBeanPostProcessor, MergedBeanDefinitionPostProcessor {

	private static final Log logger = LogFactory.getLog(ApplicationListenerDetector.class);

	/* 附加注释：存储应用上下文引用，用于后续向其添加或移除ApplicationListener */
	private final transient AbstractApplicationContext applicationContext;

	/* 附加注释：缓存bean名称到其单例状态的映射，使用ConcurrentHashMap保证线程安全，初始容量为256以减少扩容操作 */
	private final transient Map<String, Boolean> singletonNames = new ConcurrentHashMap<>(256);


	public ApplicationListenerDetector(AbstractApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		/* 附加注释：检查bean类型是否实现了ApplicationListener接口，如果是，则记录其单例状态到singletonNames映射中 */
		if (ApplicationListener.class.isAssignableFrom(beanType)) {
			this.singletonNames.put(beanName, beanDefinition.isSingleton());
		}
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof ApplicationListener<?> applicationListener) {
			// potentially not detected as a listener by getBeanNamesForType retrieval
			// 可能无法通过getBeanNamesForType检索被检测为监听器
			/* 附加注释：从singletonNames中获取bean的单例标志，用于判断该bean是否为单例 */
			Boolean flag = this.singletonNames.get(beanName);
			if (Boolean.TRUE.equals(flag)) {
				// singleton bean (top-level or inner): register on the fly
				// 单例bean（顶级或内部）：即时注册
				/* 附加注释：对于单例bean，直接将其作为ApplicationListener添加到ApplicationContext中，确保它能接收事件 */
				this.applicationContext.addApplicationListener(applicationListener);
			}
			else if (Boolean.FALSE.equals(flag)) {
				/* 附加注释：处理非单例的内部bean场景，这种bean不能可靠地接收事件 */
				if (logger.isWarnEnabled() && !this.applicationContext.containsBean(beanName)) {
					// inner bean with other scope - can't reliably process events
					// 具有其他作用域的内部bean - 无法可靠地处理事件
					/* 附加注释：记录警告日志，提示用户内部非单例bean实现ApplicationListener接口但无法被容器可靠地用于事件广播 */
					logger.warn("Inner bean '" + beanName + "' implements ApplicationListener interface " +
							"but is not reachable for event multicasting by its containing ApplicationContext " +
							"because it does not have singleton scope. Only top-level listener beans are allowed " +
							"to be of non-singleton scope.");
				}
				/* 附加注释：从singletonNames缓存中移除该bean名称，因为非单例内部bean不需要被跟踪 */
				this.singletonNames.remove(beanName);
			}
		}
		return bean;
	}

	@Override
	public void postProcessBeforeDestruction(Object bean, String beanName) {
		/* 附加注释：在bean销毁前，如果它是ApplicationListener，则从事件广播器中移除，防止已销毁的bean继续接收事件 */
		if (bean instanceof ApplicationListener<?> applicationListener) {
			try {
				/* 附加注释：获取应用上下文的事件广播器，用于移除监听器 */
				ApplicationEventMulticaster multicaster = this.applicationContext.getApplicationEventMulticaster();
				/* 附加注释：从事件广播器中移除监听器实例和监听器bean名称，确保彻底清除引用 */
				multicaster.removeApplicationListener(applicationListener);
				multicaster.removeApplicationListenerBean(beanName);
			}
			catch (IllegalStateException ex) {
				// ApplicationEventMulticaster not initialized yet - no need to remove a listener
				// ApplicationEventMulticaster尚未初始化 - 无需移除监听器
			}
		}
	}

	@Override
	public boolean requiresDestruction(Object bean) {
		/* 附加注释：判断bean是否需要执行销毁处理，只有ApplicationListener类型的bean才需要 */
		return (bean instanceof ApplicationListener);
	}


	@Override
	public boolean equals(@Nullable Object other) {
		/* 附加注释：重写equals方法，确保相同应用上下文的检测器被视为相等，避免重复注册 */
		return (this == other || (other instanceof ApplicationListenerDetector that &&
				this.applicationContext == that.applicationContext));
	}

	@Override
	public int hashCode() {
		/* 附加注释：重写hashCode方法，与equals方法保持一致，基于applicationContext的hashCode */
		return Objects.hashCode(this.applicationContext);
	}

}

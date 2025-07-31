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

package org.springframework.test.context.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactoryInitializer;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * {@link BeanFactoryInitializer} that eagerly initializes {@link DynamicPropertyRegistrar}
 * beans.
 *
 * <p>Primarily intended for internal use within the Spring TestContext Framework.
 *
 * @author Sam Brannen
 * @since 6.2
 */
/**
 * {@link BeanFactoryInitializer} 用于急切初始化 {@link DynamicPropertyRegistrar} bean。
 *
 * <p>主要用于 Spring TestContext 框架内部使用。
 *
 * @author Sam Brannen
 * @since 6.2
 */
public class DynamicPropertyRegistrarBeanInitializer implements BeanFactoryInitializer<ListableBeanFactory>, EnvironmentAware {

	private static final Log logger = LogFactory.getLog(DynamicPropertyRegistrarBeanInitializer.class);
	/* 附加注释：日志对象，用于记录DynamicPropertyRegistrar bean的初始化过程，在调试和问题排查时提供信息 */

	/**
	 * The bean name of the internally managed {@code DynamicPropertyRegistrarBeanInitializer}.
	 */
	/**
	 * 内部管理的 {@code DynamicPropertyRegistrarBeanInitializer} 的 bean 名称。
	 */
	static final String BEAN_NAME =
			"org.springframework.test.context.support.internalDynamicPropertyRegistrarBeanInitializer";
	/* 附加注释：定义了该初始化器在Spring容器中的唯一标识符，便于其他组件引用和管理 */


	private @Nullable ConfigurableEnvironment environment;
	/* 附加注释：环境配置对象，用于访问和修改应用程序的环境属性，在测试上下文中用于动态注册属性 */


	@Override
	public void setEnvironment(Environment environment) {
		/* 附加注释：实现EnvironmentAware接口的方法，Spring容器会在初始化时自动注入Environment对象 */
		if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
			/* 附加注释：检查注入的环境是否可配置，因为需要动态添加属性源，所以必须是可配置的环境 */
			throw new IllegalArgumentException("Environment must be a ConfigurableEnvironment");
		}
		this.environment = configurableEnvironment;
	}

	@Override
	public void initialize(ListableBeanFactory beanFactory) {
		/* 附加注释：实现BeanFactoryInitializer接口的方法，在BeanFactory初始化时被调用，用于初始化所有DynamicPropertyRegistrar bean */
		if (this.environment == null) {
			/* 附加注释：确保环境已被正确设置，这是动态属性注册的必要条件 */
			throw new IllegalStateException("Environment is required");
		}
		String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				beanFactory, DynamicPropertyRegistrar.class);
		/* 附加注释：查找容器中所有DynamicPropertyRegistrar类型的bean，包括父容器中定义的bean */

		if (beanNames.length > 0) {
			/* 附加注释：只有当存在DynamicPropertyRegistrar类型的bean时才进行处理 */
			DynamicValuesPropertySource propertySource = DynamicValuesPropertySource.getOrCreate(this.environment);
			/* 附加注释：获取或创建动态值属性源，这是一个特殊的属性源，用于存储测试过程中动态注册的属性 */
			DynamicPropertyRegistry registry = propertySource.dynamicPropertyRegistry;
			/* 附加注释：获取动态属性注册表，用于注册动态属性 */

			for (String name : beanNames) {
				/* 附加注释：遍历所有DynamicPropertyRegistrar bean，逐一初始化 */
				if (logger.isDebugEnabled()) {
					logger.debug("Eagerly initializing DynamicPropertyRegistrar bean '%s'".formatted(name));
				}
				DynamicPropertyRegistrar registrar = beanFactory.getBean(name, DynamicPropertyRegistrar.class);
				/* 附加注释：从容器中获取指定名称的DynamicPropertyRegistrar bean实例 */
				registrar.accept(registry);
				/* 附加注释：调用registrar的accept方法，将动态属性注册到registry中，使这些属性在测试运行时可用 */
			}
		}
	}

}

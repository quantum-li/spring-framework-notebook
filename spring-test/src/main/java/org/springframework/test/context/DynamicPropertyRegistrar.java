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

package org.springframework.test.context;

/**
 * Registrar that is used to add properties with dynamically resolved values to
 * the {@code Environment} via a {@link DynamicPropertyRegistry}.
 *
 * <p>Any bean in a test's {@code ApplicationContext} that implements the
 * {@code DynamicPropertyRegistrar} interface will be automatically detected and
 * eagerly initialized before the singleton pre-instantiation phase, and the
 * {@link #accept} methods of such beans will be invoked with a
 * {@code DynamicPropertyRegistry} that performs the actual dynamic property
 * registration on behalf of the registrar.
 *
 * <p>This is an alternative to implementing
 * {@link DynamicPropertySource @DynamicPropertySource} methods in integration
 * test classes and supports additional use cases that are not possible with a
 * {@code @DynamicPropertySource} method. For example, since a
 * {@code DynamicPropertyRegistrar} is itself a bean in the {@code ApplicationContext},
 * it can interact with other beans in the context and register dynamic properties
 * that are sourced from those beans. Note, however, that any interaction with
 * other beans results in eager initialization of those other beans and their
 * dependencies.
 *
 * <h3>Precedence</h3>
 *
 * <p>Dynamic properties have higher precedence than those loaded from
 * {@link TestPropertySource @TestPropertySource}, the operating system's
 * environment, Java system properties, or property sources added by the
 * application declaratively by using
 * {@link org.springframework.context.annotation.PropertySource @PropertySource}
 * or programmatically. Thus, dynamic properties can be used to selectively
 * override properties loaded via {@code @TestPropertySource}, system property
 * sources, and application property sources.
 *
 * <h3>Example</h3>
 *
 * <p>The following example demonstrates how to implement a
 * {@code DynamicPropertyRegistrar} as a lambda expression that registers a
 * dynamic property for the {@code ApiServer} bean. Other beans in the
 * {@code ApplicationContext} can access the {@code api.url} property which is
 * dynamically retrieved from the {@code ApiServer} bean &mdash; for example,
 * via {@code @Value("${api.url}")}.
 *
 * <pre class="code">
 * &#064;Configuration
 * class TestConfig {
 *
 *     &#064;Bean
 *     ApiServer apiServer() {
 *         return new ApiServer();
 *     }
 *
 *     &#064;Bean
 *     DynamicPropertyRegistrar apiPropertiesRegistrar(ApiServer apiServer) {
 *         return registry -> registry.add("api.url", apiServer::getUrl);
 *     }
 *
 * }</pre>
 *
 * @author Sam Brannen
 * @since 6.2
 * @see DynamicPropertySource
 * @see DynamicPropertyRegistry
 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#preInstantiateSingletons()
 *
 * 注册器，用于通过{@link DynamicPropertyRegistry}向{@code Environment}添加具有动态解析值的属性。
 *
 * <p>测试的{@code ApplicationContext}中实现了{@code DynamicPropertyRegistrar}接口的任何bean
 * 都将被自动检测并在单例预实例化阶段之前急切初始化，这些bean的{@link #accept}方法将被调用，
 * 并传入一个{@code DynamicPropertyRegistry}，该注册表代表注册器执行实际的动态属性注册。
 *
 * <p>这是实现集成测试类中{@link DynamicPropertySource @DynamicPropertySource}方法的替代方案，
 * 并支持{@code @DynamicPropertySource}方法无法实现的其他用例。例如，由于{@code DynamicPropertyRegistrar}
 * 本身是{@code ApplicationContext}中的一个bean，它可以与上下文中的其他bean交互，并注册源自这些bean的动态属性。
 * 但请注意，与其他bean的任何交互都会导致这些其他bean及其依赖项的急切初始化。
 *
 * <h3>优先级</h3>
 *
 * <p>动态属性的优先级高于从{@link TestPropertySource @TestPropertySource}、操作系统环境、
 * Java系统属性或应用程序通过使用{@link org.springframework.context.annotation.PropertySource @PropertySource}
 * 声明性地或以编程方式添加的属性源加载的属性。因此，动态属性可用于有选择地覆盖通过{@code @TestPropertySource}、
 * 系统属性源和应用程序属性源加载的属性。
 *
 * <h3>示例</h3>
 *
 * <p>以下示例演示了如何将{@code DynamicPropertyRegistrar}实现为lambda表达式，
 * 该表达式为{@code ApiServer} bean注册动态属性。{@code ApplicationContext}中的其他bean
 * 可以访问从{@code ApiServer} bean动态检索的{@code api.url}属性 &mdash; 例如，
 * 通过{@code @Value("${api.url}")}。
 */
@FunctionalInterface
public interface DynamicPropertyRegistrar {

	/**
	 * Register dynamic properties in the supplied registry.
	 *
	 * 在提供的注册表中注册动态属性。
	 */
	/* 附加注释：此方法是接口的核心功能，允许实现类通过提供的DynamicPropertyRegistry注册动态属性，
	   通常在测试环境中用于注册来自外部资源（如测试容器）的配置属性 */
	void accept(DynamicPropertyRegistry registry);

}

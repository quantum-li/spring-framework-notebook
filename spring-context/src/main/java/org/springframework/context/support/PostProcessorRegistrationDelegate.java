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
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.support.*;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22
		//
		// 警告：虽然看起来这个方法的主体可以很容易地重构以避免使用多个循环和多个列表，
		// 但使用多个列表和多次遍历处理器名称是有意为之的。我们必须确保遵守PriorityOrdered
		// 和Ordered处理器的契约。具体来说，我们绝不能导致处理器以错误的顺序被实例化
		// （通过getBean()调用）或在ApplicationContext中注册。
		//
		// 在提交拉取请求(PR)更改此方法之前，请查看所有被拒绝的涉及更改PostProcessorRegistrationDelegate
		// 的PR列表，以确保您的提议不会导致破坏性变更：
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		// 首先调用BeanDefinitionRegistryPostProcessors（如果有的话）。
		/* 附加注释：processedBeans用于跟踪已处理的后处理器，防止重复处理 */
		Set<String> processedBeans = new HashSet<>();

		/* 附加注释：处理BeanDefinitionRegistry类型的BeanFactory，这种类型允许注册和修改Bean定义 */
		if (beanFactory instanceof BeanDefinitionRegistry registry) {
			/* 附加注释：regularPostProcessors存储普通的BeanFactoryPostProcessor，registryProcessors存储BeanDefinitionRegistryPostProcessor */
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			/* 附加注释：首先处理通过构造函数或其他方式直接提供的后处理器，区分普通处理器和注册处理器 */
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor registryProcessor) {
					/* 附加注释：立即执行注册处理器的registry相关方法，这会修改Bean定义 */
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			// 不要在这里初始化FactoryBeans：我们需要让所有常规bean保持未初始化状态，
			// 以便bean工厂后处理器能够应用于它们！
			// 区分实现了PriorityOrdered、Ordered接口的BeanDefinitionRegistryPostProcessors和其他处理器。
			/* 附加注释：currentRegistryProcessors用于临时存储当前正在处理的一批注册处理器 */
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			// 首先，调用实现了PriorityOrdered接口的BeanDefinitionRegistryPostProcessors。
			/* 附加注释：获取所有BeanDefinitionRegistryPostProcessor类型的Bean名称，包括未初始化的 */
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				/* 附加注释：首先处理实现了PriorityOrdered接口的处理器，这些处理器优先级最高 */
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			/* 附加注释：按照优先级排序处理器，确保高优先级的先执行 */
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			/* 附加注释：执行所有PriorityOrdered处理器的registry方法，可能会修改Bean定义 */
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			// 接下来，调用实现了Ordered接口的BeanDefinitionRegistryPostProcessors。
			/* 附加注释：重新获取处理器名称，因为前面的处理器可能注册了新的处理器 */
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				/* 附加注释：处理实现了Ordered接口但不是PriorityOrdered的处理器，这些处理器优先级次之 */
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			/* 附加注释：执行所有Ordered处理器的registry方法 */
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			// 最后，调用所有其他BeanDefinitionRegistryPostProcessors，直到没有新的出现。
			/* 附加注释：reiterate标志用于控制是否需要重新迭代处理新出现的处理器 */
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					/* 附加注释：处理剩余的既不是PriorityOrdered也不是Ordered的处理器 */
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						/* 附加注释：设置reiterate为true，因为可能有新的处理器被注册 */
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				/* 附加注释：执行所有普通处理器的registry方法 */
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			// 现在，调用到目前为止处理的所有处理器的postProcessBeanFactory回调。
			/* 附加注释：执行所有注册处理器的factory方法，这些方法可以修改BeanFactory本身 */
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			/* 附加注释：执行普通处理器的factory方法 */
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			// 调用在上下文实例中注册的工厂处理器。
			/* 附加注释：如果BeanFactory不是BeanDefinitionRegistry类型，则只执行普通的factory方法 */
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		// 不要在这里初始化FactoryBeans：我们需要让所有常规bean保持未初始化状态，
		// 以便bean工厂后处理器能够应用于它们！
		/* 附加注释：获取所有BeanFactoryPostProcessor类型的Bean名称，包括通过Bean定义注册的 */
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 区分实现了PriorityOrdered、Ordered接口的BeanFactoryPostProcessors和其他处理器。
		/* 附加注释：按照优先级分类存储不同类型的处理器 */
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			/* 附加注释：跳过已经处理过的处理器，避免重复执行 */
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
				// 跳过 - 已经在上面的第一阶段处理过
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		// 首先，调用实现了PriorityOrdered接口的BeanFactoryPostProcessors。
		/* 附加注释：按照优先级排序并执行最高优先级的处理器 */
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		// 接下来，调用实现了Ordered接口的BeanFactoryPostProcessors。
		/* 附加注释：初始化并执行实现了Ordered接口的处理器 */
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		// 最后，调用所有其他BeanFactoryPostProcessors。
		/* 附加注释：初始化并执行剩余的普通处理器，这些处理器没有指定优先级 */
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, for example, replacing placeholders in values...
		// 清除缓存的合并bean定义，因为后处理器可能已经修改了原始元数据，
		// 例如，替换了值中的占位符...
		/* 附加注释：清除元数据缓存，因为后处理器可能修改了Bean定义，需要重新构建缓存 */
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22
		// 警告：虽然这个方法的主体看起来可以很容易地重构以避免使用多个循环和多个列表，但使用多个列表
		// 和多次遍历处理器名称是有意为之的。我们必须确保遵守PriorityOrdered和Ordered处理器的契约。
		// 特别是，我们不能导致处理器以错误的顺序被实例化（通过getBean()调用）或在ApplicationContext中注册。
		//
		// 在提交拉取请求(PR)来更改此方法之前，请查看所有涉及PostProcessorRegistrationDelegate更改的
		// 被拒绝的PR列表，以确保您的提案不会导致破坏性变更：
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22

		/* 附加注释：获取所有BeanPostProcessor类型的Bean名称，包括通过Bean定义注册的，这些处理器将用于处理Bean的实例化和初始化过程 */
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs a warn message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		// 注册BeanPostProcessorChecker，当在BeanPostProcessor实例化过程中创建bean时，
		// 即当bean不符合被所有BeanPostProcessors处理的条件时，记录警告消息。
		/* 附加注释：计算目标处理器数量，用于检测是否有Bean在BeanPostProcessor初始化过程中被创建 */
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		/* 附加注释：添加检查器，用于监控Bean的创建时机，防止Bean错过某些后处理器的处理 */
		beanFactory.addBeanPostProcessor(
				new BeanPostProcessorChecker(beanFactory, postProcessorNames, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 区分实现了PriorityOrdered、Ordered接口的BeanPostProcessors和其他处理器。
		/* 附加注释：按照优先级分类存储不同类型的处理器，确保按正确顺序注册和执行 */
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		/* 附加注释：存储内部处理器，特别是MergedBeanDefinitionPostProcessor类型的处理器，这些处理器需要在最后重新注册 */
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			/* 附加注释：优先处理实现了PriorityOrdered接口的处理器，这些处理器具有最高优先级 */
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				/* 附加注释：如果处理器同时是MergedBeanDefinitionPostProcessor类型，将其添加到内部处理器列表，以便后续重新注册 */
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			/* 附加注释：其次处理实现了Ordered接口的处理器，这些处理器具有中等优先级 */
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			/* 附加注释：最后处理没有实现任何排序接口的普通处理器 */
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		// 首先，注册实现了PriorityOrdered接口的BeanPostProcessors。
		/* 附加注释：对最高优先级的处理器进行排序，确保它们按照正确的顺序执行 */
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		/* 附加注释：注册最高优先级的处理器，这些处理器将首先应用于Bean的处理 */
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		// 接下来，注册实现了Ordered接口的BeanPostProcessors。
		/* 附加注释：初始化并收集实现了Ordered接口的处理器 */
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			/* 附加注释：同样检查是否为MergedBeanDefinitionPostProcessor类型，需要在最后重新注册 */
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		/* 附加注释：对中等优先级的处理器进行排序 */
		sortPostProcessors(orderedPostProcessors, beanFactory);
		/* 附加注释：注册中等优先级的处理器 */
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		// 现在，注册所有常规BeanPostProcessors。
		/* 附加注释：初始化并收集没有实现排序接口的普通处理器 */
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			/* 附加注释：继续检查是否为MergedBeanDefinitionPostProcessor类型 */
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		/* 附加注释：注册普通优先级的处理器，这些处理器将在有序处理器之后执行 */
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		// 最后，重新注册所有内部BeanPostProcessors。
		/* 附加注释：对内部处理器进行排序，这些处理器主要是MergedBeanDefinitionPostProcessor类型 */
		sortPostProcessors(internalPostProcessors, beanFactory);
		/* 附加注释：重新注册内部处理器，确保它们在处理链的末尾执行，这样可以处理所有其他处理器可能产生的代理对象 */
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		// 重新注册用于检测内部bean作为ApplicationListeners的后处理器，
		// 将其移动到处理器链的末尾（用于拾取代理等）。
		/* 附加注释：添加ApplicationListenerDetector处理器，放在处理链最后，用于检测和注册ApplicationListener类型的Bean，确保能处理被代理的监听器 */
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	/**
	 * Load and sort the post-processors of the specified type.
	 * @param beanFactory the bean factory to use
	 * @param beanPostProcessorType the post-processor type
	 * @param <T> the post-processor type
	 * @return a list of sorted post-processors for the specified type
	 */
	static <T extends BeanPostProcessor> List<T> loadBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, Class<T> beanPostProcessorType) {

		String[] postProcessorNames = beanFactory.getBeanNamesForType(beanPostProcessorType, true, false);
		List<T> postProcessors = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			postProcessors.add(beanFactory.getBean(ppName, beanPostProcessorType));
		}
		sortPostProcessors(postProcessors, beanFactory);
		return postProcessors;

	}

	/**
	 * Selectively invoke {@link MergedBeanDefinitionPostProcessor} instances
	 * registered in the specified bean factory, resolving bean definitions and
	 * any attributes if necessary as well as any inner bean definitions that
	 * they may contain.
	 * @param beanFactory the bean factory to use
	 */
	static void invokeMergedBeanDefinitionPostProcessors(DefaultListableBeanFactory beanFactory) {
		new MergedBeanDefinitionPostProcessorInvoker(beanFactory).invokeMergedBeanDefinitionPostProcessors();
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
			comparatorToUse = dlbf.getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanDefinitionRegistry(registry);
			postProcessBeanDefRegistry.end();
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanFactory = beanFactory.getApplicationStartup().start("spring.context.bean-factory.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanFactory(beanFactory);
			postProcessBeanFactory.end();
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 * 注册给定的BeanPostProcessor beans。
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<? extends BeanPostProcessor> postProcessors) {

		/* 附加注释：检查beanFactory是否为AbstractBeanFactory类型，以便使用更高效的批量添加方式 */
		if (beanFactory instanceof AbstractBeanFactory abstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			// 批量添加对我们那里的CopyOnWriteArrayList更有效
			/* 附加注释：AbstractBeanFactory内部使用CopyOnWriteArrayList存储BeanPostProcessor，批量添加比逐个添加更高效，
			   因为CopyOnWriteArrayList的每次写操作都会复制整个数组，批量添加只需要复制一次 */
			abstractBeanFactory.addBeanPostProcessors(postProcessors);
		}
		else {
			/* 附加注释：对于非AbstractBeanFactory类型的工厂，只能逐个添加处理器，
			   这种情况较少见，因为Spring默认使用DefaultListableBeanFactory，它是AbstractBeanFactory的子类 */
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs a warn message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final String[] postProcessorNames;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory,
				String[] postProcessorNames, int beanPostProcessorTargetCount) {

			this.beanFactory = beanFactory;
			this.postProcessorNames = postProcessorNames;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			/* 附加注释：检查当前bean是否有资格被所有BeanPostProcessor处理
			   这个方法主要用于检测在BeanPostProcessor实例化过程中创建的bean，这些bean可能无法被所有的后处理器处理 */
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				/* 附加注释：当前bean不是BeanPostProcessor、不是基础设施bean，且当前注册的后处理器数量少于目标数量时，
				   说明这个bean是在BeanPostProcessor创建过程中被创建的，可能会错过一些后处理 */
				if (logger.isWarnEnabled()) {
					/* 附加注释：收集当前正在创建中的BeanPostProcessor名称，用于日志输出 */
					Set<String> bppsInCreation = new LinkedHashSet<>(2);
					for (String bppName : this.postProcessorNames) {
						if (this.beanFactory.isCurrentlyInCreation(bppName)) {
							bppsInCreation.add(bppName);
						}
					}
					/* 附加注释：特殊场景处理：当只有一个BeanPostProcessor正在创建时 */
					if (bppsInCreation.size() == 1) {
						String bppName = bppsInCreation.iterator().next();
						/* 附加注释：检查当前bean是否是通过非静态工厂方法创建的BeanPostProcessor，
						   这种情况下会建议将工厂方法声明为静态的 */
						if (this.beanFactory.containsBeanDefinition(bppName) &&
								beanName.equals(this.beanFactory.getBeanDefinition(bppName).getFactoryBeanName())) {
							logger.warn("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
									"] is not eligible for getting processed by all BeanPostProcessors " +
									"(for example: not eligible for auto-proxying). The currently created " +
									"BeanPostProcessor " + bppsInCreation + " is declared through a non-static " +
									"factory method on that class; consider declaring it as static instead.");
							/* 附加注释：警告日志已输出，直接返回bean，不做进一步处理 */
							return bean;
						}
					}
					/* 附加注释：一般场景的警告日志，提示用户这个bean可能无法被所有BeanPostProcessor处理，
					   并建议如果不需要后处理，可以将其声明为ROLE_INFRASTRUCTURE */
					logger.warn("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying). Is this bean getting eagerly " +
							"injected/applied to a currently created BeanPostProcessor " + bppsInCreation + "? " +
							"Check the corresponding BeanPostProcessor declaration and its dependencies/advisors. " +
							"If this bean does not have to be post-processed, declare it with ROLE_INFRASTRUCTURE.");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}


	private static final class MergedBeanDefinitionPostProcessorInvoker {

		private final DefaultListableBeanFactory beanFactory;

		private MergedBeanDefinitionPostProcessorInvoker(DefaultListableBeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		private void invokeMergedBeanDefinitionPostProcessors() {
			List<MergedBeanDefinitionPostProcessor> postProcessors = PostProcessorRegistrationDelegate.loadBeanPostProcessors(
					this.beanFactory, MergedBeanDefinitionPostProcessor.class);
			for (String beanName : this.beanFactory.getBeanDefinitionNames()) {
				RootBeanDefinition bd = (RootBeanDefinition) this.beanFactory.getMergedBeanDefinition(beanName);
				Class<?> beanType = resolveBeanType(bd);
				postProcessRootBeanDefinition(postProcessors, beanName, beanType, bd);
				bd.markAsPostProcessed();
			}
			registerBeanPostProcessors(this.beanFactory, postProcessors);
		}

		private void postProcessRootBeanDefinition(List<MergedBeanDefinitionPostProcessor> postProcessors,
				String beanName, Class<?> beanType, RootBeanDefinition bd) {

			BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this.beanFactory, beanName, bd);
			postProcessors.forEach(postProcessor -> postProcessor.postProcessMergedBeanDefinition(bd, beanType, beanName));
			for (PropertyValue propertyValue : bd.getPropertyValues().getPropertyValueList()) {
				postProcessValue(postProcessors, valueResolver, propertyValue.getValue());
			}
			for (ValueHolder valueHolder : bd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
				postProcessValue(postProcessors, valueResolver, valueHolder.getValue());
			}
			for (ValueHolder valueHolder : bd.getConstructorArgumentValues().getGenericArgumentValues()) {
				postProcessValue(postProcessors, valueResolver, valueHolder.getValue());
			}
		}

		private void postProcessValue(List<MergedBeanDefinitionPostProcessor> postProcessors,
				BeanDefinitionValueResolver valueResolver, @Nullable Object value) {
			if (value instanceof BeanDefinitionHolder bdh &&
					bdh.getBeanDefinition() instanceof AbstractBeanDefinition innerBd) {

				Class<?> innerBeanType = resolveBeanType(innerBd);
				resolveInnerBeanDefinition(valueResolver, innerBd, (innerBeanName, innerBeanDefinition)
						-> postProcessRootBeanDefinition(postProcessors, innerBeanName, innerBeanType, innerBeanDefinition));
			}
			else if (value instanceof AbstractBeanDefinition innerBd) {
				Class<?> innerBeanType = resolveBeanType(innerBd);
				resolveInnerBeanDefinition(valueResolver, innerBd, (innerBeanName, innerBeanDefinition)
						-> postProcessRootBeanDefinition(postProcessors, innerBeanName, innerBeanType, innerBeanDefinition));
			}
			else if (value instanceof TypedStringValue typedStringValue) {
				resolveTypeStringValue(typedStringValue);
			}
		}

		private void resolveInnerBeanDefinition(BeanDefinitionValueResolver valueResolver, BeanDefinition innerBeanDefinition,
				BiConsumer<String, RootBeanDefinition> resolver) {

			valueResolver.resolveInnerBean(null, innerBeanDefinition, (name, rbd) -> {
				resolver.accept(name, rbd);
				return Void.class;
			});
		}

		private void resolveTypeStringValue(TypedStringValue typedStringValue) {
			try {
				typedStringValue.resolveTargetType(this.beanFactory.getBeanClassLoader());
			}
			catch (ClassNotFoundException ignored) {
			}
		}

		private Class<?> resolveBeanType(AbstractBeanDefinition bd) {
			if (!bd.hasBeanClass()) {
				try {
					bd.resolveBeanClass(this.beanFactory.getBeanClassLoader());
				}
				catch (ClassNotFoundException ex) {
					// ignore
				}
			}
			return bd.getResolvableType().toClass();
		}
	}

}

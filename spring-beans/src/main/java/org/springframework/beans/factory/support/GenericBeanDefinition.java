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
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.util.ObjectUtils;

/**
 * GenericBeanDefinition is a one-stop shop for declarative bean definition purposes.
 * Like all common bean definitions, it allows for specifying a class plus optionally
 * constructor argument values and property values. Additionally, deriving from a
 * parent bean definition can be flexibly configured through the "parentName" property.
 *
 * <p>In general, use this {@code GenericBeanDefinition} class for the purpose of
 * registering declarative bean definitions (for example, XML definitions which a bean
 * post-processor might operate on, potentially even reconfiguring the parent name).
 * Use {@code RootBeanDefinition}/{@code ChildBeanDefinition} where parent/child
 * relationships happen to be pre-determined, and prefer {@link RootBeanDefinition}
 * specifically for programmatic definitions derived from factory methods/suppliers.
 *
 * @author Juergen Hoeller
 * @since 2.5
 * @see #setParentName
 * @see RootBeanDefinition
 * @see ChildBeanDefinition
 *
 * GenericBeanDefinition是用于声明式Bean定义目的的一站式解决方案。
 * 像所有常见的Bean定义一样，它允许指定一个类以及可选的构造函数参数值和属性值。
 * 此外，可以通过"parentName"属性灵活配置从父Bean定义派生的关系。
 *
 * 通常，使用这个{@code GenericBeanDefinition}类来注册声明式Bean定义
 * （例如，Bean后处理器可能操作的XML定义，甚至可能重新配置父名称）。
 * 当父/子关系是预先确定的情况下，使用{@code RootBeanDefinition}/{@code ChildBeanDefinition}，
 * 并且对于从工厂方法/供应商派生的编程定义，特别推荐使用{@link RootBeanDefinition}。
 */
@SuppressWarnings("serial")
public class
GenericBeanDefinition extends AbstractBeanDefinition {

	/* 附加注释：parentName用于存储父Bean的名称，在Bean继承场景中使用，允许当前Bean定义继承父Bean的属性和配置 */
	private @Nullable String parentName;


	/**
	 * Create a new GenericBeanDefinition, to be configured through its bean
	 * properties and configuration methods.
	 * @see #setBeanClass
	 * @see #setScope
	 * @see #setConstructorArgumentValues
	 * @see #setPropertyValues
	 *
	 * 创建一个新的GenericBeanDefinition，通过其Bean属性和配置方法进行配置。
	 * @see #setBeanClass
	 * @see #setScope
	 * @see #setConstructorArgumentValues
	 * @see #setPropertyValues
	 */
	public GenericBeanDefinition() {
		super();
	}

	/**
	 * Create a new GenericBeanDefinition as deep copy of the given
	 * bean definition.
	 * @param original the original bean definition to copy from
	 *
	 * 创建一个新的GenericBeanDefinition，作为给定Bean定义的深拷贝。
	 * @param original 要复制的原始Bean定义
	 */
	public GenericBeanDefinition(BeanDefinition original) {
		/* 附加注释：调用父类构造函数进行深拷贝，确保所有属性都被正确复制，而不是简单的引用复制 */
		super(original);
	}


	@Override
	public void setParentName(@Nullable String parentName) {
		/* 附加注释：设置父Bean的名称，用于实现Bean定义的继承关系 */
		this.parentName = parentName;
	}

	@Override
	public @Nullable String getParentName() {
		/* 附加注释：获取父Bean的名称，在解析Bean定义时用于查找父Bean定义 */
		return this.parentName;
	}


	@Override
	public AbstractBeanDefinition cloneBeanDefinition() {
		/* 附加注释：创建当前Bean定义的克隆，通过复制构造函数实现深拷贝，用于Bean定义的复制场景 */
		return new GenericBeanDefinition(this);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		/* 附加注释：重写equals方法，比较两个GenericBeanDefinition是否相等，需要比较parentName和其他继承自父类的属性 */
		return (this == other || (other instanceof GenericBeanDefinition that &&
				ObjectUtils.nullSafeEquals(this.parentName, that.parentName) && super.equals(other)));
	}

	@Override
	public String toString() {
		/* 附加注释：根据是否有父Bean名称，生成不同的字符串表示形式，便于调试和日志输出 */
		if (this.parentName != null) {
			return "Generic bean with parent '" + this.parentName + "': " + super.toString();
		}
		return "Generic bean: " + super.toString();
	}

}

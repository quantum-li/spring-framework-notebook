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
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.util.ObjectUtils;

/**
 * Bean definition for beans which inherit settings from their parent.
 * Child bean definitions have a fixed dependency on a parent bean definition.
 *
 * 用于从父Bean继承设置的Bean定义。
 * 子Bean定义对父Bean定义有固定的依赖关系。
 *
 * <p>A child bean definition will inherit constructor argument values,
 * property values and method overrides from the parent, with the option
 * to add new values. If init method, destroy method and/or static factory
 * method are specified, they will override the corresponding parent settings.
 * The remaining settings will <i>always</i> be taken from the child definition:
 * depends on, autowire mode, dependency check, singleton, lazy init.
 *
 * <p>子Bean定义将从父Bean继承构造函数参数值、属性值和方法重写，并可选择添加新值。
 * 如果指定了初始化方法、销毁方法和/或静态工厂方法，它们将覆盖相应的父设置。
 * 其余设置将<i>始终</i>取自子定义：依赖关系、自动装配模式、依赖检查、单例、懒加载初始化。
 *
 * <p><b>NOTE:</b> Since Spring 2.5, the preferred way to register bean
 * definitions programmatically is the {@link GenericBeanDefinition} class,
 * which allows to dynamically define parent dependencies through the
 * {@link GenericBeanDefinition#setParentName} method. This effectively
 * supersedes the ChildBeanDefinition class for most use cases.
 *
 * <p><b>注意：</b>自Spring 2.5起，以编程方式注册Bean定义的首选方式是{@link GenericBeanDefinition}类，
 * 它允许通过{@link GenericBeanDefinition#setParentName}方法动态定义父依赖关系。
 * 这有效地取代了大多数用例中的ChildBeanDefinition类。
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see GenericBeanDefinition
 * @see RootBeanDefinition
 */
@SuppressWarnings("serial")
public class ChildBeanDefinition extends AbstractBeanDefinition {

	/* 附加注释：parentName是子Bean定义中最关键的属性，用于指定父Bean的名称，实现Bean定义的继承关系 */
	private @Nullable String parentName;


	/**
	 * Create a new ChildBeanDefinition for the given parent, to be
	 * configured through its bean properties and configuration methods.
	 * @param parentName the name of the parent bean
	 * @see #setBeanClass
	 * @see #setScope
	 * @see #setConstructorArgumentValues
	 * @see #setPropertyValues
	 *
	 * 为给定的父Bean创建一个新的ChildBeanDefinition，通过其Bean属性和配置方法进行配置。
	 * @param parentName 父Bean的名称
	 */
	public ChildBeanDefinition(String parentName) {
		super();
		this.parentName = parentName;
	}

	/**
	 * Create a new ChildBeanDefinition for the given parent.
	 * @param parentName the name of the parent bean
	 * @param pvs the additional property values of the child
	 *
	 * 为给定的父Bean创建一个新的ChildBeanDefinition。
	 * @param parentName 父Bean的名称
	 * @param pvs 子Bean的附加属性值
	 */
	public ChildBeanDefinition(String parentName, MutablePropertyValues pvs) {
		/* 附加注释：调用父类构造函数时传入null作为构造参数值，表示不指定构造参数，只设置属性值 */
		super(null, pvs);
		this.parentName = parentName;
	}

	/**
	 * Create a new ChildBeanDefinition for the given parent.
	 * @param parentName the name of the parent bean
	 * @param cargs the constructor argument values to apply
	 * @param pvs the additional property values of the child
	 *
	 * 为给定的父Bean创建一个新的ChildBeanDefinition。
	 * @param parentName 父Bean的名称
	 * @param cargs 要应用的构造函数参数值
	 * @param pvs 子Bean的附加属性值
	 */
	public ChildBeanDefinition(
			String parentName, ConstructorArgumentValues cargs, MutablePropertyValues pvs) {

		super(cargs, pvs);
		this.parentName = parentName;
	}

	/**
	 * Create a new ChildBeanDefinition for the given parent,
	 * providing constructor arguments and property values.
	 * @param parentName the name of the parent bean
	 * @param beanClass the class of the bean to instantiate
	 * @param cargs the constructor argument values to apply
	 * @param pvs the property values to apply
	 *
	 * 为给定的父Bean创建一个新的ChildBeanDefinition，提供构造函数参数和属性值。
	 * @param parentName 父Bean的名称
	 * @param beanClass 要实例化的Bean的类
	 * @param cargs 要应用的构造函数参数值
	 * @param pvs 要应用的属性值
	 */
	public ChildBeanDefinition(
			String parentName, Class<?> beanClass, ConstructorArgumentValues cargs, MutablePropertyValues pvs) {

		super(cargs, pvs);
		this.parentName = parentName;
		/* 附加注释：显式设置Bean类，这样可以直接指定类而不是类名，避免后续的类加载过程 */
		setBeanClass(beanClass);
	}

	/**
	 * Create a new ChildBeanDefinition for the given parent,
	 * providing constructor arguments and property values.
	 * Takes a bean class name to avoid eager loading of the bean class.
	 * @param parentName the name of the parent bean
	 * @param beanClassName the name of the class to instantiate
	 * @param cargs the constructor argument values to apply
	 * @param pvs the property values to apply
	 *
	 * 为给定的父Bean创建一个新的ChildBeanDefinition，提供构造函数参数和属性值。
	 * 采用Bean类名以避免急切加载Bean类。
	 * @param parentName 父Bean的名称
	 * @param beanClassName 要实例化的类的名称
	 * @param cargs 要应用的构造函数参数值
	 * @param pvs 要应用的属性值
	 */
	public ChildBeanDefinition(
			String parentName, String beanClassName, ConstructorArgumentValues cargs, MutablePropertyValues pvs) {

		super(cargs, pvs);
		this.parentName = parentName;
		/* 附加注释：设置Bean类名而不是类对象，实现延迟加载，提高性能 */
		setBeanClassName(beanClassName);
	}

	/**
	 * Create a new ChildBeanDefinition as deep copy of the given
	 * bean definition.
	 * @param original the original bean definition to copy from
	 *
	 * 创建一个新的ChildBeanDefinition作为给定Bean定义的深拷贝。
	 * @param original 要复制的原始Bean定义
	 */
	public ChildBeanDefinition(ChildBeanDefinition original) {
		/* 附加注释：通过调用父类的拷贝构造函数实现深拷贝，确保所有属性都被正确复制 */
		super(original);
	}


	@Override
	public void setParentName(@Nullable String parentName) {
		this.parentName = parentName;
	}

	@Override
	public @Nullable String getParentName() {
		return this.parentName;
	}

	@Override
	public void validate() throws BeanDefinitionValidationException {
		/* 附加注释：首先调用父类的验证方法，确保基本的Bean定义验证通过 */
		super.validate();
		/* 附加注释：检查parentName是否为null，这是ChildBeanDefinition的核心验证，因为子Bean必须有父Bean */
		if (this.parentName == null) {
			throw new BeanDefinitionValidationException("'parentName' must be set in ChildBeanDefinition");
		}
	}


	@Override
	public AbstractBeanDefinition cloneBeanDefinition() {
		/* 附加注释：通过拷贝构造函数创建一个新的实例，实现Bean定义的克隆 */
		return new ChildBeanDefinition(this);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		/* 附加注释：实现equals方法，首先检查引用相等，然后检查类型和parentName属性，最后调用父类equals方法比较其他属性 */
		return (this == other || (other instanceof ChildBeanDefinition that &&
				ObjectUtils.nullSafeEquals(this.parentName, that.parentName) && super.equals(other)));
	}

	@Override
	public int hashCode() {
		/* 附加注释：结合parentName和父类的hashCode计算哈希值，乘以29是为了减少哈希冲突 */
		return ObjectUtils.nullSafeHashCode(this.parentName) * 29 + super.hashCode();
	}

	@Override
	public String toString() {
		/* 附加注释：提供有意义的字符串表示，包含父Bean名称和父类的toString结果 */
		return "Child bean with parent '" + this.parentName + "': " + super.toString();
	}

}

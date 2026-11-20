package com.mingliqiye.web.netty.server.io

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命名线程工厂：按 [getName] 规则为创建的线程命名，便于日志与线程 dump 定位。
 *
 * @param getName 线程名称生成回调
 */
open class NamedThreadFactory(private val getName: NamedThreadFactoryNameGetter) : ThreadFactory {
	/** 伴生对象，提供线程名称生成器接口与工厂方法 */
	companion object {
		@JvmStatic
		private val allThreadPoolNumber = AtomicInteger(0)

		/** 函数式接口，用于定义线程名称生成规则。 */
		@FunctionalInterface
		fun interface NamedThreadFactoryNameGetter {
			/**
			 * 获取线程名称。
			 *
			 * @param clazz 线程工厂类
			 * @param poolNumber 线程池编号（全局递增）
			 * @param threadNumber 线程编号（池内递增）
			 * @return 生成的线程名称
			 */
			fun getName(clazz: Class<out NamedThreadFactory>, poolNumber: Int, threadNumber: Int): String
		}

		/** 默认的线程名称生成器：`类名-池编号-线程编号`。 */
		@JvmStatic
		val defaultGetName =
			NamedThreadFactoryNameGetter { clazz, poolNumber, threadNumber -> "${clazz.simpleName}-$poolNumber-$threadNumber" }

		/**
		 * 创建命名线程工厂实例。
		 *
		 * @param name 线程名称前缀，最终名称为 `前缀-线程编号`
		 * @return NamedThreadFactory 实例
		 */
		@JvmStatic
		fun of(name: String): NamedThreadFactory {
			return NamedThreadFactory { a, b, c ->
				"$name-$c"
			}
		}

		/**
		 * 创建命名线程工厂实例。
		 *
		 * @param getter 线程名称生成器，默认使用 [defaultGetName]
		 * @return NamedThreadFactory 实例
		 */
		@JvmStatic
		fun of(getter: NamedThreadFactoryNameGetter = defaultGetName): NamedThreadFactory {
			return NamedThreadFactory(getter)
		}
	}

	private val threadNumber = AtomicInteger(0)
	private val threadPoolNumber = allThreadPoolNumber.addAndGet(1)

	/**
	 * 获取线程名称。
	 *
	 * @param clazz 线程工厂类
	 * @param poolNumber 线程池编号
	 * @param threadNumber 线程编号
	 * @return 生成的线程名称
	 */
	open fun getThreadName(clazz: Class<out NamedThreadFactory>, poolNumber: Int, threadNumber: Int) =
		getName.getName(clazz, poolNumber, threadNumber)

	/**
	 * 创建新线程并按命名规则设置线程名。
	 *
	 * @param r 线程执行的任务
	 * @return 新创建的线程对象
	 */
	override fun newThread(r: Runnable): Thread {
		return Thread(r).apply {
			name = getThreadName(this@NamedThreadFactory.javaClass, threadPoolNumber, threadNumber.addAndGet(1))
		}
	}
}

package com.mingliqiye.web.netty.servlet.core

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.Servlet
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse

/**
 * Filter 链执行器：按序调用 Filter，链尾调用目标 Servlet。
 * Filter 不调用 chain.doFilter 即中断链路（如鉴权拦截）。
 */
internal class FilterChainImpl(
	private val filters: List<Filter>,
	private val servlet: Servlet,
) : FilterChain {
	private var index = 0

	override fun doFilter(request: ServletRequest, response: ServletResponse) {
		if (index < filters.size) {
			val filter = filters[index++]
			filter.doFilter(request, response, this)
		} else {
			servlet.service(request, response)
		}
	}
}
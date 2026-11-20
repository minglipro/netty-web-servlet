package com.mingliqiye.web.netty.servlet.resources

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.File
import java.io.RandomAccessFile
import java.net.URL

/**
 * 静态资源默认 Servlet（映射 `/`）：docRoot 文件系统优先、类路径回退。
 *
 * 能力：GET/HEAD、Content-Type（ServletContext.getMimeType）、Content-Length、Last-Modified、
 * If-Modified-Since → 304、单段 Range → 206（不满足 → 416）、目录欢迎页、可选目录列表。
 *
 * 初始化参数：
 * - `listings`：true 时目录无欢迎页则输出 HTML 列表（默认 false，回 404）；
 * - `welcomeFiles`：逗号分隔欢迎文件名（默认 `index.html,index.htm`）。
 *
 * 配置 [com.mingliqiye.web.netty.servlet.config.ServletServerConfig.docRoot] 后容器自动注册本 Servlet；
 * 也可手动注册到任意路径以提供类路径静态资源。
 */
class DefaultServlet : HttpServlet() {
	private var listings = false
	private var welcomeFiles = listOf("index.html", "index.htm")

	override fun init() {
		listings = getInitParameter("listings")?.toBoolean() ?: false
		getInitParameter("welcomeFiles")?.let { value ->
			welcomeFiles = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
		}
	}

	override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = serve(req, resp, true)

	override fun doHead(req: HttpServletRequest, resp: HttpServletResponse) = serve(req, resp, false)

	private fun serve(req: HttpServletRequest, resp: HttpServletResponse, includeBody: Boolean) {
		val relative = (req.pathInfo ?: req.servletPath).removePrefix("/")
		if (relative.split('/').any { it == ".." }) {
			resp.sendError(400)
			return
		}
		servePath(req, resp, "/$relative", includeBody)
	}

	private fun servePath(req: HttpServletRequest, resp: HttpServletResponse, path: String, includeBody: Boolean) {
		val file = servletContext.getRealPath(path)?.let { File(it) }
		if (file != null && file.isDirectory) {
			for (welcome in welcomeFiles) {
				val candidate = File(file, welcome)
				if (candidate.isFile) {
					serveFile(req, resp, candidate, includeBody)
					return
				}
			}
			if (listings) {
				renderListing(resp, path, file)
			} else {
				resp.sendError(404)
			}
			return
		}
		if (file != null && file.isFile) {
			serveFile(req, resp, file, includeBody)
			return
		}
		val url = servletContext.getResource(path)
		if (url == null) {
			resp.sendError(404)
			return
		}
		if (isDirectory(url, path)) {
			for (welcome in welcomeFiles) {
				val candidate = servletContext.getResource("$path/$welcome".replace("//", "/"))
				if (candidate != null) {
					serveUrl(req, resp, candidate, includeBody)
					return
				}
			}
			resp.sendError(404)
			return
		}
		serveUrl(req, resp, url, includeBody)
	}

	private fun isDirectory(url: URL, path: String): Boolean {
		if (path.endsWith("/")) return true
		if (url.protocol == "file") {
			return runCatching { File(url.toURI()).isDirectory }.getOrDefault(false)
		}
		return url.path.endsWith("/")
	}

	private fun serveFile(req: HttpServletRequest, resp: HttpServletResponse, file: File, includeBody: Boolean) {
		val length = file.length()
		val lastModified = file.lastModified()
		applyHeaders(resp, file.name, length, lastModified)
		if (notModified(req, lastModified)) {
			resp.status = 304
			return
		}
		val range = req.getHeader("Range")?.let { parseRange(it, length) }
		if (req.getHeader("Range") != null && range == null) {
			resp.status = 416
			resp.setHeader("Content-Range", "bytes */$length")
			return
		}
		if (range != null) {
			resp.status = 206
			resp.setHeader("Content-Range", "bytes ${range.first}-${range.last}/$length")
			resp.setContentLengthLong(range.last - range.first + 1)
			if (includeBody) {
				RandomAccessFile(file, "r").use { raf ->
					raf.seek(range.first)
					copyLimited(raf, resp, range.last - range.first + 1)
				}
			}
			return
		}
		resp.setContentLengthLong(length)
		if (includeBody) {
			file.inputStream().use { it.copyTo(resp.outputStream) }
		}
	}

	private fun serveUrl(req: HttpServletRequest, resp: HttpServletResponse, url: URL, includeBody: Boolean) {
		val connection = url.openConnection()
		val length = connection.contentLengthLong
		val lastModified = connection.lastModified
		applyHeaders(resp, url.path.substringAfterLast('/'), length, lastModified)
		if (lastModified > 0 && notModified(req, lastModified)) {
			resp.status = 304
			return
		}
		if (length >= 0) {
			resp.setContentLengthLong(length)
		}
		if (includeBody) {
			connection.getInputStream().use { it.copyTo(resp.outputStream) }
		}
	}

	private fun applyHeaders(resp: HttpServletResponse, name: String, length: Long, lastModified: Long) {
		servletContext.getMimeType(name)?.let { resp.contentType = it }
		resp.setHeader("Accept-Ranges", "bytes")
		if (lastModified > 0) {
			resp.setDateHeader("Last-Modified", lastModified)
		}
	}

	private fun notModified(req: HttpServletRequest, lastModified: Long): Boolean {
		val ims = runCatching { req.getDateHeader("If-Modified-Since") }.getOrDefault(-1L)
		return ims >= 0 && lastModified / 1000 <= ims / 1000
	}

	/** 解析单段 bytes Range（start-end / start- / -suffix），不满足或非法返回 null。 */
	private fun parseRange(header: String, length: Long): LongRange? {
		if (!header.startsWith("bytes=") || length == 0L) return null
		val spec = header.removePrefix("bytes=").substringBefore(',').trim()
		val start: Long
		val end: Long
		when {
			spec.startsWith("-") -> {
				val suffix = spec.substring(1).toLongOrNull() ?: return null
				if (suffix <= 0) return null
				start = (length - suffix).coerceAtLeast(0)
				end = length - 1
			}
			spec.endsWith("-") -> {
				start = spec.dropLast(1).toLongOrNull() ?: return null
				end = length - 1
			}
			else -> {
				val parts = spec.split('-')
				if (parts.size != 2) return null
				start = parts[0].toLongOrNull() ?: return null
				end = parts[1].toLongOrNull() ?: return null
			}
		}
		if (start < 0 || start >= length || end < start) return null
		return start..end.coerceAtMost(length - 1)
	}

	private fun copyLimited(raf: RandomAccessFile, resp: HttpServletResponse, count: Long) {
		val buffer = ByteArray(8192)
		var remaining = count
		val out = resp.outputStream
		while (remaining > 0) {
			val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
			if (read < 0) break
			out.write(buffer, 0, read)
			remaining -= read
		}
	}

	private fun renderListing(resp: HttpServletResponse, path: String, dir: File) {
		resp.contentType = "text/html;charset=UTF-8"
		val writer = resp.writer
		writer.write("<html><head><title>Index of $path</title></head><body>")
		writer.write("<h1>Index of $path</h1><ul>")
		if (path != "/") {
			writer.write("<li><a href=\"../\">../</a></li>")
		}
		dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { entry ->
			val name = entry.name + if (entry.isDirectory) "/" else ""
			writer.write("<li><a href=\"$name\">$name</a></li>")
		}
		writer.write("</ul></body></html>")
	}
}
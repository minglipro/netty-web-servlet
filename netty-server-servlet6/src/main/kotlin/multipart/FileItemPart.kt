package com.mingliqiye.web.netty.servlet.multipart

import jakarta.servlet.http.Part
import org.apache.commons.fileupload2.core.FileItem
import java.io.InputStream
import java.nio.file.Path

/** 把 commons-fileupload2 的 [FileItem] 适配为 Servlet [Part]（文件 part，表单字段已并入请求参数）。 */
internal class FileItemPart(private val item: FileItem<*>) : Part {
	override fun getInputStream(): InputStream = item.inputStream

	override fun getContentType(): String? = item.contentType

	override fun getName(): String = item.fieldName

	override fun getSubmittedFileName(): String? = item.name

	override fun getSize(): Long = item.size

	override fun write(fileName: String) {
		item.write(Path.of(fileName))
	}

	override fun delete() {
		item.delete()
	}

	override fun getHeader(name: String): String? = item.headers.getHeader(name)

	override fun getHeaders(name: String): Collection<String> = item.headers.getHeaders(name).asSequence().toList()

	override fun getHeaderNames(): Collection<String> = item.headers.headerNames.asSequence().toList()
}
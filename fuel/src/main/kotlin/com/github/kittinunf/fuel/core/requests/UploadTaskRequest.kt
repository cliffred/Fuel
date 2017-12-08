package com.github.kittinunf.fuel.core.requests

import com.github.kittinunf.fuel.core.Blob
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.util.copyTo
import java.io.OutputStream
import java.net.URL
import java.net.URLConnection

internal class UploadTaskRequest(request: Request) : TaskRequest(request) {
    var progressCallback: ((Long, Long) -> Unit)? = null
    lateinit var sourceCallback: (Request, URL) -> Iterable<Blob>

    private var bodyCallBack = fun(request: Request, outputStream: OutputStream?, totalLength: Long): Long {
        var contentLength = 0L
        outputStream.apply {
            val files = sourceCallback(request, request.url)

            if (request.sourcesLast) {
                contentLength = addParameters(request, contentLength)
                contentLength = addSources(files, request, contentLength, totalLength)
            } else {
                contentLength = addSources(files, request, contentLength, totalLength)
                contentLength = addParameters(request, contentLength)
            }

            contentLength += write("--$boundary--")
            contentLength += writeln()
        }

        progressCallback?.invoke(contentLength, totalLength)
        return contentLength
    }

    private fun OutputStream?.addParameters(request: Request, contentLength: Long): Long {
        var contentLengthVar = contentLength
        request.parameters.forEach { (name, data) ->
            contentLengthVar += write("--$boundary")
            contentLengthVar += writeln()
            contentLengthVar += write("Content-Disposition: form-data; name=\"$name\"")
            contentLengthVar += writeln()
            contentLengthVar += write("Content-Type: text/plain")
            contentLengthVar += writeln()
            contentLengthVar += writeln()
            contentLengthVar += write(data.toString())
            contentLengthVar += writeln()
        }
        return contentLengthVar
    }

    private fun OutputStream?.addSources(files: Iterable<Blob>, request: Request, contentLength: Long, totalLength: Long): Long {
        var contentLengthVar = contentLength
        files.forEachIndexed { i, (name, length, inputStream) ->
            val postFix = if (files.count() == 1) "" else "${i + 1}"
            val fieldName = request.names.getOrElse(i) { request.name + postFix }

            contentLengthVar += write("--$boundary")
            contentLengthVar += writeln()
            contentLengthVar += write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$name\"")
            contentLengthVar += writeln()
            contentLengthVar += write("Content-Type: " + request.mediaTypes.getOrElse(i) { guessContentType(name) })
            contentLengthVar += writeln()
            contentLengthVar += writeln()

            //input file data
            if (this != null) {
                inputStream().use {
                    it.copyTo(this, BUFFER_SIZE) { writtenBytes ->
                        progressCallback?.invoke(contentLengthVar + writtenBytes, totalLength)
                    }
                }
            }
            contentLengthVar += length
            contentLengthVar += writeln()
        }
        return contentLengthVar
    }

    private val boundary = request.headers["Content-Type"]?.split("=", limit = 2)?.get(1) ?: System.currentTimeMillis().toString(16)

    init {
        request.bodyCallback = bodyCallBack
    }
}

fun OutputStream?.write(str: String): Int {
    val data = str.toByteArray()
    this?.write(data)
    return data.size
}

fun OutputStream?.writeln(): Int {
    this?.write(CRLF)
    return CRLF.size
}

private const val BUFFER_SIZE = 1024
private val CRLF = "\r\n".toByteArray()

private fun guessContentType(name: String): String = try {
    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
} catch (ex: NoClassDefFoundError) {
    // The MimetypesFileTypeMap class doesn't exists on old Android devices.
    "application/octet-stream"
}

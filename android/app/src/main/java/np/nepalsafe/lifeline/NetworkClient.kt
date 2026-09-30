package np.nepalsafe.lifeline

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object NetworkClient {
    fun getJson(url: String, timeoutMs: Int = 15_000): JSONObject =
        requestJson("GET", url, null, timeoutMs)

    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 20_000): JSONObject =
        requestJson("POST", url, body.toString().toByteArray(StandardCharsets.UTF_8), timeoutMs)

    fun postMultipart(
        url: String,
        imageFile: File,
        fields: Map<String, String>,
        timeoutMs: Int = 45_000
    ): JSONObject {
        val boundary = "NepalSafe-${System.currentTimeMillis()}"
        val connection = open(url, timeoutMs).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.buffered().use { output ->
            fields.forEach { (name, value) ->
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.write("\r\n".toByteArray())
            }
            output.write("--$boundary\r\n".toByteArray())
            output.write(
                "Content-Disposition: form-data; name=\"image\"; filename=\"${imageFile.name}\"\r\n".toByteArray()
            )
            output.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
            imageFile.inputStream().use { it.copyTo(output) }
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return readJsonResponse(connection)
    }

    private fun requestJson(method: String, url: String, body: ByteArray?, timeoutMs: Int): JSONObject {
        val connection = open(url, timeoutMs).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body) }
            }
        }
        return readJsonResponse(connection)
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
        }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        return try {
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Server returned $status${if (text.isBlank()) "" else ": ${text.take(240)}"}")
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}

package app.pingvanta.monitor

import app.pingvanta.data.MonitorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Result of one real network check. */
data class CheckOutcome(
    val up: Boolean,
    val responseMs: Long? = null,
    val httpCode: Int? = null,
    val error: String? = null,
)

interface Checker {
    suspend fun check(monitor: MonitorEntity): CheckOutcome
}

/** Parses "200-299, 301,401" style expectations. */
class StatusCodeSpec private constructor(private val ranges: List<IntRange>) {

    fun matches(code: Int): Boolean = ranges.any { code in it }

    companion object {
        const val DEFAULT = "200-399"

        fun parse(text: String): StatusCodeSpec? {
            val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val ranges = parts.map { part ->
                val bounds = part.split('-').map { it.trim() }
                when (bounds.size) {
                    1 -> {
                        val code = bounds[0].toIntOrNull() ?: return null
                        if (code !in 100..599) return null
                        code..code
                    }
                    2 -> {
                        val from = bounds[0].toIntOrNull() ?: return null
                        val to = bounds[1].toIntOrNull() ?: return null
                        if (from !in 100..599 || to !in 100..599 || from > to) return null
                        from..to
                    }
                    else -> return null
                }
            }
            return StatusCodeSpec(ranges)
        }

        fun isValid(text: String): Boolean = parse(text) != null
    }
}

object Targets {
    /** Adds https:// when the user typed a bare host, and returns null when the URL cannot be parsed. */
    fun httpUrl(target: String): HttpUrl? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()
    }

    /** Extracts a bare host from "host", "host:port", "tcp://host:port/path" or a URL. */
    fun host(target: String): String {
        var value = target.trim()
        val scheme = value.indexOf("://")
        if (scheme >= 0) value = value.substring(scheme + 3)
        value = value.substringBefore('/').substringBefore('?')
        if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']')
        // Leave raw IPv6 literals alone; otherwise drop a trailing :port.
        if (value.count { it == ':' } == 1) value = value.substringBefore(':')
        return value
    }

    /** Port embedded in the target ("host:8080"), if any. */
    fun embeddedPort(target: String): Int? {
        var value = target.trim()
        val scheme = value.indexOf("://")
        if (scheme >= 0) value = value.substring(scheme + 3)
        value = value.substringBefore('/')
        if (value.startsWith("[")) return value.substringAfter("]:", "").toIntOrNull()
        return if (value.count { it == ':' } == 1) value.substringAfter(':').toIntOrNull() else null
    }
}

object FailureReasons {
    fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "DNS lookup failed"
        is SSLHandshakeException -> "SSL handshake failed" + detail(error)
        is SSLPeerUnverifiedException -> "SSL certificate does not match host"
        is SSLException -> "SSL error" + detail(error)
        is SocketTimeoutException -> "Connection timeout"
        is InterruptedIOException -> "Connection timeout"
        is ConnectException -> "Connection refused"
        is NoRouteToHostException -> "No route to host"
        is IOException -> "Connection error" + detail(error)
        else -> "Check failed" + detail(error)
    }

    private fun detail(error: Throwable): String {
        val message = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return if (message.isEmpty()) "" else ": " + message.take(120)
    }
}

/**
 * Performs a real GET request and judges the response against the monitor's expected status codes.
 * A response that arrives but carries an unexpected code (for example 503) counts as DOWN.
 */
class HttpChecker(private val baseClient: OkHttpClient) : Checker {

    override suspend fun check(monitor: MonitorEntity): CheckOutcome = withContext(Dispatchers.IO) {
        val url = Targets.httpUrl(monitor.target)
            ?: return@withContext CheckOutcome(up = false, error = "Invalid URL")
        val spec = StatusCodeSpec.parse(monitor.expectedCodes) ?: StatusCodeSpec.parse(StatusCodeSpec.DEFAULT)!!
        val timeout = monitor.timeoutSeconds.coerceIn(1, 120).toLong()
        val client = baseClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .callTimeout(timeout, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build()
        val started = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val code = response.code
                if (spec.matches(code)) {
                    CheckOutcome(up = true, responseMs = elapsed, httpCode = code)
                } else {
                    CheckOutcome(
                        up = false,
                        responseMs = elapsed,
                        httpCode = code,
                        error = "HTTP $code (expected ${monitor.expectedCodes})",
                    )
                }
            }
        } catch (e: Exception) {
            CheckOutcome(up = false, error = FailureReasons.describe(e))
        }
    }

    companion object {
        const val USER_AGENT = "Pingvanta/1.0 (Android uptime monitor)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** Opens a TCP connection to host:port. UP means the three-way handshake completed. */
class TcpChecker : Checker {
    override suspend fun check(monitor: MonitorEntity): CheckOutcome = withContext(Dispatchers.IO) {
        val host = Targets.host(monitor.target)
        if (host.isEmpty() || monitor.port !in 1..65535) {
            return@withContext CheckOutcome(up = false, error = "Invalid host or port")
        }
        val timeoutMs = monitor.timeoutSeconds.coerceIn(1, 120) * 1000
        val started = System.nanoTime()
        try {
            val address = InetSocketAddress(InetAddress.getByName(host), monitor.port)
            Socket().use { socket -> socket.connect(address, timeoutMs) }
            CheckOutcome(up = true, responseMs = (System.nanoTime() - started) / 1_000_000)
        } catch (e: Exception) {
            CheckOutcome(up = false, error = FailureReasons.describe(e))
        }
    }
}

/**
 * ICMP echo. Android apps cannot open raw sockets, so this runs the system ping binary, which is
 * permitted to send ICMP on most devices. If the binary is missing or blocked, it falls back to
 * InetAddress.isReachable, which may be less accurate. Networks that drop ICMP will report DOWN.
 */
class PingChecker : Checker {
    override suspend fun check(monitor: MonitorEntity): CheckOutcome = withContext(Dispatchers.IO) {
        val host = Targets.host(monitor.target)
        if (host.isEmpty() || host.startsWith("-")) {
            return@withContext CheckOutcome(up = false, error = "Invalid host")
        }
        val timeout = monitor.timeoutSeconds.coerceIn(1, 60)
        try {
            runSystemPing(host, timeout)
        } catch (e: UnknownHostException) {
            CheckOutcome(up = false, error = "DNS lookup failed")
        } catch (e: Exception) {
            fallback(host, timeout)
        }
    }

    private fun runSystemPing(host: String, timeoutSeconds: Int): CheckOutcome {
        val started = System.nanoTime()
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeoutSeconds.toString(), host)
            .redirectErrorStream(true)
            .start()
        try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor((timeoutSeconds + 5).toLong(), TimeUnit.SECONDS)
            if (!finished) return CheckOutcome(up = false, error = "Ping timeout")
            val exit = process.exitValue()
            if (exit == 0) {
                val parsed = TIME_REGEX.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
                val ms = parsed?.toLong() ?: ((System.nanoTime() - started) / 1_000_000)
                return CheckOutcome(up = true, responseMs = ms)
            }
            val lower = output.lowercase()
            return when {
                "unknown host" in lower || "bad address" in lower ->
                    CheckOutcome(up = false, error = "DNS lookup failed")
                "permitted" in lower || "permission" in lower ->
                    throw IOException("ping not permitted")
                else -> CheckOutcome(up = false, error = "No ICMP reply (host down or ICMP blocked)")
            }
        } finally {
            process.destroy()
        }
    }

    private fun fallback(host: String, timeoutSeconds: Int): CheckOutcome {
        val started = System.nanoTime()
        return try {
            val reachable = InetAddress.getByName(host).isReachable(timeoutSeconds * 1000)
            if (reachable) {
                CheckOutcome(up = true, responseMs = (System.nanoTime() - started) / 1_000_000)
            } else {
                CheckOutcome(up = false, error = "No ICMP reply (host down or ICMP blocked)")
            }
        } catch (e: Exception) {
            CheckOutcome(up = false, error = FailureReasons.describe(e))
        }
    }

    private companion object {
        val TIME_REGEX = Regex("time[=<]([0-9.]+) ?ms")
    }
}

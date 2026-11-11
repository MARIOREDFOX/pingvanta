package app.pingvanta

import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorType
import app.pingvanta.monitor.HttpChecker
import app.pingvanta.monitor.TcpChecker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpCheckerTest {
    private lateinit var server: MockWebServer
    private val checker = HttpChecker(HttpChecker.defaultClient())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun monitor(codes: String = "200-399", timeout: Int = 5) = MonitorEntity(
        name = "test",
        target = server.url("/health").toString(),
        expectedCodes = codes,
        timeoutSeconds = timeout,
    )

    @Test
    fun `200 is up and records response time`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val outcome = checker.check(monitor())
        assertTrue(outcome.up)
        assertEquals(200, outcome.httpCode)
        assertNotNull(outcome.responseMs)
        assertEquals(HttpChecker.USER_AGENT, server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `a server error response is down even though the server answered`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = checker.check(monitor())
        assertFalse(outcome.up)
        assertEquals(503, outcome.httpCode)
        assertTrue(outcome.error!!.contains("503"))
    }

    @Test
    fun `404 is down by default but up when explicitly expected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(checker.check(monitor()).up)
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(checker.check(monitor(codes = "404")).up)
    }

    @Test
    fun `no response within the timeout is reported as a timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val outcome = checker.check(monitor(timeout = 1))
        assertFalse(outcome.up)
        assertEquals("Connection timeout", outcome.error)
    }

    @Test
    fun `closed port is a connection failure`() = runBlocking {
        val target = server.url("/").toString()
        server.shutdown()
        val outcome = checker.check(MonitorEntity(name = "gone", target = target, timeoutSeconds = 2))
        assertFalse(outcome.up)
        assertNotNull(outcome.error)
    }

    @Test
    fun `unresolvable host is a DNS failure, not up`() = runBlocking {
        val outcome = checker.check(
            MonitorEntity(name = "dns", target = "http://pingvanta-does-not-exist.invalid", timeoutSeconds = 5),
        )
        assertFalse(outcome.up)
        assertEquals("DNS lookup failed", outcome.error)
    }

    @Test
    fun `invalid url is rejected without a request`() = runBlocking {
        val outcome = checker.check(MonitorEntity(name = "bad", target = "ht!tp://???"))
        assertFalse(outcome.up)
        assertEquals("Invalid URL", outcome.error)
    }
}

class TcpCheckerTest {
    @Test
    fun `open port is up and closed port is down`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val checker = TcpChecker()
        val open = MonitorEntity(
            name = "tcp",
            target = server.hostName,
            type = MonitorType.TCP,
            port = server.port,
            timeoutSeconds = 2,
        )
        assertTrue(checker.check(open).up)
        server.shutdown()
        assertFalse(checker.check(open).up)
    }
}

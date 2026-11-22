package com.sterbenden.clockedin.mcp

import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sterbenden.clockedin.core.Actor
import com.sterbenden.clockedin.service.ClockService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * End-to-end: starts the IDE's real MCP server with this plugin loaded and talks to it
 * over HTTP like an agent would.
 */
class ClockToolsMcpTest : BasePlatformTestCase() {
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private lateinit var url: String
    private var sessionId: String? = null
    private var nextId = 1

    override fun setUp() {
        super.setUp()
        val clock = ClockService.getInstance()
        clock.clockOut(Actor.ME)
        clock.sessions().forEach { clock.delete(it.id) }
        val server = service<McpServerService>()
        // Since 2026.3 the server must not be started from the UI thread.
        if (!server.isRunning) {
            val started = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread { server.start() }
            PlatformTestUtil.waitWithEventsDispatching("MCP server did not start", { started.isDone }, 30)
            started.get()
        }
        PlatformTestUtil.waitWithEventsDispatching("MCP server did not start", { server.isRunning && server.port > 0 }, 30)
        url = "http://127.0.0.1:${server.port}/stream"
        initialize()
    }

    override fun tearDown() {
        try {
            val clock = ClockService.getInstance()
            clock.clockOut(Actor.ME)
            clock.sessions().forEach { clock.delete(it.id) }
        } finally {
            super.tearDown()
        }
    }

    fun `test tools are listed with descriptions`() {
        val tools = rpc("tools/list", buildJsonObject {})["tools"]!!.jsonArray.map { it.jsonObject }
        val ours = tools.filter { it["name"]!!.jsonPrimitive.content.startsWith("clock_") }
            .associateBy { it["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("clock_in", "clock_pause", "clock_out", "clock_status", "clock_report"), ours.keys)
        assertTrue(ours["clock_in"]!!["description"]!!.jsonPrimitive.content.contains("clocked-in skill"))
        val reportParams = ours["clock_report"]!!["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys
        // The IDE's MCP server adds projectPath to every tool.
        assertEquals(setOf("from", "to", "groupBy", "projectPath"), reportParams)
    }

    fun `test agent clocks in and out through MCP`() {
        val clockIn = call("clock_in")
        assertEquals("started", clockIn["outcome"]!!.jsonPrimitive.content)
        assertTrue(clockIn["status"]!!.jsonObject["running"]!!.jsonPrimitive.boolean)

        assertEquals("already_running", call("clock_in")["outcome"]!!.jsonPrimitive.content)

        val running = ClockService.getInstance().status().running!!
        assertEquals(Actor.AGENT, running.startedBy)
        assertEquals("clocked-in-test", running.startedByAgent)

        val status = call("clock_status")
        assertEquals("agent", status["startedBy"]!!.jsonPrimitive.content)

        assertEquals("stopped", call("clock_out")["outcome"]!!.jsonPrimitive.content)
        assertEquals("not_running", call("clock_out")["outcome"]!!.jsonPrimitive.content)
        assertNull(ClockService.getInstance().status().running)
    }

    fun `test agent pauses at the end of a request and resumes on the next`() {
        assertEquals("started", call("clock_in")["outcome"]!!.jsonPrimitive.content)
        val paused = call("clock_pause")
        assertEquals("paused", paused["outcome"]!!.jsonPrimitive.content)
        assertEquals("paused", paused["status"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("already_paused", call("clock_pause")["outcome"]!!.jsonPrimitive.content)
        assertEquals("resumed", call("clock_in")["outcome"]!!.jsonPrimitive.content)
        assertEquals("running", call("clock_status")["state"]!!.jsonPrimitive.content)
    }

    fun `test agent leaves my break alone`() {
        ClockService.getInstance().clockIn(Actor.ME)
        assertEquals("left_running_started_by_me", call("clock_pause")["outcome"]!!.jsonPrimitive.content)
        ClockService.getInstance().pause(Actor.ME)
        assertEquals("on_break_by_me", call("clock_in")["outcome"]!!.jsonPrimitive.content)
        assertEquals("left_on_break_by_me", call("clock_out")["outcome"]!!.jsonPrimitive.content)
        assertEquals("paused", call("clock_status")["state"]!!.jsonPrimitive.content)
    }

    fun `test agent can't clock out a session I started`() {
        ClockService.getInstance().clockIn(Actor.ME)
        assertEquals("left_running_started_by_me", call("clock_out")["outcome"]!!.jsonPrimitive.content)
        assertNotNull(ClockService.getInstance().status().running)
    }

    fun `test report validates input`() {
        val report = call("clock_report", buildJsonObject { put("groupBy", "week") })
        assertEquals("week", report["groupBy"]!!.jsonPrimitive.content)

        val error = rpcRaw("tools/call", buildJsonObject {
            put("name", "clock_report")
            putJsonObject("arguments") {
                put("from", "yesterday")
                put("projectPath", project.basePath)
            }
        })
        val result = error["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result.toString().contains("Invalid 'from' date"))
    }

    fun `test call without a resolvable project tells the agent what to pass`() {
        val response = rpcRaw("tools/call", buildJsonObject {
            put("name", "clock_status")
            putJsonObject("arguments") {}
        })
        val result = response["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result.toString().contains("projectPath"))
    }

    // --- minimal MCP client (streamable HTTP) ---

    private fun initialize() {
        rpc("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "clocked-in-test")
                put("version", "1")
            }
        })
        post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        })
    }

    private fun call(tool: String, arguments: JsonObject = buildJsonObject {}): JsonObject {
        // Like an agent whose working directory is not an open project: pass projectPath.
        val result = rpc("tools/call", buildJsonObject {
            put("name", tool)
            put("arguments", JsonObject(arguments + ("projectPath" to JsonPrimitive(project.basePath))))
        })
        assertFalse("$tool failed: $result", result["isError"]?.jsonPrimitive?.boolean ?: false)
        result["structuredContent"]?.let { return it.jsonObject }
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun rpc(method: String, params: JsonObject): JsonObject {
        val response = rpcRaw(method, params)
        response["error"]?.let { fail("$method failed: $it") }
        return response["result"]!!.jsonObject
    }

    private fun rpcRaw(method: String, params: JsonObject): JsonObject = post(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", nextId++)
        put("method", method)
        put("params", params)
    })!!.jsonObject

    private fun post(body: JsonObject): JsonElement? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        // Send off the UI thread and pump events, so tool calls that need the EDT can run.
        val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        PlatformTestUtil.waitWithEventsDispatching("No MCP response", { future.isDone }, 30)
        val response = future.get()
        assertTrue("HTTP ${response.statusCode()}: ${response.body()}", response.statusCode() in 200..299)
        response.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
        val text = response.body().trim()
        if (text.isEmpty() || text == "null") return null // notifications have no result
        val json = if (text.startsWith("{")) {
            text
        } else {
            text.lines().filter { it.startsWith("data:") }.joinToString("") { it.removePrefix("data:").trim() }
        }
        return try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw AssertionError("Unexpected MCP response (${response.headers().map()}): $text", e)
        }
    }
}

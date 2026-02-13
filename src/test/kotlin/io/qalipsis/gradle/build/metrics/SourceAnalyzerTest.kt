/*
 * QALIPSIS
 * Copyright (C) 2025 AERIS IT Solutions GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package io.qalipsis.gradle.build.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SourceAnalyzerTest {

    private val analyzer = SourceAnalyzer()

    @Test
    fun `should extract meters from explicit meterRegistry calls`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val meterPrefix = "kafka.produce"
                private var recordsCount: Counter? = null
                private var timeToResponse: Timer? = null

                fun start() {
                    meterRegistry?.apply {
                        recordsCount = counter(scenarioName, stepName, "${'$'}meterPrefix-records", tags)
                        timeToResponse = timer(scenarioName, stepName, "${'$'}{meterPrefix}-time-to-response", tags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.meters.size)
        val counter = result.meters.find { it.type == "counter" }!!
        assertEquals("kafka.produce-records", counter.name)
        assertTrue(counter.resolved)

        val timer = result.meters.find { it.type == "timer" }!!
        assertEquals("kafka.produce-time-to-response", timer.name)
        assertTrue(timer.resolved)
    }

    @Test
    fun `should extract meters with named parameters`(@TempDir tempDir: Path) {
        val source = """
            class Publisher {
                companion object {
                    private const val EVENTS_EXPORT_TIMER_NAME = "kafka.events.export"
                }

                fun start() {
                    meterRegistry.timer(
                        scenarioName = "",
                        stepName = "",
                        name = EVENTS_EXPORT_TIMER_NAME,
                        tags = mapOf("publisher" to "kafka")
                    )
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("Publisher.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(1, result.meters.size)
        assertEquals("kafka.events.export", result.meters[0].name)
        assertEquals("timer", result.meters[0].type)
        assertTrue(result.meters[0].resolved)
    }

    @Test
    fun `should extract meters with single argument`(@TempDir tempDir: Path) {
        val source = """
            class Publisher {
                companion object {
                    private const val EVENTS_EXPORT_TIMER_NAME = "kafka.events.export"
                }

                fun start() {
                    counter = meterRegistry.counter(EVENTS_EXPORT_TIMER_NAME)
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("Publisher.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(1, result.meters.size)
        assertEquals("kafka.events.export", result.meters[0].name)
        assertEquals("counter", result.meters[0].type)
    }

    @Test
    fun `should extract events with value`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val eventPrefix = "kafka.produce"

                fun execute() {
                    eventsLogger?.info("${'$'}{eventPrefix}.sent.records", records.count(), tags = context.toEventTags())
                    eventsLogger?.info("${'$'}{eventPrefix}.sent.bytes", metersForCall.bytesSent, tags = context.toEventTags())
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.events.size)
        val records = result.events.find { it.name.endsWith(".sent.records") }!!
        assertEquals("kafka.produce.sent.records", records.name)
        assertEquals("info", records.severity)
        assertEquals("Int", records.valueType)

        val bytes = result.events.find { it.name.endsWith(".sent.bytes") }!!
        assertEquals("Number", bytes.valueType)
    }

    @Test
    fun `should extract events without value`(@TempDir tempDir: Path) {
        val source = """
            class MyReader {
                private val eventPrefix = "mongodb.poll"

                fun poll() {
                    eventsLogger?.trace("${'$'}eventPrefix.polling", tags = context.toEventTags())
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyReader.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(1, result.events.size)
        assertEquals("mongodb.poll.polling", result.events[0].name)
        assertEquals("trace", result.events[0].severity)
        assertEquals("none", result.events[0].valueType)
    }

    @Test
    fun `should extract events with array value`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val eventPrefix = "netty.http"

                fun onFailure() {
                    eventsLogger?.warn(
                        "${'$'}{eventPrefix}.connection-failure",
                        arrayOf(timeToFailure, throwable),
                        tags = eventsTags
                    )
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(1, result.events.size)
        assertEquals("netty.http.connection-failure", result.events[0].name)
        assertEquals("warn", result.events[0].severity)
        assertTrue(result.events[0].valueType.startsWith("Array"))
    }

    @Test
    fun `should extract events from apply blocks`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val eventPrefix = "rabbitmq.consume"

                fun process() {
                    eventsLogger?.apply {
                        info("${'$'}{eventPrefix}.bytes", message.size, tags = eventTags)
                        info("${'$'}{eventPrefix}.records", 1, tags = eventTags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.events.size)
        assertTrue(result.events.all { it.severity == "info" })
        assertTrue(result.events.all { it.resolved })
    }

    @Test
    fun `should extract events with named parameters`(@TempDir tempDir: Path) {
        val source = """
            class MyReader {
                private val eventPrefix = "elasticsearch.poll"

                fun onError() {
                    eventsLogger?.apply {
                        error(
                            name = "${'$'}{eventPrefix}.failure.records",
                            value = e.message,
                            tags = eventTags!!
                        )
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyReader.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(1, result.events.size)
        assertEquals("elasticsearch.poll.failure.records", result.events[0].name)
        assertEquals("error", result.events[0].severity)
        assertEquals("String", result.events[0].valueType)
    }

    @Test
    fun `should warn about unresolved prefixes`(@TempDir tempDir: Path) {
        val source = """
            class MyStep(private val redisMethod: String) {
                private val meterPrefix = "redis-lettuce-poll-${'$'}redisMethod"

                fun start() {
                    meterRegistry?.apply {
                        counter(scenarioName, stepName, "${'$'}meterPrefix-records", tags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(1, result.meters.size)
        assertFalse(result.meters[0].resolved)
        assertTrue(result.meters[0].name.contains("\$redisMethod"))
    }

    @Test
    fun `should resolve prefixes from overrides`(@TempDir tempDir: Path) {
        val source = """
            class MyStep(private val redisMethod: String) {
                private val meterPrefix = "redis-lettuce-poll-${'$'}redisMethod"

                fun start() {
                    meterRegistry?.apply {
                        counter(scenarioName, stepName, "${'$'}meterPrefix-records", tags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val overrides = mapOf("MyStep.redisMethod" to "scan")
        val result = analyzer.analyzeFile(file, overrides)

        assertEquals(1, result.meters.size)
        assertEquals("redis-lettuce-poll-scan-records", result.meters[0].name)
        assertTrue(result.meters[0].resolved)
    }

    @Test
    fun `should skip files without meter or event usage`(@TempDir tempDir: Path) {
        val source = """
            class SimpleConfig {
                val name = "test"
                fun configure() { println("hello") }
            }
        """.trimIndent()
        val file = tempDir.resolve("SimpleConfig.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertTrue(result.meters.isEmpty())
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `should extract meters from run blocks`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val meterPrefix = "http-client"

                fun start() {
                    meterRegistry?.run {
                        counter(scenarioName, stepName, "${'$'}meterPrefix-sent-bytes", tags)
                        timer(scenarioName, stepName, "${'$'}{meterPrefix}-time-to-response", tags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.meters.size)
        val counter = result.meters.find { it.type == "counter" }!!
        assertEquals("http-client-sent-bytes", counter.name)
        assertTrue(counter.resolved)

        val timer = result.meters.find { it.type == "timer" }!!
        assertEquals("http-client-time-to-response", timer.name)
        assertTrue(timer.resolved)
    }

    @Test
    fun `should extract meters from let blocks with implicit it`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val meterPrefix = "cassandra-poll"

                fun start() {
                    meterRegistry?.let {
                        it.counter(scenarioName, stepName, "${'$'}meterPrefix-records", tags)
                        it.timer(scenarioName, stepName, "${'$'}{meterPrefix}-time-to-response", tags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.meters.size)
        val counter = result.meters.find { it.type == "counter" }!!
        assertEquals("cassandra-poll-records", counter.name)
        assertTrue(counter.resolved)

        val timer = result.meters.find { it.type == "timer" }!!
        assertEquals("cassandra-poll-time-to-response", timer.name)
        assertTrue(timer.resolved)
    }

    @Test
    fun `should extract meters from also blocks with named parameter`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val meterPrefix = "mongodb-save"

                fun start() {
                    meterRegistry?.also { registry ->
                        registry.counter(scenarioName, stepName, "${'$'}meterPrefix-records", tags)
                        registry.timer(scenarioName, stepName, "${'$'}{meterPrefix}-time-to-response", tags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.meters.size)
        val counter = result.meters.find { it.type == "counter" }!!
        assertEquals("mongodb-save-records", counter.name)
        assertTrue(counter.resolved)

        val timer = result.meters.find { it.type == "timer" }!!
        assertEquals("mongodb-save-time-to-response", timer.name)
        assertTrue(timer.resolved)
    }

    @Test
    fun `should extract events from let blocks with implicit it`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val eventPrefix = "graphite.export"

                fun process() {
                    eventsLogger?.let {
                        it.info("${'$'}{eventPrefix}.bytes", message.size, tags = eventTags)
                        it.info("${'$'}{eventPrefix}.records", 1, tags = eventTags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.events.size)
        assertTrue(result.events.all { it.severity == "info" })
        assertTrue(result.events.all { it.resolved })
    }

    @Test
    fun `should extract events from run blocks`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val eventPrefix = "jms.consume"

                fun process() {
                    eventsLogger?.run {
                        info("${'$'}{eventPrefix}.bytes", message.size, tags = eventTags)
                        error("${'$'}{eventPrefix}.failure", e, tags = eventTags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(2, result.events.size)
        val info = result.events.find { it.severity == "info" }!!
        assertEquals("jms.consume.bytes", info.name)
        val error = result.events.find { it.severity == "error" }!!
        assertEquals("jms.consume.failure", error.name)
        assertEquals("Throwable", error.valueType)
    }

    @Test
    fun `should extract events from also blocks with named parameter`(@TempDir tempDir: Path) {
        val source = """
            class MyStep {
                private val eventPrefix = "slack.send"

                fun process() {
                    eventsLogger?.also { logger ->
                        logger.warn("${'$'}{eventPrefix}.slow-response", timeToResponse, tags = eventTags)
                    }
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("MyStep.kt").toFile().apply { writeText(source) }

        val result = analyzer.analyzeFile(file, emptyMap())

        assertEquals(1, result.events.size)
        assertEquals("slack.send.slow-response", result.events[0].name)
        assertEquals("warn", result.events[0].severity)
        assertEquals("Duration", result.events[0].valueType)
        assertTrue(result.events[0].resolved)
    }
}

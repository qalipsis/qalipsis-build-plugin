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

import java.io.File

/**
 * A single meter declaration extracted from source code.
 *
 * @property name      The fully-resolved meter name (e.g. `"kafka-produce-value-serialization-errors"`),
 *                     or the partially-resolved form when some variables could not be substituted.
 * @property type      The meter type — one of `counter`, `timer`, `gauge`, `summary`, `rate`, `throughput`.
 * @property sourceFile The name of the Kotlin source file where this meter was found.
 * @property resolved  `true` when every `$variable` reference in the name was successfully resolved
 *                     to a literal string; `false` if one or more variables remain unresolved.
 *                     Unresolved entries can be fixed via [MetricsReportExtension.prefixOverrides].
 */
data class MeterEntry(
    val name: String,
    val type: String,
    val sourceFile: String,
    val resolved: Boolean
)

/**
 * A single event declaration extracted from source code.
 *
 * @property name       The fully-resolved event name (e.g. `"kafka-produce.producing-value"`).
 * @property severity   The log level — one of `trace`, `debug`, `info`, `warn`, `error`.
 * @property valueType  The inferred type of the value parameter (e.g. `"Int"`, `"Duration"`, `"none"` when absent).
 * @property sourceFile The name of the Kotlin source file where this event was found.
 * @property resolved   `true` when every `$variable` reference in the name was successfully resolved.
 */
data class EventEntry(
    val name: String,
    val severity: String,
    val valueType: String,
    val sourceFile: String,
    val resolved: Boolean
)

/**
 * The aggregated result of analyzing one source file, containing all meters and events found within it.
 */
data class AnalysisResult(
    val meters: List<MeterEntry>,
    val events: List<EventEntry>
)

/**
 * Regex-based static analyzer for Kotlin source files that extracts meter and event declarations
 * from QALIPSIS plugin code.
 *
 * ## Overview
 *
 * QALIPSIS plugins instrument their steps using two APIs:
 * - **[CampaignMeterRegistry]** for numeric meters (counters, timers, etc.)
 * - **[EventsLogger]** for structured events at various severity levels
 *
 * This analyzer scans `.kt` source files and produces [MeterEntry] and [EventEntry] records
 * that are later written into a CSV report and packaged into the plugin JAR.
 *
 * ## Source code patterns recognized
 *
 * ### Meter calls
 *
 * **Explicit receiver calls** — the most common pattern:
 * ```kotlin
 * meterRegistry?.counter(scenarioName, stepName, "$meterPrefix.records", tags)
 * meterRegistry.timer(scenarioName, stepName, name = "$meterPrefix.time-to-response", tags = tags)
 * ```
 *
 * **Bare calls inside `apply`/`run` blocks** — used when multiple meters are registered together.
 * In these scope functions the receiver becomes `this`, so method calls appear without a prefix:
 * ```kotlin
 * meterRegistry?.apply {
 *     counter(scenarioName, stepName, "$meterPrefix.records", tags)
 *     timer(scenarioName, stepName, "$meterPrefix.time-to-response", tags)
 * }
 * ```
 *
 * **Parameter calls inside `let`/`also` blocks** — the receiver is passed as a lambda parameter
 * (`it` by default, or an explicit name). Calls use the parameter as the receiver:
 * ```kotlin
 * meterRegistry?.let {
 *     it.counter(scenarioName, stepName, "$meterPrefix.records", tags)
 * }
 * meterRegistry?.also { registry ->
 *     registry.timer(scenarioName, stepName, "$meterPrefix.time-to-response", tags)
 * }
 * ```
 *
 * Three argument forms are supported for meters:
 * 1. **4-arg positional**: `counter(scenarioName, stepName, "name", tags)` — the name is the 3rd argument.
 * 2. **Named parameter**: `counter(scenarioName = ..., stepName = ..., name = "name", tags = ...)` — matched by `name =`.
 * 3. **1-arg shorthand**: `counter(CONSTANT_NAME)` — a constant reference used as the sole argument.
 *
 * ### Event calls
 *
 * **Explicit receiver calls**:
 * ```kotlin
 * eventsLogger?.info("$eventPrefix.received-records", recordCount, tags = tags)
 * ```
 *
 * **Bare calls inside `apply`/`run` blocks**, or **parameter calls inside `let`/`also` blocks**:
 * ```kotlin
 * eventsLogger?.apply {
 *     info("$eventPrefix.success", receivedBytesCount, tags = tags)
 *     error("$eventPrefix.failure", e, tags = tags)
 * }
 * eventsLogger?.let {
 *     it.info("$eventPrefix.success", receivedBytesCount, tags = tags)
 * }
 * ```
 *
 * Events follow a `(name, value?, tags?)` positional form or named parameters (`name = ...`, `value = ...`).
 *
 * ## Prefix resolution
 *
 * Meter and event names typically use string templates referencing a prefix variable declared in the class:
 * ```kotlin
 * private val meterPrefix = "kafka-produce"
 * private val eventPrefix = "kafka-produce"
 * ```
 *
 * The analyzer resolves `$meterPrefix` and `${eventPrefix}` by scanning for `val` declarations
 * in the same file. Resolution is recursive: if the prefix itself contains template variables,
 * they are resolved transitively.
 *
 * When a variable cannot be resolved (e.g. a constructor parameter or a dynamic expression),
 * the entry is marked with `resolved = false`. These cases can be fixed by configuring
 * [MetricsReportExtension.prefixOverrides] in the build script:
 * ```kotlin
 * qalipsisBuild {
 *     metricsReport {
 *         enabled.set(true)
 *         prefixOverrides.put("RedisLettucePollStepNewImpl.redisMethod", "scan")
 *     }
 * }
 * ```
 *
 * Override keys use the format `ClassName.variableName`, where `ClassName` is the Kotlin file name
 * (without `.kt`).
 *
 * ## Workflow
 *
 * The [MetricsReportTask] drives the overall process:
 * 1. Collects all `.kt` files from the configured source directories.
 * 2. Calls [analyzeFile] on each file.
 * 3. Deduplicates the results (meters by name+type, events by name+severity+valueType).
 * 4. Writes the combined CSV to `META-INF/services/qalipsis/metrics-and-events.csv`.
 * 5. Logs warnings for any unresolved entries.
 *
 * The task is wired to run before `processResources` so the CSV is included in the JAR.
 */
class SourceAnalyzer {

    companion object {
        private val METER_METHODS = setOf("counter", "timer", "gauge", "summary", "rate", "throughput")
        private val EVENT_SEVERITIES = setOf("trace", "debug", "info", "warn", "error")

        /**
         * Pattern for variable declarations like:
         * `val meterPrefix = "some-value"`
         * `private val eventPrefix: String = "some.value"`
         * `private const val EVENTS_EXPORT_TIMER_NAME = "some.name"`
         */
        private val VARIABLE_DECLARATION = Regex(
            """(?:private|protected|internal|public)?\s*(?:const\s+)?val\s+(\w+)(?:\s*:\s*\w+)?\s*=\s*"([^"]*?)""""
        )

        /**
         * Explicit meter call: `meterRegistry?.counter(` or `meterRegistry.counter(`
         */
        private val EXPLICIT_METER_CALL = Regex(
            """meterRegistry\??\.(\w+)\s*\("""
        )

        /**
         * Explicit event call: `eventsLogger?.info(` or `eventsLogger.info(`
         */
        private val EXPLICIT_EVENT_CALL = Regex(
            """eventsLogger\??\.(\w+)\s*\("""
        )

        /**
         * Bare function call (used inside `.apply`/`.run` blocks): `counter(` or `info(`
         */
        private fun bareCallRegex(methods: Set<String>): Regex {
            return Regex("""\b(${methods.joinToString("|")})\s*\(""")
        }

        /** Scope functions where `this` refers to the receiver — bare method calls work. */
        private val THIS_RECEIVER_SCOPES = setOf("apply", "run")

        /** Scope functions where the receiver is passed as a lambda parameter (`it` or named). */
        private val PARAM_RECEIVER_SCOPES = setOf("let", "also")

        /**
         * Pattern to detect any of the four standard Kotlin scope function blocks on a receiver.
         * Captures the scope function name (apply, run, let, also) in group 1.
         */
        private fun scopeBlockRegex(receiver: String): Regex {
            val allScopes = (THIS_RECEIVER_SCOPES + PARAM_RECEIVER_SCOPES).joinToString("|")
            return Regex("""$receiver\??\.\s*($allScopes)\s*\{""")
        }
    }

    /**
     * Analyzes a single Kotlin source file and extracts all meter and event declarations.
     *
     * The analysis proceeds in four steps:
     * 1. **Quick check** — if the file does not reference `meterRegistry` / `CampaignMeterRegistry`
     *    or `eventsLogger` / `EventsLogger`, it is skipped entirely for performance.
     * 2. **Variable extraction** — all `val varName = "literal"` declarations are collected into a
     *    lookup map used later for string template resolution.
     * 3. **Override injection** — entries from [prefixOverrides] whose key starts with the file's
     *    class name (e.g. `"MyStep.redisMethod"`) are merged into the variable map, overriding
     *    any source-level declaration.
     * 4. **Call detection** — explicit receiver calls and calls inside scope function blocks
     *    (`apply`, `run`, `let`, `also`) are scanned for meter and event registrations.
     *
     * @param file             The Kotlin source file to analyze.
     * @param prefixOverrides  A map of `ClassName.variableName` → `literalValue` entries that
     *                         supply values for variables the analyzer cannot resolve from source
     *                         (e.g. constructor parameters, dynamic expressions).
     * @return An [AnalysisResult] containing all meters and events found in the file.
     */
    fun analyzeFile(file: File, prefixOverrides: Map<String, String>): AnalysisResult {
        val content = file.readText()
        val fileName = file.name
        val className = file.nameWithoutExtension

        val hasMeterUsage = content.contains("meterRegistry") || content.contains("CampaignMeterRegistry")
        val hasEventUsage = content.contains("eventsLogger") || content.contains("EventsLogger")

        if (!hasMeterUsage && !hasEventUsage) {
            return AnalysisResult(emptyList(), emptyList())
        }

        // Extract variables from the file.
        val variables = extractVariables(content)

        // Apply manual overrides for this class.
        prefixOverrides.forEach { (key, value) ->
            if (key.startsWith("$className.")) {
                variables[key.substringAfter(".")] = value
            }
        }

        val meters = if (hasMeterUsage) findMeterCalls(content, fileName, variables) else emptyList()
        val events = if (hasEventUsage) findEventCalls(content, fileName, variables) else emptyList()

        return AnalysisResult(meters, events)
    }

    // ── Variable extraction ─────────────────────────────────────────────────

    /**
     * Scans the file content for `val` declarations whose right-hand side is a string literal
     * and returns them as a mutable map of `variableName → stringValue`.
     *
     * Matches patterns like:
     * - `val meterPrefix = "kafka-produce"`
     * - `private val eventPrefix: String = "kafka-produce"`
     * - `private const val SOME_NAME = "my.metric.name"`
     *
     * Only simple string literals are captured — concatenations, function calls, and
     * multi-line strings are ignored. The map is mutable so that [prefixOverrides] can
     * be injected afterward.
     */
    private fun extractVariables(content: String): MutableMap<String, String> {
        val vars = mutableMapOf<String, String>()
        for (match in VARIABLE_DECLARATION.findAll(content)) {
            vars[match.groupValues[1]] = match.groupValues[2]
        }
        return vars
    }

    // ── Meter analysis ──────────────────────────────────────────────────────

    /**
     * Finds all meter registration calls in the file content and returns them as [MeterEntry] records.
     *
     * Detection happens in two passes to avoid double-counting:
     * 1. **Explicit calls** — matches `meterRegistry?.counter(...)` or `meterRegistry.timer(...)`.
     *    The position of each opening parenthesis is recorded in a set of processed positions.
     * 2. **Scope-block calls** — finds `meterRegistry?.apply/run/let/also { ... }` regions, then:
     *    - For `apply`/`run` blocks: scans for bare `counter(...)`, `timer(...)`, etc.
     *    - For `let`/`also` blocks: scans for `it.counter(...)` or `paramName.counter(...)`.
     *    Positions already seen in pass 1 are skipped.
     *
     * For each call, the meter name argument is extracted via [extractMeterName], then resolved
     * through [resolveString] to replace any `$variable` references.
     */
    private fun findMeterCalls(
        content: String,
        sourceFile: String,
        variables: Map<String, String>
    ): List<MeterEntry> {
        val meters = mutableListOf<MeterEntry>()
        val processedPositions = mutableSetOf<Int>()

        // 1. Explicit calls: meterRegistry?.counter(...)
        for (match in EXPLICIT_METER_CALL.findAll(content)) {
            val method = match.groupValues[1]
            if (method !in METER_METHODS) continue

            val openParen = content.indexOf('(', match.range.last - 1)
            if (openParen < 0) continue
            processedPositions.add(openParen)

            val args = extractArguments(content, openParen)
            val nameArg = extractMeterName(args) ?: continue
            val (resolvedName, resolved) = resolveString(nameArg, variables)

            meters.add(MeterEntry(resolvedName, method, sourceFile, resolved))
        }

        // 2. Calls inside meterRegistry?.apply/run/let/also { ... } blocks.
        val scopeBlocks = findScopeBlockRegions(content, "meterRegistry")

        for (block in scopeBlocks) {
            val regionContent = content.substring(block.region.first, block.region.last + 1)

            val callMatches: Sequence<MatchResult>
            val methodGroupIndex: Int

            if (block.thisReceiver) {
                // apply/run: scan for bare calls like counter(...)
                callMatches = bareCallRegex(METER_METHODS).findAll(regionContent)
                methodGroupIndex = 1
            } else {
                // let/also: scan for it.counter(...) or paramName.counter(...)
                val paramCallRegex = Regex("""${Regex.escape(block.parameterName)}\??\.(\w+)\s*\(""")
                callMatches = paramCallRegex.findAll(regionContent)
                methodGroupIndex = 1
            }

            for (match in callMatches) {
                val method = match.groupValues[methodGroupIndex]
                if (method !in METER_METHODS) continue

                val openParen = content.indexOf('(', block.region.first + match.range.last - 1)
                if (openParen < 0 || openParen in processedPositions) continue
                processedPositions.add(openParen)

                val args = extractArguments(content, openParen)
                val nameArg = extractMeterName(args) ?: continue
                val (resolvedName, resolved) = resolveString(nameArg, variables)

                meters.add(MeterEntry(resolvedName, method, sourceFile, resolved))
            }
        }

        return meters
    }

    /**
     * Extracts the meter name from the argument list.
     *
     * Supports three calling conventions:
     * - 4 args positional: `counter(scenarioName, stepName, "name", tags)`
     * - Named `name =`: `timer(scenarioName = "", stepName = "", name = ..., tags = ...)`
     * - 1 arg: `counter(CONSTANT_NAME)`
     */
    private fun extractMeterName(args: List<String>): String? {
        // Check for named `name =` parameter first.
        for (arg in args) {
            val stripped = arg.trimStart()
            if (stripped.startsWith("name") && stripped.removePrefix("name").trimStart().startsWith("=")) {
                return stripped.substringAfter("=").trim()
            }
        }
        return when {
            args.size >= 4 -> args[2]
            args.size == 1 -> args[0]
            else -> null
        }
    }

    // ── Event analysis ──────────────────────────────────────────────────────

    /**
     * Finds all event logging calls in the file content and returns them as [EventEntry] records.
     *
     * Works identically to [findMeterCalls] with two passes (explicit + scope-block),
     * but additionally infers the type of the `value` parameter via [inferValueType].
     * When no value is passed, the value type is reported as `"none"`.
     */
    private fun findEventCalls(
        content: String,
        sourceFile: String,
        variables: Map<String, String>
    ): List<EventEntry> {
        val events = mutableListOf<EventEntry>()
        val processedPositions = mutableSetOf<Int>()

        // 1. Explicit calls: eventsLogger?.info(...)
        for (match in EXPLICIT_EVENT_CALL.findAll(content)) {
            val severity = match.groupValues[1]
            if (severity !in EVENT_SEVERITIES) continue

            val openParen = content.indexOf('(', match.range.last - 1)
            if (openParen < 0) continue
            processedPositions.add(openParen)

            val args = extractArguments(content, openParen)
            val parsed = parseEventArgs(args) ?: continue
            val (resolvedName, resolved) = resolveString(parsed.first, variables)
            val valueType = if (parsed.second != null) inferValueType(parsed.second!!, variables) else "none"

            events.add(EventEntry(resolvedName, severity, valueType, sourceFile, resolved))
        }

        // 2. Calls inside eventsLogger?.apply/run/let/also { ... } blocks.
        val scopeBlocks = findScopeBlockRegions(content, "eventsLogger")

        for (block in scopeBlocks) {
            val regionContent = content.substring(block.region.first, block.region.last + 1)

            val callMatches: Sequence<MatchResult>

            if (block.thisReceiver) {
                // apply/run: scan for bare calls like info(...)
                callMatches = bareCallRegex(EVENT_SEVERITIES).findAll(regionContent)
            } else {
                // let/also: scan for it.info(...) or paramName.info(...)
                val paramCallRegex = Regex("""${Regex.escape(block.parameterName)}\??\.(\w+)\s*\(""")
                callMatches = paramCallRegex.findAll(regionContent)
            }

            for (match in callMatches) {
                val severity = match.groupValues[1]
                if (severity !in EVENT_SEVERITIES) continue

                val openParen = content.indexOf('(', block.region.first + match.range.last - 1)
                if (openParen < 0 || openParen in processedPositions) continue
                processedPositions.add(openParen)

                val args = extractArguments(content, openParen)
                val parsed = parseEventArgs(args) ?: continue
                val (resolvedName, resolved) = resolveString(parsed.first, variables)
                val valueType = if (parsed.second != null) inferValueType(parsed.second!!, variables) else "none"

                events.add(EventEntry(resolvedName, severity, valueType, sourceFile, resolved))
            }
        }

        return events
    }

    /**
     * Parses event arguments and returns (name, valueExpression?).
     *
     * Supports both positional and named-parameter syntax:
     * - `info("name", value, tags = ...)` → ("name", "value")
     * - `info("name", tags = ...)` → ("name", null)
     * - `error(name = "...", value = ..., tags = ...)` → ("...", "...")
     */
    private fun parseEventArgs(args: List<String>): Pair<String, String?>? {
        if (args.isEmpty()) return null

        // Named-parameter form.
        val namedName = args.firstOrNull { it.trimStart().let { s -> s.startsWith("name") && s.removePrefix("name").trimStart().startsWith("=") } }
        if (namedName != null) {
            val name = namedName.substringAfter("=").trim()
            val namedValue = args.firstOrNull { it.trimStart().let { s -> s.startsWith("value") && s.removePrefix("value").trimStart().startsWith("=") } }
            val value = namedValue?.substringAfter("=")?.trim()
            return name to value
        }

        // Positional form.
        val name = args[0]
        val value = if (args.size >= 2) {
            val second = args[1].trimStart()
            // Skip if the second arg is `tags = ...`.
            if (second.startsWith("tags") && second.removePrefix("tags").trimStart().startsWith("=")) null
            else args[1]
        } else {
            null
        }
        return name to value
    }

    // ── Value type inference ────────────────────────────────────────────────

    /**
     * Infers the type of an event value expression using heuristics on the source text.
     *
     * This is a best-effort classification — the analyzer does not perform full type resolution.
     * Rules are evaluated in order; the first match wins:
     *
     * | Pattern                                       | Inferred type      |
     * |-----------------------------------------------|--------------------|
     * | `arrayOf(...)`                                 | `Array<...>`       |
     * | Integer literal with `L` suffix                | `Long`             |
     * | Decimal literal                                | `Double`           |
     * | Integer literal                                | `Int`              |
     * | Quoted string                                  | `String`           |
     * | `true` / `false`                               | `Boolean`          |
     * | Contains `Duration` or `durationSinceNanos`    | `Duration`         |
     * | Ends with `.size`, `.count()`                  | `Int`              |
     * | Ends with `.toDouble()`, `.toInt()`, `.toLong()` | Matching type    |
     * | Ends with `.message`, `.asText()`              | `String`           |
     * | Contains time-related keywords                 | `Duration`         |
     * | Looks like a numeric quantity (bytes, count…)  | `Number`           |
     * | Looks like a throwable (e, error, exception…)  | `Throwable`        |
     * | Everything else                                | `Object`           |
     */
    private fun inferValueType(expression: String, variables: Map<String, String>): String {
        val trimmed = expression.trim()
        return when {
            trimmed.startsWith("arrayOf(") -> inferArrayType(trimmed)
            trimmed.matches(Regex("""-?\d+L""")) -> "Long"
            trimmed.matches(Regex("""-?\d+\.\d+f?""")) -> "Double"
            trimmed.matches(Regex("""-?\d+""")) -> "Int"
            trimmed.startsWith("\"") -> "String"
            trimmed == "true" || trimmed == "false" -> "Boolean"
            trimmed.contains("Duration") -> "Duration"
            trimmed.contains("durationSinceNanos") -> "Duration"
            trimmed.endsWith(".size") -> "Int"
            trimmed.endsWith(".count()") -> "Int"
            trimmed.endsWith(".toDouble()") -> "Double"
            trimmed.endsWith(".toInt()") -> "Int"
            trimmed.endsWith(".toLong()") -> "Long"
            trimmed.endsWith(".message") -> "String"
            trimmed.endsWith(".asText()") -> "String"
            trimmed.contains("timeTo") || trimmed.contains("TimeTo") ||
                trimmed.contains("timeToResponse") || trimmed.contains("timeToConnect") -> "Duration"
            looksLikeNumber(trimmed, variables) -> "Number"
            looksLikeThrowable(trimmed) -> "Throwable"
            else -> "Object"
        }
    }

    /**
     * Infers a parameterized `Array<T>` type by recursively inferring the type of each element.
     */
    private fun inferArrayType(expression: String): String {
        val inner = expression.removePrefix("arrayOf(").removeSuffix(")")
        val elements = splitTopLevel(inner)
        if (elements.isEmpty()) return "Array"
        val types = elements.map { inferValueType(it, emptyMap()) }.distinct()
        return "Array<${types.joinToString(", ")}>"
    }

    /**
     * Heuristic check: returns `true` if the expression likely represents a numeric quantity
     * based on common naming patterns (bytes, count, records, size, total, etc.).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun looksLikeNumber(expr: String, variables: Map<String, String>): Boolean {
        val lower = expr.lowercase()
        return lower.contains("bytes") || lower.contains("count") || lower.contains("records") ||
            lower.contains("items") || lower.contains("size") || lower.contains("length") ||
            lower.contains("total") || lower.contains("number") ||
            expr.endsWith(".contentLength") || expr.endsWith(".contentLength.toDouble()")
    }

    /**
     * Heuristic check: returns `true` if the expression likely represents a throwable
     * (common variable names: `e`, `error`, `exception`, `cause`).
     */
    private fun looksLikeThrowable(expr: String): Boolean {
        val lower = expr.lowercase()
        return lower == "e" || lower == "error" || lower.contains("throwable") ||
            lower.contains("exception") || lower.endsWith("cause")
    }

    // ── String resolution ───────────────────────────────────────────────────

    /**
     * Resolves Kotlin string template variables (`$var` and `${var}`) using the variable map
     * built from source-level declarations and [prefixOverrides].
     *
     * Resolution is **recursive**: if a variable's value itself contains `$other`, that
     * reference is resolved in turn. This handles chains like:
     * ```kotlin
     * val basePrefix = "redis-lettuce"
     * val meterPrefix = "$basePrefix-poll"
     * // → resolves to "redis-lettuce-poll"
     * ```
     *
     * When the input is not a quoted string (i.e. a bare variable or constant reference like
     * `SOME_CONST`), the analyzer first looks it up in the variable map and then resolves
     * the resulting value.
     *
     * @return A pair of (resolvedString, fullyResolved). `fullyResolved` is `false` if any
     *         `$variable` could not be substituted — the unresolved `$variable` token is left
     *         in place so it appears in the CSV as a hint to the developer.
     */
    private fun resolveString(raw: String, variables: Map<String, String>): Pair<String, Boolean> {
        // If the raw value is a constant reference (no quotes), resolve it.
        val unquoted = unquote(raw)
        if (!raw.trimStart().startsWith("\"")) {
            // It's a variable/constant reference.
            val resolved = variables[raw.trim()]
            return if (resolved != null) {
                resolveString("\"$resolved\"", variables)
            } else {
                raw.trim() to false
            }
        }

        var result = unquoted
        var fullyResolved = true

        // Resolve ${variable} patterns.
        result = Regex("""\$\{(\w+)}""").replace(result) { match ->
            val varName = match.groupValues[1]
            variables[varName]?.let {
                val (resolved, isResolved) = resolveString("\"$it\"", variables)
                if (!isResolved) fullyResolved = false
                resolved
            } ?: run {
                fullyResolved = false
                match.value
            }
        }

        // Resolve $variable patterns.
        result = Regex("""\$(\w+)""").replace(result) { match ->
            val varName = match.groupValues[1]
            variables[varName]?.let {
                val (resolved, isResolved) = resolveString("\"$it\"", variables)
                if (!isResolved) fullyResolved = false
                resolved
            } ?: run {
                fullyResolved = false
                match.value
            }
        }

        return result to fullyResolved
    }

    /** Strips surrounding double quotes from a string if present. */
    private fun unquote(s: String): String {
        val trimmed = s.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    // ── Scope-block region detection ────────────────────────────────────────

    /**
     * Describes a detected Kotlin scope function block (`apply`, `run`, `let`, or `also`)
     * on a receiver variable.
     *
     * @property region        Character range in the source from the opening `{` to the matching `}`.
     * @property thisReceiver  `true` for `apply`/`run` (receiver is `this`, bare calls work);
     *                         `false` for `let`/`also` (receiver is a lambda parameter).
     * @property parameterName The lambda parameter name for `let`/`also` blocks — either an
     *                         explicit name (e.g. `registry` in `{ registry -> ... }`) or `"it"`
     *                         when the default implicit parameter is used. Not meaningful for
     *                         `apply`/`run` blocks.
     */
    private data class ScopeBlock(
        val region: IntRange,
        val thisReceiver: Boolean,
        val parameterName: String
    )

    /**
     * Finds all scope function blocks (`apply`, `run`, `let`, `also`) on the given [receiver]
     * in the source content.
     *
     * This is needed because inside scope blocks, API calls may appear without the original
     * receiver prefix:
     * - In `apply`/`run`, `this` is the receiver → bare calls like `counter(...)`.
     * - In `let`/`also`, the receiver is a parameter → calls like `it.counter(...)`.
     *
     * Brace matching is delegated to [findMatchingBrace], which correctly handles
     * nested braces, string literals, and comments.
     *
     * @param content  The full source file text.
     * @param receiver The receiver variable name — typically `"meterRegistry"` or `"eventsLogger"`.
     * @return A list of [ScopeBlock] entries describing each detected block.
     */
    private fun findScopeBlockRegions(content: String, receiver: String): List<ScopeBlock> {
        val blocks = mutableListOf<ScopeBlock>()
        val regex = scopeBlockRegex(receiver)

        for (match in regex.findAll(content)) {
            val scopeFunction = match.groupValues[1]
            val openBrace = content.indexOf('{', match.range.last - 1)
            if (openBrace < 0) continue
            val closeBrace = findMatchingBrace(content, openBrace)
            if (closeBrace <= openBrace) continue

            val region = openBrace..closeBrace
            val thisReceiver = scopeFunction in THIS_RECEIVER_SCOPES
            val parameterName = if (!thisReceiver) {
                extractLambdaParameter(content, openBrace) ?: "it"
            } else {
                "this"
            }

            blocks.add(ScopeBlock(region, thisReceiver, parameterName))
        }
        return blocks
    }

    /**
     * Extracts the explicit lambda parameter name from a `{ paramName -> ... }` block.
     *
     * Looks for a simple identifier followed by `->` immediately after the opening brace.
     * Returns `null` if no explicit parameter is found (meaning the implicit `it` is used).
     */
    private fun extractLambdaParameter(content: String, openBrace: Int): String? {
        // Search in a reasonable window after the opening brace.
        val searchEnd = minOf(openBrace + 100, content.length)
        val arrowIndex = content.indexOf("->", openBrace + 1)
        if (arrowIndex < 0 || arrowIndex > searchEnd) return null
        val between = content.substring(openBrace + 1, arrowIndex).trim()
        return if (between.matches(Regex("""\w+"""))) between else null
    }

    // ── Parsing utilities ───────────────────────────────────────────────────

    /**
     * Extracts the comma-separated arguments from a function call.
     * [openParen] must point to the opening `(`.
     */
    private fun extractArguments(content: String, openParen: Int): List<String> {
        var parenDepth = 0
        var braceDepth = 0
        var inString = false
        var escaped = false
        val args = mutableListOf<String>()
        val current = StringBuilder()

        var i = openParen
        while (i < content.length) {
            val c = content[i]

            if (escaped) {
                current.append(c)
                escaped = false
                i++
                continue
            }

            if (inString) {
                current.append(c)
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                i++
                continue
            }

            when (c) {
                '"' -> {
                    inString = true
                    current.append(c)
                }
                '(' -> {
                    parenDepth++
                    if (parenDepth > 1) current.append(c)
                }
                ')' -> {
                    parenDepth--
                    if (parenDepth == 0) {
                        val arg = current.toString().trim()
                        if (arg.isNotEmpty()) args.add(arg)
                        return args
                    }
                    current.append(c)
                }
                '{' -> {
                    braceDepth++
                    current.append(c)
                }
                '}' -> {
                    braceDepth--
                    current.append(c)
                }
                ',' -> {
                    if (parenDepth == 1 && braceDepth == 0) {
                        args.add(current.toString().trim())
                        current.clear()
                    } else {
                        current.append(c)
                    }
                }
                else -> {
                    if (parenDepth >= 1) current.append(c)
                }
            }
            i++
        }
        return args
    }

    /**
     * Finds the index of the closing `}` that matches the opening `{` at [openBrace].
     * Correctly handles strings, comments, and nesting.
     */
    private fun findMatchingBrace(content: String, openBrace: Int): Int {
        var depth = 0
        var inString = false
        var inLineComment = false
        var inBlockComment = false
        var escaped = false

        var i = openBrace
        while (i < content.length) {
            val c = content[i]
            val next = if (i + 1 < content.length) content[i + 1] else null

            if (inLineComment) {
                if (c == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }

            if (escaped) {
                escaped = false
                i++
                continue
            }

            if (inString) {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                i++
                continue
            }

            when {
                c == '/' && next == '/' -> {
                    inLineComment = true
                    i += 2
                    continue
                }
                c == '/' && next == '*' -> {
                    inBlockComment = true
                    i += 2
                    continue
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * Splits a string on commas at the top level (not inside parentheses, brackets, or strings).
     */
    private fun splitTopLevel(input: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        val current = StringBuilder()

        for (c in input) {
            if (escaped) {
                current.append(c)
                escaped = false
                continue
            }
            if (inString) {
                current.append(c)
                if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> { inString = true; current.append(c) }
                '(', '[', '{' -> { depth++; current.append(c) }
                ')', ']', '}' -> { depth--; current.append(c) }
                ',' -> {
                    if (depth == 0) {
                        result.add(current.toString().trim())
                        current.clear()
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) result.add(last)
        return result
    }
}

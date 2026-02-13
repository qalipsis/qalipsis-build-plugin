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

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that scans Kotlin source files for meter and event declarations and generates
 * a CSV report at `META-INF/services/qalipsis/metrics-and-events.csv`.
 *
 * ## Inputs
 *
 * | Property          | Description                                                      |
 * |-------------------|------------------------------------------------------------------|
 * | [sourceDirs]      | Directories containing `.kt` files to scan (typically `src/main/kotlin`). |
 * | [pluginName]      | Human-readable plugin name, used in log output.                  |
 * | [prefixOverrides] | Optional `ClassName.variableName → value` map for unresolvable variables. |
 *
 * ## Output
 *
 * | Property      | Description                                                                |
 * |---------------|----------------------------------------------------------------------------|
 * | [outputFile]  | The generated CSV file. Default: `build/generated/resources/metrics-report/META-INF/services/qalipsis/metrics-and-events.csv`. |
 *
 * ## CSV format
 *
 * ```
 * category,name,type,severity,value_type,source_file
 * meter,kafka-produce-records,counter,,,KafkaProducer.kt
 * event,kafka-produce.producing-value,,info,Object,KafkaProducer.kt
 * ```
 *
 * - **Meters** have `category=meter`, with `type` filled (counter/timer/etc.) and `severity`/`value_type` empty.
 * - **Events** have `category=event`, with `severity` and `value_type` filled and `type` empty.
 * - Rows are sorted by source file then by name within each category.
 * - Duplicates are removed: meters by (name, type), events by (name, severity, valueType).
 *
 * ## Caching
 *
 * This task is annotated with [@CacheableTask][CacheableTask], so Gradle will skip execution
 * when the inputs (source files + configuration) have not changed since the last run.
 *
 * ## Unresolved prefix warnings
 *
 * After generation, any entries whose names still contain unresolved `$variable` tokens are
 * logged at `WARN` level with instructions on how to fix them via [prefixOverrides].
 */
@CacheableTask
abstract class MetricsReportTask : DefaultTask() {

    /** Kotlin source directories to scan. Typically `src/main/kotlin` from root + subprojects. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    /** Human-readable plugin name (e.g. `"kafka"`), derived from the root project name. */
    @get:Input
    abstract val pluginName: Property<String>

    /** Optional overrides for variables that cannot be resolved from source. See [MetricsReportExtension.prefixOverrides]. */
    @get:Input
    @get:Optional
    abstract val prefixOverrides: MapProperty<String, String>

    /** The CSV file to generate. Wired into the resource source set so it ends up in the JAR. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /**
     * Executes the analysis and CSV generation.
     *
     * The workflow is:
     * 1. Walk all `.kt` files under [sourceDirs].
     * 2. Analyze each file with [SourceAnalyzer.analyzeFile], collecting meters and events.
     * 3. Deduplicate the collected entries.
     * 4. Write the sorted CSV to [outputFile].
     * 5. Log warnings for any unresolved entries.
     */
    @TaskAction
    fun generate() {
        val analyzer = SourceAnalyzer()
        val overrides = prefixOverrides.getOrElse(emptyMap())

        val allMeters = mutableListOf<MeterEntry>()
        val allEvents = mutableListOf<EventEntry>()

        sourceDirs.files
            .filter { it.isDirectory }
            .forEach { srcDir ->
                srcDir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val result = analyzer.analyzeFile(file, overrides)
                        allMeters.addAll(result.meters)
                        allEvents.addAll(result.events)
                    }
            }

        // Deduplicate meters by (name, type) and events by (name, severity, valueType).
        val uniqueMeters = allMeters.distinctBy { "${it.name}::${it.type}" }
        val uniqueEvents = allEvents.distinctBy { "${it.name}::${it.severity}::${it.valueType}" }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.write("category,name,type,severity,value_type,source_file")
            writer.newLine()

            for (meter in uniqueMeters.sortedWith(compareBy({ it.sourceFile }, { it.name }))) {
                writer.write("meter,${csvEscape(meter.name)},${meter.type},,,${meter.sourceFile}")
                writer.newLine()
            }

            for (event in uniqueEvents.sortedWith(compareBy({ it.sourceFile }, { it.name }))) {
                writer.write("event,${csvEscape(event.name)},,${event.severity},${csvEscape(event.valueType)},${event.sourceFile}")
                writer.newLine()
            }
        }

        // Log warnings for unresolved names.
        val unresolvedMeters = uniqueMeters.filter { !it.resolved }
        val unresolvedEvents = uniqueEvents.filter { !it.resolved }
        if (unresolvedMeters.isNotEmpty() || unresolvedEvents.isNotEmpty()) {
            logger.warn("WARNING: Some entries have unresolved name prefixes.")
            logger.warn("Use qalipsisBuild { metricsReport { prefixOverrides.put(\"ClassName.variableName\", \"value\") } } to configure.")
            unresolvedMeters.forEach { logger.warn("  Meter '${it.name}' in ${it.sourceFile}") }
            unresolvedEvents.forEach { logger.warn("  Event '${it.name}' in ${it.sourceFile}") }
        }

        logger.lifecycle(
            "Metrics report generated: ${output.absolutePath} (${uniqueMeters.size} meters, ${uniqueEvents.size} events)"
        )
    }

    /** Escapes a string for safe inclusion in a CSV field (wraps in quotes if it contains commas, quotes, or newlines). */
    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}

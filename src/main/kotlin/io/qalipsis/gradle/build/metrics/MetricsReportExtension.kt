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

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the **metrics report** feature of the `io.qalipsis.build` plugin.
 *
 * This extension is nested inside [QalipsisBuildExtension] and controls whether the
 * `generateMetricsReport` task runs and how the [SourceAnalyzer] resolves unresolvable variables.
 *
 * ## Example
 *
 * ```kotlin
 * qalipsisBuild {
 *     metricsReport {
 *         enabled.set(true)
 *         prefixOverrides.put("RedisLettucePollStepNewImpl.redisMethod", "scan")
 *         prefixOverrides.put("StepContextBasedSocketMonitoringCollector.stepQualifier", "http")
 *     }
 * }
 * ```
 *
 * ## When are prefix overrides needed?
 *
 * The [SourceAnalyzer] resolves `$variable` references in meter/event names by scanning `val`
 * declarations in the same file. When a variable comes from an external source (constructor
 * parameter, function argument, computed expression), the analyzer cannot resolve it and marks
 * the entry as unresolved. The build will emit a warning like:
 * ```
 * WARNING: Some entries have unresolved name prefixes.
 *   Meter 'redis-lettuce-poll-$redisMethod-records' in MyStep.kt
 * ```
 *
 * To fix this, add a [prefixOverrides] entry mapping `ClassName.variableName` to the desired
 * literal value. The class name is the Kotlin file name without the `.kt` extension.
 */
abstract class MetricsReportExtension {

    /**
     * Whether the metrics report generation is enabled. Defaults to `false` (disabled).
     *
     * When `false`, the `generateMetricsReport` task is skipped and no source sets are modified.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Manual overrides for prefix variables that cannot be resolved from source.
     *
     * Keys follow the format `ClassName.variableName`, where `ClassName` is the Kotlin file name
     * without the `.kt` extension. Values are the literal string to substitute.
     *
     * Example:
     * ```kotlin
     * prefixOverrides.put("RedisLettucePollStepNewImpl.redisMethod", "scan")
     * ```
     *
     * This would resolve `$redisMethod` to `"scan"` when analyzing `RedisLettucePollStepNewImpl.kt`,
     * producing a fully-resolved meter name like `"redis-lettuce-poll-scan-records"`.
     */
    abstract val prefixOverrides: MapProperty<String, String>
}

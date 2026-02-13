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

package io.qalipsis.gradle.build

import io.qalipsis.gradle.build.metrics.MetricsReportExtension
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * Top-level DSL extension for the `io.qalipsis.build` plugin, exposed as `qalipsisBuild { ... }`
 * in `build.gradle.kts`.
 *
 * Each feature of the plugin is configured through a dedicated nested extension. Currently the
 * only feature is [metricsReport]; additional features can be added here in the future.
 *
 * ## Example
 *
 * ```kotlin
 * qalipsisBuild {
 *     metricsReport {
 *         enabled.set(true)
 *         prefixOverrides.put("MyStep.redisMethod", "scan")
 *     }
 * }
 * ```
 */
abstract class QalipsisBuildExtension @Inject constructor(objects: ObjectFactory) {

    /** Configuration for the metrics report feature. See [MetricsReportExtension]. */
    val metricsReport: MetricsReportExtension = objects.newInstance(MetricsReportExtension::class.java)

    /** Configures the metrics report feature using a DSL block. */
    fun metricsReport(action: Action<MetricsReportExtension>) {
        action.execute(metricsReport)
    }
}

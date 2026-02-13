# QALIPSIS Build Plugin

Gradle plugin providing shared build logic for [QALIPSIS](https://qalipsis.io) plugin projects.

**Plugin ID:** `io.qalipsis.build`
**Group:** `io.qalipsis`
**License:** AGPL-3.0

## Features

### Metrics Report Generation

Scans Kotlin source files for `CampaignMeterRegistry` and `EventsLogger` API calls and produces a
CSV catalog of all declared meters and events. The CSV is packaged into the plugin JAR at
`META-INF/services/qalipsis/metrics-and-events.csv`, making the instrumentation inventory available
at runtime.

## Requirements

- Gradle 8.x
- Kotlin 1.9+
- Java 11+

## Installation

### From Maven Central / QALIPSIS Snapshots

In the plugin project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        // For snapshot versions:
        maven { url = uri("https://maven.qalipsis.com/repository/oss-snapshots/") }
        gradlePluginPortal()
    }
}
```

In `build.gradle.kts`:

```kotlin
plugins {
    id("io.qalipsis.build") version "0.1.0"
}
```

### From mavenLocal (development)

In `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

Then publish the plugin locally:

```bash
cd qalipsis-build-plugin
./gradlew publishToMavenLocal
```

## Configuration

The plugin is configured via the `qalipsisBuild` DSL block. All features are **disabled by default**.

```kotlin
qalipsisBuild {
    metricsReport {
        // Enable the metrics report generation (default: false).
        enabled.set(true)

        // Optional: supply values for variables the analyzer cannot resolve from source.
        prefixOverrides.put("RedisLettucePollStepNewImpl.redisMethod", "scan")
        prefixOverrides.put("StepContextBasedSocketMonitoringCollector.stepQualifier", "http")
    }
}
```

### `metricsReport` options

| Property          | Type                       | Default | Description                                          |
|-------------------|----------------------------|---------|------------------------------------------------------|
| `enabled`         | `Property<Boolean>`        | `false` | Enables the `generateMetricsReport` task.            |
| `prefixOverrides` | `MapProperty<String, String>` | empty   | Manual overrides for unresolvable prefix variables.  |

## Gradle Tasks

### `generateMetricsReport`

Scans `src/main/kotlin` across the root project and all subprojects, extracts meter and event
declarations, and writes a deduplicated CSV report.

- **Group:** `build`
- **Runs before:** `processResources` (when enabled)
- **Cacheable:** Yes (Gradle build cache compatible)

Run it manually:

```bash
./gradlew generateMetricsReport
```

Or let it run automatically as part of the build (it is wired before `processResources`):

```bash
./gradlew build
```

## Source Code Patterns Recognized

The analyzer detects meter and event registrations in several coding styles commonly used
across QALIPSIS plugins.

### Meter calls (`CampaignMeterRegistry`)

**Explicit receiver calls:**

```kotlin
meterRegistry?.counter(scenarioName, stepName, "$meterPrefix-records", tags)
meterRegistry.timer(scenarioName, stepName, name = "$meterPrefix-time-to-response", tags = tags)
```

**Bare calls inside `apply`/`run` blocks:**

```kotlin
meterRegistry?.apply {
    counter(scenarioName, stepName, "$meterPrefix-records", tags)
    timer(scenarioName, stepName, "$meterPrefix-time-to-response", tags)
}
```

**Parameter calls inside `let`/`also` blocks:**

```kotlin
meterRegistry?.let {
    it.counter(scenarioName, stepName, "$meterPrefix-records", tags)
}
meterRegistry?.also { registry ->
    registry.timer(scenarioName, stepName, "$meterPrefix-time-to-response", tags)
}
```

Supported meter types: `counter`, `timer`, `gauge`, `summary`, `rate`, `throughput`.

Three argument forms are recognized:
1. **4-arg positional:** `counter(scenarioName, stepName, "name", tags)` -- the name is the 3rd argument.
2. **Named parameter:** `counter(scenarioName = ..., stepName = ..., name = "name", tags = ...)` -- matched by `name =`.
3. **1-arg shorthand:** `counter(CONSTANT_NAME)` -- a constant reference as the sole argument.

### Event calls (`EventsLogger`)

**Explicit receiver calls:**

```kotlin
eventsLogger?.info("$eventPrefix.received-records", recordCount, tags = tags)
```

**Bare calls inside `apply`/`run` blocks, or parameter calls inside `let`/`also` blocks:**

```kotlin
eventsLogger?.apply {
    info("$eventPrefix.success", receivedBytesCount, tags = tags)
    error("$eventPrefix.failure", e, tags = tags)
}
eventsLogger?.let {
    it.info("$eventPrefix.success", receivedBytesCount, tags = tags)
}
```

Supported severities: `trace`, `debug`, `info`, `warn`, `error`.

Events follow `(name, value?, tags?)` positional form or named parameters (`name = ...`, `value = ...`).

## Prefix Resolution

Meter and event names typically reference prefix variables declared in the class:

```kotlin
private val meterPrefix = "kafka-produce"
private val eventPrefix = "kafka-produce"
```

The analyzer resolves `$meterPrefix` and `${eventPrefix}` by scanning `val` declarations
in the same file. Resolution is **recursive**: if a prefix itself contains template variables,
they are resolved transitively:

```kotlin
val basePrefix = "redis-lettuce"
val meterPrefix = "$basePrefix-poll"
// resolves to "redis-lettuce-poll"
```

### Handling Unresolvable Variables

When a variable cannot be resolved from source (e.g. constructor parameters, dynamic expressions),
the entry is marked as **unresolved** and the build emits a warning:

```
WARNING: Some entries have unresolved name prefixes.
Use qalipsisBuild { metricsReport { prefixOverrides.put("ClassName.variableName", "value") } } to configure.
  Meter 'redis-lettuce-poll-$redisMethod-records' in RedisLettucePollStepNewImpl.kt
```

Fix these by adding entries to `prefixOverrides`:

```kotlin
qalipsisBuild {
    metricsReport {
        enabled.set(true)
        prefixOverrides.put("RedisLettucePollStepNewImpl.redisMethod", "scan")
    }
}
```

Override keys use the format **`ClassName.variableName`**, where `ClassName` is the Kotlin file
name without the `.kt` extension.

## CSV Output Format

The generated file at `META-INF/services/qalipsis/metrics-and-events.csv` follows this format:

```csv
category,name,type,severity,value_type,source_file
meter,kafka-produce-records,counter,,,KafkaProducer.kt
meter,kafka-produce-time-to-response,timer,,,KafkaProducer.kt
event,kafka-produce.producing-value,,info,Object,KafkaProducer.kt
event,kafka-produce.failure,,error,Throwable,KafkaProducer.kt
```

| Column        | Meter rows              | Event rows               |
|---------------|-------------------------|--------------------------|
| `category`    | `meter`                 | `event`                  |
| `name`        | Resolved meter name     | Resolved event name      |
| `type`        | counter, timer, etc.    | *(empty)*                |
| `severity`    | *(empty)*               | trace, debug, info, etc. |
| `value_type`  | *(empty)*               | Inferred type or `none`  |
| `source_file` | Kotlin source file name | Kotlin source file name  |

Rows are sorted by source file, then by name within each category. Duplicates are removed
(meters by name+type, events by name+severity+value_type).

## Value Type Inference

For events, the analyzer infers the type of the `value` parameter using heuristics:

| Pattern                                          | Inferred type |
|--------------------------------------------------|---------------|
| `arrayOf(...)`                                   | `Array<...>`  |
| Integer literal with `L` suffix                  | `Long`        |
| Decimal literal                                  | `Double`      |
| Integer literal                                  | `Int`         |
| Quoted string                                    | `String`      |
| `true` / `false`                                 | `Boolean`     |
| Contains `Duration` or `durationSinceNanos`      | `Duration`    |
| Ends with `.size`, `.count()`                    | `Int`         |
| Ends with `.toDouble()`, `.toInt()`, `.toLong()` | Matching type |
| Ends with `.message`, `.asText()`                | `String`      |
| Contains time-related keywords                   | `Duration`    |
| Looks like a numeric quantity (bytes, count...)  | `Number`      |
| Looks like a throwable (e, error, exception...)  | `Throwable`   |
| Everything else                                  | `Object`      |

When no value parameter is passed, the type is reported as `none`.

## Building the Plugin

```bash
cd qalipsis-build-plugin

# Run tests
./gradlew test

# Publish to mavenLocal for local development
./gradlew publishToMavenLocal

# Stage artifacts for release (used by CI with JReleaser)
./gradlew publish
```

## Project Structure

```
qalipsis-build-plugin/
  src/main/kotlin/io/qalipsis/gradle/build/
    QalipsisBuildPlugin.kt        # Plugin entry point: registers tasks and wires resource sets
    QalipsisBuildExtension.kt     # Top-level DSL: qalipsisBuild { ... }
    metrics/
      MetricsReportExtension.kt   # DSL for metricsReport { enabled, prefixOverrides }
      MetricsReportTask.kt        # Gradle task: orchestrates analysis and CSV generation
      SourceAnalyzer.kt           # Regex-based Kotlin source parser
  src/test/kotlin/io/qalipsis/gradle/build/
    metrics/
      SourceAnalyzerTest.kt       # Unit tests for the source analyzer
```

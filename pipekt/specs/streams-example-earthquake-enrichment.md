# Streams Example Plan: Earthquake Enrichment Stress Case

**Status:** Illustrative example document.

**Purpose:** Show one concrete INFINITE pipeline example using the active contracts and runtime model.

**Precedence:** This document is intentionally lower precedence than [streams-contracts-v1.md](./streams-contracts-v1.md), [current-implementation.md](./current-implementation.md), and [streams-phase-2-fix-plan.md](./streams-phase-2-fix-plan.md). If it conflicts with those documents, they win.

## Summary

This document defines a concrete example pipeline for `pipekt` v1 (`INFINITE` mode only) that you can implement and stress-test before AMQP/Kafka adapters.

It uses open APIs with list/object payloads and natural burst behavior:

1. **Source:** USGS Earthquake GeoJSON feed (updated frequently, list of events).
2. **Enrichment A:** Open-Meteo (weather context by event coordinates/time).
3. **Enrichment B:** OpenAQ (air-quality context by event coordinates/time) or no-key fallback APIs.

The example validates ingestion, backpressure, retries, checkpointing, lease reclaim, and restart behavior under sustained async load.

---

## Verified Resources and Links

Link checks executed on **2026-03-13** (HTTP status from direct `curl -L` checks):

| Resource | Purpose | URL | Status |
| --- | --- | --- | --- |
| USGS GeoJSON docs | Source contract and feed formats | https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php | 200 |
| USGS live feed (`all_hour`) | Real source batch endpoint | https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson | 200 |
| Open-Meteo docs | Weather API parameters and response shape | https://open-meteo.com/en/docs | 200 |
| Open-Meteo pricing/limits | Rate-limit planning | https://open-meteo.com/en/pricing | 200 |
| Open-Meteo live endpoint | Real enrichment endpoint | https://api.open-meteo.com/v1/forecast?latitude=37.7749&longitude=-122.4194&hourly=temperature_2m | 200 |
| OpenAQ docs root | OpenAQ API docs | https://docs.openaq.org/ | 200 |
| OpenAQ rate-limit docs | OpenAQ quota/reference | https://docs.openaq.org/using-the-api/rate-limits | 200 |
| OpenAQ live endpoint | Air-quality enrichment endpoint | https://api.openaq.org/v3/locations?limit=1 | 401 (reachable, auth required) |
| OpenAlex live endpoint | No-key fallback enrichment dataset | https://api.openalex.org/works?per-page=5 | 200 |
| World Bank indicator API | No-key fallback enrichment dataset | https://api.worldbank.org/v2/country/all/indicator/SP.POP.TOTL?format=json&per_page=5 | 200 |
| RestCountries endpoint | No-key static geo/demographic enrichment | https://restcountries.com/v3.1/all?fields=name,cca2,latlng,population | 200 |

Practical note:
- If you need fully open/no-key stress runs, prefer **USGS + Open-Meteo + (OpenAlex or World Bank)**.
- Keep OpenAQ as an optional profile when an API key is available.

---

## Detailed Domain and Resource Mapping

### Core Domain

The example models a single durable entity across pipeline stages:

- **Entity:** `QuakeCase`
- **Stable key:** `eventId` (`USGS feature.id`) -> used as `sourceId`
- **Join keys for enrichment:** `(latitude, longitude, eventTime)`
- **Terminal output unit:** `QuakeOutputRow` (one row per source event)

### Resource-to-Domain Mapping

1. **USGS `FeatureCollection` -> `QuakeEvent`**
- Input: `features[]`
- Mapped fields: `id`, `properties.time`, `properties.mag`, `properties.place`, `geometry.coordinates`
- Domain goal: canonical event row with stable business identity

2. **Open-Meteo Forecast -> `WeatherEnrichment`**
- Input: hourly arrays (`temperature_2m`, `wind_speed_10m`, `weathercode`)
- Mapped by nearest event hour
- Domain goal: environmental context per quake event

3. **OpenAQ (optional) or fallback -> secondary enrichment**
- OpenAQ profile: nearest station + pollutant measurements
- No-key fallback profile: add contextual metadata from OpenAlex/WorldBank/RestCountries
- Domain goal: additional dimensions to increase fan-out, serialization pressure, and checkpoint payload sizes

### Recommended Test Profiles

1. **Profile A (No key, high throughput):**
- Source: USGS
- Enrichment: Open-Meteo
- Secondary enrichment: OpenAlex or World Bank

2. **Profile B (With OpenAQ key):**
- Source: USGS
- Enrichment A: Open-Meteo
- Enrichment B: OpenAQ v3

3. **Profile C (Synthetic pressure mode):**
- Source: USGS + record amplification factor (e.g. x20 logical clones)
- Enrichment: cached/sampled external calls to isolate runtime/store limits

---

## Why This Case Fits Your Contracts

- Continuous source polling maps directly to `INFINITE` pipelines.
- Source returns list payloads (`FeatureCollection.features[]`), easy for `poll(maxItems)`.
- Enrichment requires async HTTP (`suspend StepFn`) and retry policies.
- Stable business key exists (`event.id`) for `sourceId` idempotency.
- Domain is simple enough to reason about while still stressing runtime paths.

---

## Pipeline Shape

Target pipeline name: `earthquake-enrichment-v1`

1. `source("usgs-feed", usgsSourceAdapter)`
2. `filter("significant-quakes")` keep events above threshold (e.g. `mag >= 2.5`)
3. `step("enrich-weather")` call Open-Meteo
4. `step("enrich-secondary")` call OpenAQ (or fallback API)
5. `step("to-output-row")` normalize final payload for sink/testing

No barrier/finalizer is used (v1 rule).

---

## Domain Model (Pseudocode)

```kotlin
// Source payload from USGS (simplified)
data class QuakeEvent(
  val eventId: String,          // stable business key (sourceId)
  val timeEpochMs: Long,
  val magnitude: Double?,
  val place: String?,
  val latitude: Double,
  val longitude: Double,
  val depthKm: Double?
)

data class WeatherEnrichment(
  val temperatureC: Double?,
  val windSpeedMs: Double?,
  val weatherCode: Int?,
  val observedAtEpochMs: Long?
)

data class AirQualityEnrichment(
  val pm25: Double?,
  val pm10: Double?,
  val o3: Double?,
  val sourceName: String?
)

data class QuakeEnriched(
  val event: QuakeEvent,
  val weather: WeatherEnrichment,
  val air: AirQualityEnrichment?,
  val contextTags: Map<String, String> = emptyMap()
)

data class QuakeOutputRow(
  val key: String,
  val ts: Long,
  val mag: Double?,
  val lat: Double,
  val lon: Double,
  val tempC: Double?,
  val pm25: Double?
)

sealed interface QuakeError : ItemFailure {
  data class UpstreamUnavailable(override val message: String) : QuakeError {
    override fun isRetryable() = true
  }
  data class BadPayload(override val message: String) : QuakeError
}
```

---

## Source Adapter (Ktor, KMP-Friendly) Pseudocode

```kotlin
class UsgsSourceAdapter(
  private val client: HttpClient,
  private val feedUrl: String,
  private val seen: SeenCache, // small in-memory dedupe to reduce duplicate append pressure
) : SourceAdapter<QuakeEvent> {

  override suspend fun poll(maxItems: Int): List<SourceRecord<QuakeEvent>> {
    val json = client.get(feedUrl).bodyAsText()
    val parsed: UsgsFeatureCollection = decode(json)

    return parsed.features
      .asSequence()
      .mapNotNull { f -> f.toQuakeEventOrNull() }
      .filter { event -> seen.tryMark(event.eventId) }   // local dedupe optimization only
      .take(maxItems)
      .map { event -> SourceRecord(id = event.eventId, payload = event) }
      .toList()
  }

  override suspend fun ack(records: List<SourceRecord<QuakeEvent>>) {
    // No-op for HTTP polling source.
    // Contract still honored: runtime calls ack only after appendIngress success.
  }

  override suspend fun nack(records: List<SourceRecord<QuakeEvent>>, retry: Boolean) {
    // No-op for HTTP polling source.
    // Retry behavior naturally happens on next poll.
  }
}
```

Notes:
- `sourceId` must always be `eventId`.
- Keep adapter transport metadata out of `StepFn`.

---

## Pipeline Definition Pseudocode

```kotlin
val definitionEither = pipeline<QuakeEvent>(
  name = "earthquake-enrichment-v1",
  maxInFlight = 25_000,
  retentionDays = 7, // high-volume pipeline -> short terminal retention
) {
  source("usgs-feed", usgsSourceAdapter)

  filter<QuakeEvent>(
    name = "significant-quakes",
    filteredReason = FilteredReason.BELOW_THRESHOLD
  ) { quake ->
    (quake.magnitude ?: 0.0) >= 2.5
  }

  step<QuakeEvent, Pair<QuakeEvent, WeatherEnrichment>>(
    name = "enrich-weather",
    retryPolicy = RetryPolicy(maxAttempts = 5, backoffMs = 500)
  ) { quake ->
    val weather = weatherApi.fetchAt(quake.latitude, quake.longitude, quake.timeEpochMs)
    quake to weather
  }

  step<Pair<QuakeEvent, WeatherEnrichment>, QuakeEnriched>(
    name = "enrich-secondary",
    retryPolicy = RetryPolicy(maxAttempts = 3, backoffMs = 750)
  ) { (quake, weather) ->
    val secondary = secondaryApi.enrich(quake, weather)
    QuakeEnriched(
      event = quake,
      weather = weather,
      air = secondary.air,
      contextTags = secondary.tags
    )
  }

  step<QuakeEnriched, QuakeOutputRow>(
    name = "to-output-row",
    retryPolicy = RetryPolicy(maxAttempts = 1)
  ) { enriched ->
    QuakeOutputRow(
      key = enriched.event.eventId,
      ts = enriched.event.timeEpochMs,
      mag = enriched.event.magnitude,
      lat = enriched.event.latitude,
      lon = enriched.event.longitude,
      tempC = enriched.weather.temperatureC,
      pm25 = enriched.air.pm25,
    )
  }
}
```

---

## Runtime Wiring (Pseudocode)

```kotlin
// Startup
val run = store.getOrCreateRun(
  pipeline = definition.name,
  planVersion = "earthquake-enrichment-v1"
)

// Must run before workers start (restart safety)
store.reclaimExpiredLeases(now = clock.now(), limit = 5000)

// Ingestion loop
launch {
  while (isActive) {
    if (store.countNonTerminal(run.id) >= definition.maxInFlight) {
      delay(200)
      continue
    }

    val records = sourceAdapter.poll(maxItems = 500)
    if (records.isEmpty()) {
      delay(500)
      continue
    }

    val ingress = records.map { r -> IngressRecord(sourceId = r.id, payload = r.payload) }
    store.appendIngress(run.id, ingress)
    sourceAdapter.ack(records)
  }
}

// Worker loops (one coroutine group per step)
definition.operators.forEach { step ->
  launch {
    while (isActive) {
      val items = store.claim(
        step = step.name,
        runId = run.id,
        limit = 64,
        leaseDuration = 30.seconds,
        workerId = "worker-${step.name}-${randomId()}"
      )
      if (items.isEmpty()) {
        delay(100)
        continue
      }
      items.forEach { executeAndCheckpoint(it, step) }
    }
  }
}

// Watchdog loop
launch {
  while (isActive) {
    delay(5.seconds)
    store.reclaimExpiredLeases(now = clock.now(), limit = 10_000)
  }
}
```

---

## HTTP Enrichment Client Pattern (Ktor) Pseudocode

```kotlin
class OpenMeteoApi(private val client: HttpClient) {
  suspend context(Raise<ItemFailure>) fun fetchAt(
    lat: Double,
    lon: Double,
    eventTs: Long
  ): WeatherEnrichment {
    val resp = client.get("https://api.open-meteo.com/v1/forecast") {
      parameter("latitude", lat)
      parameter("longitude", lon)
      parameter("hourly", "temperature_2m,wind_speed_10m,weathercode")
      parameter("timezone", "UTC")
      // nearest-hour selection handled in mapper
    }
    if (!resp.status.isSuccess()) raise(QuakeError.UpstreamUnavailable("meteo ${resp.status}"))
    return mapWeather(resp.bodyAsText(), eventTs)
  }
}

class OpenAqApi(private val client: HttpClient) {
  suspend context(Raise<ItemFailure>) fun fetchNearest(
    lat: Double,
    lon: Double,
    eventTs: Long
  ): AirQualityEnrichment {
    val resp = client.get("https://api.openaq.org/v3/locations") {
      parameter("coordinates", "$lat,$lon")
      parameter("radius", 25000)
      parameter("limit", 10)
      parameter("order_by", "distance")
      parameter("sort", "asc")
    }
    if (!resp.status.isSuccess()) raise(QuakeError.UpstreamUnavailable("openaq ${resp.status}"))
    return mapAirQuality(resp.bodyAsText(), eventTs)
  }
}
```

Fallback secondary API strategy (no-key profile):

```kotlin
data class SecondaryEnrichment(val air: AirQualityEnrichment?, val tags: Map<String, String>)

interface SecondaryApi {
  suspend context(Raise<ItemFailure>) fun enrich(
    quake: QuakeEvent,
    weather: WeatherEnrichment
  ): SecondaryEnrichment
}

class OpenAlexSecondaryApi(private val client: HttpClient) : SecondaryApi {
  override suspend context(Raise<ItemFailure>) fun enrich(
    quake: QuakeEvent,
    weather: WeatherEnrichment
  ): SecondaryEnrichment {
    val resp = client.get("https://api.openalex.org/works") { parameter("per-page", 3) }
    if (!resp.status.isSuccess()) raise(QuakeError.UpstreamUnavailable("openalex ${resp.status}"))
    // attach tiny contextual tags only; keep payload bounded
    return SecondaryEnrichment(
      air = null,
      tags = mapOf("secondary_source" to "openalex")
    )
  }
}
```

Recommended runtime controls:
- per-API semaphore (e.g. 32 for weather, 16 for air-quality)
- short request timeout (2-3s) + retries via `RetryPolicy`
- jittered local delay before retry to avoid thundering herd

---

## ktkit Integration Example (Composition Layer, Not Core)

Keep `pipekt.core` independent. Add ktkit only in app composition:

```kotlin
class PipelineHealthHandler(
  private val store: DurableStore,
  private val pipelineName: String,
) : AbstractRestHandler() {
  suspend fun getHealth(): HealthDto {
    val run = store.listActiveRuns(pipelineName).firstOrNull()
    val inFlight = run?.let { store.countNonTerminal(it.id) } ?: 0
    return HealthDto(
      pipeline = pipelineName,
      inFlight = inFlight,
      backpressurePaused = inFlight >= 25_000
    )
  }
}
```

Use ktkit to host lifecycle and health endpoints, not to define core stream contracts.

---

## Stress Test Plan

## Stage 1: Contract + Correctness

1. Build pipeline and assert `Either.Right`.
2. Duplicate ingress test: same `sourceId` appended twice => second insert counted as duplicate.
3. Retry test: `enrich-weather` fails twice then succeeds => attempt count increments and final status `COMPLETED`.
4. Filter test: low magnitude => status `FILTERED`, terminal payload nulled.
5. Fatal test: malformed event => status `FAILED`, `lastErrorJson` set, terminal payload nulled.

## Stage 2: Sustained Load (InMemory)

1. Synthetic source emits 500 records/poll, every 200ms.
2. 30 minutes run.
3. Validate:
- no unbounded memory growth
- ingestion pauses when `countNonTerminal >= maxInFlight`
- item throughput remains stable after first warm-up minutes

## Stage 3: Crash + Recovery

1. Start runtime and process load.
2. Kill process while items are `IN_PROGRESS`.
3. Restart runtime:
- call `reclaimExpiredLeases` before worker launch
- verify stuck items return to `PENDING`
- verify processing resumes without duplicate completed rows

## Stage 4: External API Pressure

1. Replace synthetic steps with real Open-Meteo/OpenAQ.
2. Ramp worker counts every 5 minutes.
3. Track:
- success rate
- retry rate
- p95 step latency
- watchdog reclaim count
- duplicate ingress ratio

Stop criteria:
- repeated 429/5xx causing sustained retry backlog
- non-terminal count pinned at max for >10 minutes

---

## Metrics To Record

- `ingress.polled.count`
- `ingress.appended.count`
- `ingress.duplicate.count`
- `inflight.non_terminal`
- `step.exec.count{step,status}`
- `step.retry.count{step}`
- `step.latency.ms{step,p50,p95,p99}`
- `watchdog.reclaimed.count`
- `terminal.payload_nulled.count`

---

## Acceptance Criteria

This example is accepted when all below hold:

1. Pipeline runs continuously in `INFINITE` mode for at least 30 minutes.
2. Backpressure pause/resume works via `countNonTerminal` threshold.
3. Retry backoff is observable in step execution timings.
4. Restart recovers leased work without manual intervention.
5. Terminal items always have `payloadJson = null`.
6. Duplicate source records do not produce duplicate logical work items.
7. Example can be hosted by a ktkit app layer without introducing ktkit into `pipekt.core`.

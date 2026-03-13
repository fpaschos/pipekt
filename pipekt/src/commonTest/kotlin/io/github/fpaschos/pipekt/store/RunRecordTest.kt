package io.github.fpaschos.pipekt.store

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.time.Instant

/**
 * Contract coverage for [RunRecord] as a persistent entity.
 *
 * These tests validate the "RunRecord minimum fields" contract from
 * `plans/streams-contracts-v1.md`: every record must carry a run id, pipeline
 * name, plan version, status, and creation / update timestamps, and expose
 * them as immutable value-type fields.
 */
class RunRecordTest :
    FunSpec({

        test("run record exposes minimum required fields") {
            val createdAt = Instant.fromEpochMilliseconds(1000L)
            val updatedAt = Instant.fromEpochMilliseconds(2000L)
            val r =
                RunRecord(
                    id = "run-1",
                    pipeline = "p",
                    planVersion = "v1",
                    status = "ACTIVE",
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            r.id.shouldNotBeBlank()
            r.pipeline.shouldBe("p")
            r.planVersion.shouldBe("v1")
            r.status.shouldBe("ACTIVE")
            r.createdAt.shouldNotBe(null)
            r.updatedAt.shouldNotBe(null)
        }
    })

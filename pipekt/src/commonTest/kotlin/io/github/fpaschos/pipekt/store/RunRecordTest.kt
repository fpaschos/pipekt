package io.github.fpaschos.pipekt.store

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

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
            val r =
                RunRecord(
                    id = "run-1",
                    pipeline = "p",
                    planVersion = "v1",
                    status = "ACTIVE",
                    createdAtMs = 1000L,
                    updatedAtMs = 2000L,
                )
            r.id.shouldNotBeBlank()
            r.pipeline.shouldBe("p")
            r.planVersion.shouldBe("v1")
            r.status.shouldBe("ACTIVE")
            r.createdAtMs.shouldBeGreaterThan(0L)
            r.updatedAtMs.shouldBeGreaterThan(0L)
        }
    })

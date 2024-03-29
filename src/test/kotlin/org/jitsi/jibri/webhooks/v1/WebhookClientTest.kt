/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.jibri.webhooks.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.delay
import org.jitsi.jibri.status.ComponentBusyStatus
import org.jitsi.jibri.status.ComponentHealthStatus
import org.jitsi.jibri.status.JibriStatus
import org.jitsi.jibri.status.OverallHealth
import org.jitsi.jibri.util.TaskPools
import org.jitsi.test.concurrent.FakeExecutorService

class WebhookClientTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val requests = mutableListOf<HttpRequestData>()
    val goodStatus = JibriStatus(
        ComponentBusyStatus.IDLE,
        OverallHealth(
            ComponentHealthStatus.HEALTHY,
            mapOf()
        )
    )
    val badStatus = JibriStatus(
        ComponentBusyStatus.IDLE,
        OverallHealth(
            ComponentHealthStatus.UNHEALTHY,
            mapOf()
        )
    )
    val client = WebhookClient(
        "test",
        client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    with(request.url.toString()) {
                        when {
                            contains("success") -> {
                                respondOk()
                            }
                            contains("delay") -> {
                                delay(1000)
                                respondOk()
                            }
                            contains("error") -> {
                                respondError(HttpStatusCode.BadRequest)
                            }
                            else -> error("Unsupported URL")
                        }
                    }
                }
            }
        }
    )

    // Coroutines will sometimes try to execute a launch in the calling thread, so the normal FakeExecutorService
    // doesn't work as it relies on the calling code finishing and then the test code being able to call runOne
    // or runAll, etc.  This overrides the execute method (which is what gets used by coroutine dispatchers) and
    // executes the Runnable immediately.
    val ioExecutor: FakeExecutorService = spyk {
        val runnable = slot<Runnable>()
        every { execute(capture(runnable)) } answers {
            runnable.captured.run()
        }
    }

    beforeSpec {
        TaskPools.ioPool = ioExecutor
    }

    afterSpec {
        TaskPools.Companion.ioPool = TaskPools.DefaultIoPool
    }

    context("when the client") {
        context("has a valid subscriber") {
            client.addSubscriber("success")
            context("calling updateStatus") {
                client.updateStatus(goodStatus)
                should("send a POST to the subscriber at the proper url") {
                    requests shouldHaveSize 1
                    with(requests[0]) {
                        url.toString() shouldContain "/v1/status"
                        method shouldBe HttpMethod.Post
                    }
                }
                should("send the correct data") {
                    requests[0].body.contentType shouldBe ContentType.Application.Json
                    val requestBody = requests[0].body.toByteReadPacket().readText()
                    requestBody shouldBe jacksonObjectMapper().writeValueAsString(
                        JibriEvent.HealthEvent("test", goodStatus)
                    )
                    requestBody shouldContain """
                        "jibriId":"test"
                    """.trimIndent()
                }
                context("and calling updateStatus again") {
                    client.updateStatus(badStatus)
                    should("send another request with the new status") {
                        requests shouldHaveSize 2
                        val request1Body = requests[1].body.toByteReadPacket().readText()

                        request1Body shouldContain jacksonObjectMapper().writeValueAsString(
                            JibriEvent.HealthEvent("test", badStatus)
                        )
                    }
                }
            }
        }
        context("has multiple subscribers") {
            client.addSubscriber("https://success")
            client.addSubscriber("https://delay")
            client.addSubscriber("https://error")
            context("calling updateStatus") {
                client.updateStatus(goodStatus)
                should("send a POST to the subscribers at the proper url") {
                    requests shouldHaveSize 3
                    requests shouldContainRequestTo "success"
                    requests shouldContainRequestTo "delay"
                    requests shouldContainRequestTo "error"
                }
                context("and calling updateStatus again") {
                    requests.clear()
                    client.updateStatus(goodStatus)
                    should("send a POST to the subscribers at the proper url") {
                        requests shouldHaveSize 3
                        requests shouldContainRequestTo "success"
                        requests shouldContainRequestTo "delay"
                        requests shouldContainRequestTo "error"
                    }
                }
            }
        }
    }
})

infix fun List<HttpRequestData>.shouldContainRequestTo(host: String) {
    this.find { it.url.host.contains(host) } shouldNotBe null
}

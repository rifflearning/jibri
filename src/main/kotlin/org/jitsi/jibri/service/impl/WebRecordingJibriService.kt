package org.jitsi.jibri.service.impl

import org.jitsi.jibri.config.Config
import org.jitsi.jibri.config.XmppCredentials
import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.selenium.JibriSelenium
import org.jitsi.jibri.selenium.RECORDING_URL_OPTIONS
import org.jitsi.jibri.service.ErrorSettingPresenceFields
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.status.ComponentState
import org.jitsi.jibri.util.extensions.error
import org.jitsi.jibri.util.whenever
import org.jitsi.metaconfig.config
import org.jitsi.xmpp.extensions.jibri.JibriIq

/**
 * Parameters needed for starting a [WebRecordingJibriService]
 */
data class WebRecordingParams(
    /**
     * Which call we'll join
     */
    val callParams: CallParams,
    /**
     * The ID of this session
     */
    val sessionId: String,
    /**
     * The login information needed to appear invisible in
     * the call
     */
    val callLoginParams: XmppCredentials
)

/**
 * [WebRecordingJibriService] is the [JibriService] responsible for joining
 * a web call, capturing its audio and video, and writing that audio and video
 * to a server for further analysis.
 */
class WebRecordingJibriService(
    private val webRecordingParams: WebRecordingParams,
    private val jibriSelenium: JibriSelenium = JibriSelenium()
) : StatefulJibriService("Web recording") {
    /**
     * The job dispatcher url that will be responsible for analysis services creation
     * where captured by client data should go.
     */
    private val dispatcherUrl: String by config {
        "JibriConfig::dispatcherUrl" { Config.legacyConfigSource.dispatcherUrl!! }
        "jibri.analysis.dispatcher".from(Config.configSource)
    }

    init {
        logger.info("Dispatcher url for analysis processes creation: $dispatcherUrl")
        registerSubComponent(JibriSelenium.COMPONENT_ID, jibriSelenium)
    }

    override fun start() {
        jibriSelenium.joinCall(
                webRecordingParams.callParams.callUrlInfo.copy(urlParams = RECORDING_URL_OPTIONS),
                webRecordingParams.callLoginParams)

        whenever(jibriSelenium).transitionsTo(ComponentState.Running) {
            logger.info("Selenium joined the call, starting the capturer")
            try {
                jibriSelenium.addToPresence("session_id", webRecordingParams.sessionId)
                jibriSelenium.addToPresence("mode", JibriIq.RecordingMode.FILE.toString())
                jibriSelenium.sendPresence()
                jibriSelenium.startCapturing(dispatcherUrl)
            } catch (t: Throwable) {
                logger.error("Error while setting fields in presence", t)
                publishStatus(ComponentState.Error(ErrorSettingPresenceFields))
            }
        }
    }

    override fun stop() {
        logger.info("Stopping capturer and quitting selenium")
        jibriSelenium.stopCapturing()
        jibriSelenium.leaveCallAndQuitBrowser()
    }
}

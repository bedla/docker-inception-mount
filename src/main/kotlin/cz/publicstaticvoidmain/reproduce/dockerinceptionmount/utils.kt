package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.InspectContainerResponse
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun Path.toLinuxPathStr() = this.toString().replace("\\", "/")

fun InspectContainerResponse.waitAndCheckContainerStartState(dockerClient: DockerClient, image: String, logger: Logger) {
    await atMost 5.seconds withPollInterval 500.milliseconds until {
        val status = dockerClient.inspectContainerCmd(this.id!!).exec().state.status
        logger.warn("Polling for container {} state.status: {}", image, status)
        status == "exited"
    }

    dockerClient.inspectContainerCmd(this.id!!).exec()!!.run {
        val exitCode = this.state?.exitCodeLong
        if (exitCode != 0L) {
            logger.error("Error during container start, state is: {}", this.state)
            throw IllegalStateException("Container exited with error $exitCode")
        }
    }

}
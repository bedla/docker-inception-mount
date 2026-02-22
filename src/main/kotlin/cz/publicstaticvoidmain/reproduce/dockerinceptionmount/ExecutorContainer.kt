package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import com.github.dockerjava.api.command.InspectContainerResponse
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

open class ExecutorContainer(
    private val image: String,
    private val command: List<String>,
    private val consoleOutMount: ConsoleOutMount,
    private val mounts: List<MyMount>,
    private val startupTimeout: Duration,
    private val postInit: (ExecutorContainer) -> Unit
) : GenericContainer<ExecutorContainer>(
    run {
        // NOTE: Standard JVM runtime is OK, but for GraalVM native runtime resources has "resource:" prefix and
        //       Testcontainers are not able to transfer class-path files to the image.
        //       See https://github.com/testcontainers/testcontainers-java/pull/10810
        //       When Testcontainers will be fixed we will use `.withFileFromClasspath("/to-copy/entrypoint.sh", "entrypoint.sh")`.
        val fileEntrypointSh = createTempDirectory("docker-temp-")
            .run {
                val outEntrypointFile = this.resolve("entrypoint.sh")
                Files.writeString(outEntrypointFile, ClassPathResource("entrypoint.sh").getContentAsString(Charsets.UTF_8))
                logger.info("Host entrypoint.sh file is: {}", outEntrypointFile)
                outEntrypointFile
            }


        ImageFromDockerfile()
            .withDockerfileFromBuilder { builder ->
                builder
                    .from(image)
                    .copy("/to-copy/entrypoint.sh", "/usr/local/bin/entrypoint.sh")
                    .run("chmod", "+x", "/usr/local/bin/entrypoint.sh")
                    // info: here we are doing some dark-magic to make correctly work combination of Docker EXEC and SHELL forms
                    .entryPoint(*arrayOf("/usr/local/bin/entrypoint.sh"))
                    .build()
            }
            .withFileFromFile("/to-copy/entrypoint.sh", fileEntrypointSh.toFile())
    }
) {
    fun initialize(): ExecutorContainer {
        val commandStr = buildList {
            if (consoleOutMount.containerFile.parent != null) {
                val parentDirStr = consoleOutMount.containerFile.parent.toLinuxPathStr()
                add("mkdir -p $parentDirStr &&")
            }
            addAll(command)
            add("> ${consoleOutMount.containerFile.toLinuxPathStr()} 2>&1")
//            add("; sleep 30")
        }.joinToString(" ")

        this
            .withLogConsumer(Slf4jLogConsumer(logger))
            .waitingFor(
                LogMessageWaitStrategy().withRegEx(".*CMD FINISH.*")
                    .withStartupTimeout(startupTimeout.toJavaDuration())
            )
            .withCommand("sh", "-c", commandStr)
            .apply {
                consoleOutMount.mount.mount(this)
                mounts.forEach {
                    it.mount(this)
                }
            }

        postInit(this)
        return this
    }

    override fun containerIsStarted(containerInfo: InspectContainerResponse?) {

        await atMost 5.seconds withPollInterval 500.milliseconds until {
            val status = dockerClient.inspectContainerCmd(containerInfo?.id!!).exec().state.status
            logger.warn("Polling for container {} state.status: {}", image, status)
            status == "exited"
        }

        dockerClient.inspectContainerCmd(containerInfo?.id!!).exec()!!.run {
            val exitCode = this.state?.exitCodeLong
            if (exitCode != 0L) {
                logger.error("Error during container start, state is: {}", this.state)
                throw IllegalStateException("Container exited with error $exitCode")
            }
        }
    }

    data class ConsoleOutMount(
        val mount: MyMount,
        val containerFile: Path
    )

    companion object {
        private val logger = LoggerFactory.getLogger(ExecutorContainer::class.java)!!
    }
}

@file:OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)

package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.model.LogConfig
import org.apache.maven.shared.invoker.DefaultInvocationRequest
import org.apache.maven.shared.invoker.DefaultInvoker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.util.FileSystemUtils.copyRecursively
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


class NativeTests {
    @Test
    fun nativeImageRunUsingDinD() {
        val regex = "Successfully built image '(.+)'".toRegex()

        val workDirBuild = createTempDirectory("native-tests-build")

        logger.info("Copying sources to $workDirBuild")
        copyRecursively(Path("."), workDirBuild)

        val pomXml = workDirBuild.resolve("pom.xml")
        val pomXmlStr = Files.readString(pomXml).replace("<tags></tags>", "<tags><tag>ivos</tag></tags>")
        Files.writeString(workDirBuild.resolve("pom.xml"), pomXmlStr)

        val myImage = GenericContainer(
            ImageFromDockerfile()
                .withDockerfileFromBuilder { builder: DockerfileBuilder ->
                    builder
                        .from("ghcr.io/graalvm/native-image-community:25")
                        .run(
                            """
                                microdnf install -y maven tar gzip curl && 
                                microdnf clean all"""
                        )
                        .entryPoint(
                            "bash", "-c", "{ " +
                                    "cd /work-dir && " +
                                    "mvn clean package -DskipTests -P native && " +
                                    "mvn -P native spring-boot:build-image-no-fork ; } ; " +
                                    "echo MYFINISH"
                        )
                        .build()
                }
        ).use { container ->
            val refImage = AtomicReference("n/a")

            container.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("${logger.name}.BUILDER")))
                .withLogConsumer { frame ->
                    val line = frame.utf8StringWithoutLineEnding
                    val matchResult = regex.find(line)
                    if (matchResult != null) {
                        refImage.store(matchResult.groupValues[1])
                    }
                }
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!.withLogConfig(LogConfig(LogConfig.LoggingType.JSON_FILE))
                }
                .withFileSystemBind(workDirBuild.toString(), "/work-dir", BindMode.READ_WRITE)
                .withFileSystemBind(DockerClientFactory.instance().getRemoteDockerUnixSocketPath(), "/var/run/docker.sock", BindMode.READ_WRITE)
                .waitingFor(Wait.forLogMessage(".*MYFINISH.*", 1).withStartupTimeout(5.minutes.toJavaDuration()))

            container.start()

            refImage.load()
        }

        logger.info("Running tests using Image: $myImage")

        val loggerRunner = LoggerFactory.getLogger("${logger.name}.RUNNER")

        val workDirRun = createTempDirectory("native-tests-run")
        val inputHostDir = workDirRun.resolve("input").createDirectories()
        val outputHostDir = workDirRun.resolve("output").createDirectories()

        val inputHostFile = inputHostDir.resolve("input.txt")
        val outputHostFile = outputHostDir.resolve("out-foo.txt")

        Files.writeString(inputHostFile, "potato_${Uuid.random()}_orange")

        val containerInputDir = "/my-input-dir"
        val containerOutputDir = "/my-output-dir"

        class MyGenericContainer : GenericContainer<MyGenericContainer>(
            ImageFromDockerfile()
                .withDockerfileFromBuilder { builder: DockerfileBuilder ->
                    builder
                        .from(myImage)
                        .entryPoint(
                            "sh", "-c", "/cnb/process/web " +
                                    "--input-file=$containerInputDir/input.txt --output-dir=$containerOutputDir --output-filename=${outputHostFile.fileName} --run-in-docker " +
                                    $$""" ; res=$?; [ $res -eq 0 ] && echo "MYFINISH" || echo "MYFINISH with error"; (exit $res)"""
                        )
                        .build()
                }
        ) {
            override fun containerIsStarted(containerInfo: InspectContainerResponse?) {
                val inspectResult = dockerClient.inspectContainerCmd(containerInfo?.id!!).exec()
                val inspectStr = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(inspectResult)
                logger.info("Inspect result: \n{}", inspectStr)

                containerInfo.waitAndCheckContainerStartState(dockerClient, image.toString(), loggerRunner)
            }
        }

        MyGenericContainer().use { container ->
            container
                .withLogConsumer(Slf4jLogConsumer(loggerRunner))
                .withFileSystemBind(inputHostDir.toString(), containerInputDir, BindMode.READ_WRITE)
                .withFileSystemBind(outputHostDir.toString(), containerOutputDir, BindMode.READ_WRITE)
                .withEnv("DOCKER_HOST", "tcp://host.docker.internal:2375")
                .waitingFor(Wait.forLogMessage(".*MYFINISH.*", 1).withStartupTimeout(10.seconds.toJavaDuration()))

            container.start()
        }

        val output = Files.readString(outputHostFile)
        assertThat(output).isNotEmpty
        val lines = output.lines()
        assertThat(lines).hasSize(5)
        assertThat(lines[0]).startsWith("output-").endsWith("-end")
        assertThat(lines[1]).startsWith("potato_").endsWith("_orange")
        assertThat(lines[2]).isEqualTo("###")
        assertThat(lines[3]).startsWith("output-").endsWith("-end")
        assertThat(lines[4]).startsWith("potato_").endsWith("_orange")
        assertThat(lines[0]).isNotEqualTo(lines[3])
        assertThat(lines[1]).isEqualTo(lines[4])
    }

    @Test
    @Disabled
    fun process() {
        val request = DefaultInvocationRequest()

        request.setPomFile(File("pom.xml"))
        request.setGoals(mutableListOf<String?>("clean compile"))

        val invoker = DefaultInvoker()

        val result = invoker.execute(request)
        check(result.exitCode == 0) { "Maven build failed with exit code " + result.exitCode }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NativeTests::class.java)!!
    }
}

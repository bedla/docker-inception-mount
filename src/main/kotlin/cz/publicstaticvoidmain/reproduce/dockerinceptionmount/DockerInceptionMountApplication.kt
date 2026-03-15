@file:OptIn(ExperimentalUuidApi::class)

package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Volume
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.SelinuxContext
import org.testcontainers.utility.MountableFile
import org.testcontainers.utility.ResourceReaper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@SpringBootApplication
class DockerInceptionMountApplication

fun main(args: Array<String>) {
    runApplication<DockerInceptionMountApplication>(*args)
}


@Component
class Starter(
    private val myBusinessLogicProcessor: MyBusinessLogicProcessor
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        // docker run -v c:\temp\my:/my-out -e DOCKER_HOST=tcp://host.docker.internal:2375 docker.io/library/docker-inception-mount:0.0.1-SNAPSHOT --run-in-docker --output-dir=/my-out
        // docker run -v c:\temp\my:/my-out -v c:\temp\my\input.txt:/my-input/input.txt -e DOCKER_HOST=tcp://host.docker.internal:2375 docker.io/library/docker-inception-mount:0.0.1-SNAPSHOT --run-in-docker --output-dir=/my-out --input-file=/my-input/input.txt

        val outputDir = args.getOptionValues("output-dir")?.first()?.let { Path(it) } ?: error("Missing output directory in args: optionNames=${args.optionNames}, nonOptionArgs=${args.nonOptionArgs} ")
        val outputFilename = args.getOptionValues("output-filename")?.first() ?: "my-result.txt"
        val inputFile = args.getOptionValues("input-file")?.first()?.let { Path(it) }

        if (inputFile != null) {
            require(inputFile.exists() && inputFile.isRegularFile()) { "File $inputFile does not exist or is not regular file." }
        }

        val workingDirectories = createWorkingDirectories(args)
            .also { logger.info("Working directory: {}", it) }

        val inputFileContext = if (inputFile != null) {
            createInputFileContext(inputFile, workingDirectories)
        } else {
            null
        }


        val itemsToProcess = listOf(
            Uuid.random(),
            Uuid.random()
        )
        val files = itemsToProcess.map {
            myBusinessLogicProcessor.process(
                containerUuid = it,
                workingDirectories = workingDirectories,
                inputFileContext
            )
        }

        mergeResultFilesIntoOutputSingleFile(workingDirectories, outputDir, outputFilename, files)
    }

    private fun mergeResultFilesIntoOutputSingleFile(workingDirectories: WorkingDirectories, outputDir: Path, outputFilename: String, files: List<Path>) {
        val outputPath = when (workingDirectories) {
            is NamedVolumeWorkingDirectories -> workingDirectories.mainContainerWorkDir.resolve("export")
            is HostWorkingDirectories -> outputDir
        }.createDirectories()
            .also { logger.info("Final output path: {}", it) }

        val mergedResultOutputFilePath = outputPath.resolve(outputFilename)
            .also {
                if (it.parent != null) {
                    it.parent.createDirectories()
                }
            }

        val mergedResultStr = files.joinToString("\n###\n") { Files.readString(it) }
        logger.info("Writing merged results to: {}", mergedResultOutputFilePath)
        Files.write(mergedResultOutputFilePath, mergedResultStr.toByteArray())

        if (workingDirectories is NamedVolumeWorkingDirectories) {
            val dockerHostMountOutputFilePath = outputDir.resolve(outputFilename)
            logger.info("Copying final result source: {} to target: {}", mergedResultOutputFilePath, dockerHostMountOutputFilePath)
            Files.copy(
                mergedResultOutputFilePath,
                dockerHostMountOutputFilePath,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun createInputFileContext(
        inputFile: Path?,
        workingDirectories: WorkingDirectories
    ): MyBusinessLogicProcessor.InputFileContext? {
        return if (inputFile != null) {
            when (workingDirectories) {
                is NamedVolumeWorkingDirectories -> {
                    val mount = VolumeMount(
                        volumeName = workingDirectories.namedVolume.name,
                        containerMount = workingDirectories.mountedVolumeBaseDir
                    )

                    val containerFile = mount.containerMount
                        .resolve("my-input")
                        .resolve(inputFile.fileName)
                        .also { logger.info("Container input file: {}", it) }

                    DockerClientFactory.instance().runInsideDocker(
                        { cmd ->
                            cmd.hostConfig!!.withBinds(
                                Bind(
                                    mount.volumeName,
                                    Volume(mount.containerMount.toLinuxPathStr()),
                                    AccessMode.rw,
                                    SelinuxContext.SHARED.selContext,
                                )
                            )
                            // INFO: We have 30s to copy file, then container is finished
                            cmd.withCmd("sh", "-c", "sleep 30")
                        },
                        { dockerClient, containerId ->

                            dockerClient.execCreateCmd(containerId)
                                .withCmd("sh", "-c", "mkdir -p ${containerFile.parent.toLinuxPathStr()}")
                                .exec()
                                .id
                                .also { execId ->
                                    dockerClient.execStartCmd(execId)
                                        .exec(object : ResultCallback.Adapter<Frame>() {})
                                        .awaitCompletion()
                                }

                            dockerClient.copyFileToContainer(
                                MountableFile.forHostPath(inputFile),
                                containerFile.toLinuxPathStr(),
                                containerId
                            )
                        }
                    )

                    MyBusinessLogicProcessor.InputFileContext(containerFile, mount)
                }

                is HostWorkingDirectories -> {
                    val containerInputFile = Path("/my-input").resolve(inputFile.fileName)
                    val mount = HostPathMount(
                        hostMount = inputFile,
                        containerMount = containerInputFile
                    )

                    MyBusinessLogicProcessor.InputFileContext(containerInputFile, mount)
                }
            }
        } else {
            null
        }
    }

    private fun createWorkingDirectories(args: ApplicationArguments): WorkingDirectories {
        return if (args.optionNames.contains("run-in-docker")) {
            val volumeResponse = DockerClientFactory.instance().client().createVolumeCmd()
                .withName("my-volume-${Uuid.random()}")
                // INFO: With this label, Volume will be removed by Ryuk
                .withLabels(DockerClientFactory.DEFAULT_LABELS + ResourceReaper.instance().labels)
                .exec()
            NamedVolumeWorkingDirectories(
                namedVolume = NamedVolume(volumeResponse.name),
                mainContainerWorkDir = createTempDirectory("my-workdir-"),
                mainContainerOutputSubDir = "container-$containerUuidKey/out-data",
                volumeOutputDir = "container-$containerUuidKey/out-data",
                mountedVolumeBaseDir = Path("/my-volume"),
            )
        } else {
            HostWorkingDirectories(
                workDir = createTempDirectory("my-temp-"),
                subDirOutput = "container-$containerUuidKey/out-data",
                mountedOutputDir = Path("/my-out"),
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DockerInceptionMountApplication::class.java)!!
    }
}

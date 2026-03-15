@file:OptIn(ExperimentalUuidApi::class)

package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import cz.publicstaticvoidmain.reproduce.dockerinceptionmount.ExecutorContainer.ConsoleOutMount
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class MyBusinessLogicProcessor(
) {
    fun process(
        containerUuid: Uuid,
        workingDirectories: WorkingDirectories,
        inputFileContext: InputFileContext?,
    ): Path {

        val containerOutputDir = createContainerOutputDir(workingDirectories, containerUuid)
            .also { logger.info("Container output directory: {}", it) }
        val outputMount = createOutputMount(workingDirectories, containerUuid, containerOutputDir)
            .also { logger.info("Output mount: {}", it) }
        val outputFileContainerPath = createOutputFileContainerPath(workingDirectories, containerOutputDir, outputMount)
            .also { logger.info("Output file container path: {}", it) }
        val consoleOutMount = createConsoleOutMount(workingDirectories, containerOutputDir, containerUuid)
            .also { logger.info("Console output mount: {}", it) }

        val command = buildList {
            if (outputFileContainerPath.parent != null) {
                val parentDirStr = outputFileContainerPath.parent.toLinuxPathStr()
                add("mkdir -p $parentDirStr &&")
            }
            add(
                buildString {
                    add("(")
                    add("echo 'output-${OffsetDateTime.now()}-end'")
                    if (inputFileContext?.containerInputFile != null) {
                        add(" && cat ${inputFileContext.containerInputFile.toLinuxPathStr()}")
                    }
                    add(")")
                }
            )
            add("> ${outputFileContainerPath.toLinuxPathStr()}")
            add("&& echo 'std-err-output-${OffsetDateTime.now()}' >&2")
        }

        try {
            ExecutorContainer(
                image = "alpine",
                command = command,
                consoleOutMount = consoleOutMount,
                mounts = listOfNotNull(outputMount, inputFileContext?.mount),
                startupTimeout = 15.seconds,
                postInit = { }
            ).initialize()
                .use { container ->
                    container.start()
                }

            val outputJsonFile = findOutputJsonFile(outputMount, workingDirectories, containerUuid, outputFileContainerPath)

            if (!outputJsonFile.exists()) {
                logger.error("Output JSON file {} does not exist.", outputJsonFile)
                when (outputMount) {
                    is VolumeMount -> {
                        val files = (workingDirectories as NamedVolumeWorkingDirectories).let {
                            it.mainContainerWorkDir
                                .resolve(it.mainContainerOutputSubDir.interpolate(containerUuid))
                                .toFile()
                        }.listFiles()
                        throw IllegalStateException(
                            "Output JSON file $outputFilename not generated, look for error. File should be at $outputJsonFile. " +
                                    "Files in in-container directory: ${files.contentToString()}",
                        )
                    }

                    is HostPathMount -> {
                        throw IllegalStateException(
                            "Output JSON file $outputFilename not generated, look for error. File should be at $outputJsonFile. " +
                                    "Files in in-host directory: ${outputMount.hostMount.toFile().listFiles().contentToString()}",
                        )
                    }
                }
            } else {
                logger.info("JSON output file: {}", outputJsonFile)
                return outputJsonFile
            }
        } catch (e: Exception) {
            try {
                val consoleOutStr = consoleOutputFromContainer(consoleOutMount, workingDirectories, containerUuid, consoleOutFilename)
                if (consoleOutStr.contains("Fatal error")) {
                    logger.error("Error from container while processing:\n{}", consoleOutStr)
                }
            } catch (nestedE: Exception) {
                e.addSuppressed(nestedE)
            }
            logger.error("Error during processing: {}", e.message)
            throw e
        }
    }

    private fun findOutputJsonFile(
        outputMount: MyMount,
        workingDirectories: WorkingDirectories,
        containerUuid: Uuid,
        outputFileContainerPath: Path
    ): Path {
        return when (outputMount) {
            is VolumeMount -> {
                extractFromVolume(outputMount) { dockerClient, containerId ->

                    val mainContainerOutputParentDir = (workingDirectories as NamedVolumeWorkingDirectories).let {
                        it.mainContainerWorkDir
                            .resolve(it.mainContainerOutputSubDir.interpolate(containerUuid))
                    }.createDirectories()

                    val mainContainerOutputFile = mainContainerOutputParentDir
                        .resolve(outputFilename)
                    dockerClient.copyFileFromContainer(
                        containerId,
                        containerFilePath = outputFileContainerPath.toLinuxPathStr(),
                        filename = outputFilename,
                        targetFile = mainContainerOutputFile.toFile(),
                        messageWhat = "Output JSON",
                        logger
                    )

                    return@extractFromVolume mainContainerOutputFile
                }
            }

            is HostPathMount -> {
                outputMount.hostMount
                    .resolve(outputFilename)
            }
        }
    }

    private fun createConsoleOutMount(
        workingDirectories: WorkingDirectories,
        containerOutputDir: Path,
        containerUuid: Uuid
    ): ConsoleOutMount {
        return when (workingDirectories) {
            is NamedVolumeWorkingDirectories -> {
                ConsoleOutMount(
                    mount = VolumeMount(
                        volumeName = workingDirectories.namedVolume.name,
                        containerMount = workingDirectories.mountedVolumeBaseDir,
                    ),
                    containerFile = containerOutputDir.resolve("console").resolve(consoleOutFilename)
                )
            }

            is HostWorkingDirectories -> {
                ConsoleOutMount(
                    mount = HostPathMount(
                        hostMount = workingDirectories.workDir
                            .resolve(workingDirectories.subDirOutput.interpolate(containerUuid))
                            .resolve("console"),
                        containerMount = workingDirectories.mountedOutputDir.resolve("console"),
                    ),
                    containerFile = workingDirectories.mountedOutputDir.resolve("console").resolve(consoleOutFilename)
                )
            }
        }
    }

    private fun createOutputFileContainerPath(
        workingDirectories: WorkingDirectories,
        containerOutputDir: Path,
        outputMount: MyMount
    ): Path {
        return when (workingDirectories) {
            is NamedVolumeWorkingDirectories -> {
                containerOutputDir.resolve("data").resolve(outputFilename)
            }

            is HostWorkingDirectories -> {
                outputMount.containerMount.resolve(outputFilename)
            }
        }
    }

    private fun createOutputMount(
        workingDirectories: WorkingDirectories,
        containerUuid: Uuid,
        containerOutputDir: Path
    ): MyMount {
        return when (workingDirectories) {
            is NamedVolumeWorkingDirectories -> VolumeMount(
                volumeName = workingDirectories.namedVolume.name,
                containerMount = workingDirectories.mountedVolumeBaseDir
            )

            is HostWorkingDirectories -> HostPathMount(
                hostMount = workingDirectories.workDir
                    .resolve(workingDirectories.subDirOutput.interpolate(containerUuid))
                    .resolve("data"),
                containerMount = containerOutputDir.resolve("data")
            )
        }
    }

    private fun createContainerOutputDir(workingDirectories: WorkingDirectories, containerUuid: Uuid): Path {
        return when (workingDirectories) {
            is NamedVolumeWorkingDirectories -> {
                workingDirectories.mountedVolumeBaseDir
                    .resolve(workingDirectories.volumeOutputDir.interpolate(containerUuid))
            }

            is HostWorkingDirectories -> {
                workingDirectories.mountedOutputDir
            }
        }
    }

    private fun consoleOutputFromContainer(
        consoleOutMount: ConsoleOutMount,
        workingDirectories: WorkingDirectories,
        containerUuid: Uuid,
        consoleOutFilename: String
    ): String {
        val consoleOutFile = when (consoleOutMount.mount) {
            is VolumeMount -> {
                extractFromVolume(consoleOutMount.mount) { dockerClient, containerId ->
                    val mainContainerOutputParentDir = (workingDirectories as NamedVolumeWorkingDirectories).let {
                        it.mainContainerWorkDir
                            .resolve(it.mainContainerOutputSubDir.interpolate(containerUuid))
                    }.createDirectories()

                    val mainContainerConsoleOutFile = mainContainerOutputParentDir
                        .resolve(consoleOutFilename)
                    dockerClient.copyFileFromContainer(
                        containerId,
                        containerFilePath = consoleOutMount.containerFile.toLinuxPathStr(),
                        filename = consoleOutFilename,
                        targetFile = mainContainerConsoleOutFile.toFile(),
                        messageWhat = "Console-out",
                        logger
                    )
                    mainContainerConsoleOutFile
                }
            }

            is HostPathMount -> {
                consoleOutMount.mount.hostMount.resolve(consoleOutFilename)
            }
        }

        return if (consoleOutFile.exists() && consoleOutFile.isReadable()) {
            Files.readString(consoleOutFile)
        } else {
            ""
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MyBusinessLogicProcessor::class.java)!!

        private const val outputFilename = "out-file.txt"
        private const val consoleOutFilename = "console-out.txt"
    }

    data class InputFileContext(
        val containerInputFile: Path,
        val mount: MyMount,
    )
}

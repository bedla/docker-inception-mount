@file:OptIn(ExperimentalUuidApi::class)

package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.SelinuxContext
import org.testcontainers.images.builder.Transferable
import java.io.File
import java.io.FileOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


fun DockerClient.copyFileToContainer(transferable: Transferable, containerPath: String, containerId: String) {
    PipedOutputStream().use { pipedOutputStream ->
        PipedInputStream(pipedOutputStream).use { pipedInputStream ->
            TarArchiveOutputStream(pipedOutputStream).use { tarArchive ->
                val thread = Thread(Runnable {
                    try {
                        tarArchive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        tarArchive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                        transferable.transferTo(tarArchive, containerPath)
                    } finally {
                        IOUtils.closeQuietly(tarArchive)
                    }
                })
                thread.start()

                this
                    .copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(pipedInputStream)
                    .withRemotePath("/")
                    .exec()
                thread.join()
            }
        }
    }
}


fun DockerClient.copyFileFromContainer(
    containerId: String,
    containerFilePath: String,
    filename: String,
    targetFile: File,
    messageWhat: String,
    logger: Logger
) {
    TarArchiveInputStream(
        this
            .copyArchiveFromContainerCmd(containerId, containerFilePath)
            .exec()
    )
        .use { tis ->
            var entry: TarArchiveEntry
            while ((tis.nextEntry.also { entry = it }) != null) {
                if (!entry.isDirectory && entry.name == filename) {
                    IOUtils.copyLarge(tis, FileOutputStream(targetFile))
                    break
                }
            }
        }.also { bytes ->
            logger.info(
                "Copy {} ({} bytes) from child-container ({}) to main-container ({})",
                messageWhat,
                bytes,
                containerFilePath,
                targetFile
            )
        }
}

fun <T> extractFromVolume(mount: VolumeMount, block: (DockerClient, String) -> T): T {
    return DockerClientFactory.instance().runInsideDocker(
        { cmd ->
            cmd.hostConfig!!.withBinds(
                Bind(
                    mount.volumeName,
                    Volume(mount.containerMount.toLinuxPathStr()),
                    AccessMode.rw,
                    SelinuxContext.SHARED.selContext,
                )
            )
        },
        block
    )
}


sealed interface WorkingDirectories

data class NamedVolumeWorkingDirectories(
    val namedVolume: NamedVolume,
    // work-dir vytvorene uvnitr main containeru
    val mainContainerWorkDir: Path,
    // kde je out-dir v main containeru, kam se kopiruje vystup z analyzy
    val mainContainerOutputSubDir: ContainerUuidString,
    // kde se ve Volume nachazi out-dir
    val volumeOutputDir: ContainerUuidString,
    // pod jakou cestou je videt Volume v containeru
    val mountedVolumeBaseDir: Path,
) : WorkingDirectories

data class HostWorkingDirectories(
    // temp work-dir
    val workDir: Path,
    // kde je out-dir na hostovi, kam se kopiruje vystup z analyzy
    val subDirOutput: ContainerUuidString,
    // kde uvnitr containeru maji byt vystupni soubory
    val mountedOutputDir: Path,
) : WorkingDirectories

data class NamedVolume(
    val name: String
)

typealias ContainerUuidString = String

fun ContainerUuidString.interpolate(containerUuid: Uuid): String {
    return this.replace(containerUuidKey, containerUuid.toString())
}

const val containerUuidKey = "<containerUuid>"

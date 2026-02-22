package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import org.slf4j.LoggerFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.SelinuxContext
import java.nio.file.Path

sealed interface MyMount {
    val containerMount: Path

    fun mount(container: GenericContainer<*>)
}

data class HostPathMount(
    val hostMount: Path,
    override val containerMount: Path,
) : MyMount {
    override fun mount(container: GenericContainer<*>) {
        container.withFileSystemBind(hostMount.toFile().canonicalPath, containerMount.toLinuxPathStr(), BindMode.READ_WRITE)
    }
}

data class VolumeMount(
    val volumeName: String,
    val accessMode: AccessMode = AccessMode.rw,
    val selinuxContext: SelinuxContext = SelinuxContext.SHARED,
    override val containerMount: Path,
) : MyMount {
    override fun mount(container: GenericContainer<*>) {
        val containerMountStr = containerMount.toLinuxPathStr()

        val keyFn = { volumeName: String, path: String -> "$volumeName###$path" }

        val index = container.binds.groupBy { keyFn(it.path, it.volume.path) }
        if (index.containsKey(keyFn(volumeName, containerMountStr))) {
            logger.trace("Binds already contains volume {} shared as {}", volumeName, containerMountStr)
        } else {
            container.binds.add(
                Bind(
                    volumeName,
                    Volume(containerMountStr),
                    accessMode,
                    selinuxContext.selContext
                )
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(VolumeMount::class.java)!!
    }
}
package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.runApplication
import org.springframework.boot.system.JavaVersion
import org.springframework.util.ClassUtils
import java.io.Reader
import java.io.StringReader
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.function.Consumer
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

class DockerInceptionMountApplicationTests {
    init {
        println("*** test begin")
        println(JavaVersion.getJavaVersion())
        println(ClassUtils.hasMethod(Reader::class.java, "readAllLines"))
        println(StringReader("xxx").readAllLines())
        println("*** test end")
    }

    @Test
    fun process(@TempDir tempDir: Path) {
        val inputFile = tempDir.resolve("input").resolve("input.txt")
            .createParentDirectories()
        val outputDir = tempDir.resolve("output")
            .createDirectories()

        inputFile.writeText("foo-${OffsetDateTime.now()}-bar")

        runApplication<DockerInceptionMountApplication>("--input-file=$inputFile", "--output-dir=$outputDir")

        val outputFile = outputDir.resolve("my-result.txt")

        assertThat(outputFile)
            .isRegularFile
            .isReadable
            .content()
            .satisfies(Consumer { content: String ->
                assertThat(content).contains("###")
                val items = content.split("###").map { it.trim() }
                assertThat(items).hasSize(2)

                val item1lines = items[0].split("\n")
                assertThat(item1lines).hasSize(2)
                assertThat(item1lines[0]).startsWith("output-")
                assertThat(item1lines[1])
                    .startsWith("foo-")
                    .endsWith("-bar")

                val item2lines = items[1].split("\n")
                assertThat(item2lines).hasSize(2)
                assertThat(item2lines[0]).startsWith("output-")
                assertThat(item2lines[1])
                    .startsWith("foo-")
                    .endsWith("-bar")

                assertThat(item2lines[0]).isNotEqualTo(item1lines[0])
            })
    }

    @Test
    fun processWithCustomOutputFilename(@TempDir tempDir: Path) {
        val outputFilename = "potato.txt"

        val inputFile = tempDir.resolve("foo-input").resolve("input-bar.txt")
            .createParentDirectories()
        val outputDir = tempDir.resolve("output-www")
            .createDirectories()

        inputFile.writeText("xxx-${OffsetDateTime.now()}-yyy")

        runApplication<DockerInceptionMountApplication>("--input-file=$inputFile", "--output-dir=$outputDir", "--output-filename=${outputFilename}")

        val outputFile = outputDir.resolve(outputFilename)

        assertThat(outputFile)
            .isRegularFile
            .isReadable
            .content()
            .contains("###")
            .contains("xxx-")
            .contains("-yyy")
    }
}

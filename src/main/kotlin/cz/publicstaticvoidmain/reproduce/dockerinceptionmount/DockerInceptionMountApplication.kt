package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DockerInceptionMountApplication

fun main(args: Array<String>) {
    runApplication<DockerInceptionMountApplication>(*args)
}

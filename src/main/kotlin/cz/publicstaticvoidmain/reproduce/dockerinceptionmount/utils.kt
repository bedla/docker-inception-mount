package cz.publicstaticvoidmain.reproduce.dockerinceptionmount

import java.nio.file.Path

fun Path.toLinuxPathStr() = this.toString().replace("\\", "/")

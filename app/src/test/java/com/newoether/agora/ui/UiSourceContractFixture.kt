package com.newoether.agora.ui

import java.io.File

internal abstract class UiSourceContractFixture {
    protected fun source(root: File, path: String): String =
        File(root, path).readText().replace("\r\n", "\n")

    protected fun sourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate source root")
    }
}

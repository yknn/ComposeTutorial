package com.example.composetutorial.serial

import java.io.File

/** 运行时枚举可用串口节点,不写死路径。 */
object SerialPortScanner {

    fun listSerialPorts(): List<String> {
        val result = sortedSetOf<String>()
        // 1. /proc/tty/drivers 里标记为 "serial" 的驱动前缀
        runCatching {
            File("/proc/tty/drivers").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                // 形如: serial  /dev/ttyS  4 64-111 serial
                if (parts.size >= 5 && parts.last() == "serial") {
                    scanDev(parts[1], result)
                }
            }
        }
        // 2. 兜底:常见节点前缀(有些板子不在 /proc/tty/drivers 里)
        listOf("/dev/ttyS", "/dev/ttyUSB", "/dev/ttyACM", "/dev/ttyHS", "/dev/ttyMT")
            .forEach { scanDev(it, result) }
        return result.toList()
    }

    private fun scanDev(prefix: String, out: MutableSet<String>) {
        val name = prefix.substringAfterLast('/')
        runCatching {
            File("/dev").listFiles { f -> f.name.startsWith(name) }
                ?.forEach { out.add(it.absolutePath) }
        }
    }
}

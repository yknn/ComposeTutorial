package com.example.composetutorial.serial

import android.os.Handler
import android.os.Looper
import android.serialport.SerialPort
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 极简串口封装:打开 / 异步读 / 写 / 关闭。
 * 读取在后台线程阻塞进行,数据通过主线程回调抛出,方便直接更新 Compose 状态。
 */
class SerialPortManager {

    private var serialPort: SerialPort? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false

    private val mainHandler = Handler(Looper.getMainLooper())

    val isOpen: Boolean get() = serialPort != null

    /**
     * 打开串口并启动读线程。
     * @param onData  收到数据(主线程回调)
     * @param onError 出错(主线程回调)
     */
    fun open(
        path: String,
        baudrate: Int,
        onData: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (isOpen) return
        try {
            val sp = SerialPort.newBuilder(File(path), baudrate).build()
            serialPort = sp
            inputStream = sp.inputStream
            outputStream = sp.outputStream
            running = true
            readThread = Thread {
                val buffer = ByteArray(1024)
                val ins = inputStream ?: return@Thread
                while (running) {
                    try {
                        val n = ins.read(buffer)
                        if (n > 0) {
                            val data = buffer.copyOf(n)
                            mainHandler.post { onData(data) }
                        }
                    } catch (e: Throwable) {
                        if (running) mainHandler.post { onError(e) }
                        break
                    }
                }
            }.apply {
                isDaemon = true
                name = "serial-read"
                start()
            }
        } catch (e: Throwable) {
            close()
            onError(e)
        }
    }

    /** 写入原始字节,返回是否成功 */
    fun write(data: ByteArray): Boolean = try {
        val out = outputStream ?: return false
        out.write(data)
        out.flush()
        true
    } catch (e: Throwable) {
        false
    }

    fun close() {
        running = false
        readThread?.interrupt()
        readThread = null
        serialPort?.tryClose()
        serialPort = null
        inputStream = null
        outputStream = null
    }
}

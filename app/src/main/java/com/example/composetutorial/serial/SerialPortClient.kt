package com.example.composetutorial.serial

import android.serialport.SerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

sealed class SerialState {
    data object Closed : SerialState()
    data object Opening : SerialState()
    data class Open(val path: String) : SerialState()
    data class Error(val message: String) : SerialState()
}

/**
 * 协程版异步串口客户端。
 *
 * - 读:独立协程在 IO 线程阻塞读 → 经 FrameDecoder 分帧 → 用 SharedFlow 抛完整帧
 * - 写:send() 把数据丢进 Channel 队列,由独立写协程串行化,调用方不阻塞
 * - 状态:StateFlow 暴露 打开中 / 已连接 / 已关闭 / 出错
 *
 * @param decoderFactory 每次 open 新建一个解码器(解码器有状态,不能跨连接复用)
 */
class SerialPortClient(private val decoderFactory: () -> FrameDecoder) {

    data class Config(
        val path: String,
        val baudRate: Int,
        val dataBits: Int = 8,
        val parity: Int = 0,
        val stopBits: Int = 1,
    )

    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val _state = MutableStateFlow<SerialState>(SerialState.Closed)
    val state: StateFlow<SerialState> = _state.asStateFlow()

    private var serialPort: SerialPort? = null
    private var scope: CoroutineScope? = null
    private var writeChannel: Channel<ByteArray>? = null

    val isOpen: Boolean get() = _state.value is SerialState.Open

    fun open(config: Config) {
        val st = _state.value
        if (st is SerialState.Open || st is SerialState.Opening) return
        _state.value = SerialState.Opening

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val newChannel = Channel<ByteArray>(capacity = Channel.BUFFERED)
        scope = newScope
        writeChannel = newChannel

        newScope.launch {
            val sp = try {
                SerialPort.newBuilder(File(config.path), config.baudRate)
                    .dataBits(config.dataBits)
                    .parity(config.parity)
                    .stopBits(config.stopBits)
                    .build()
            } catch (e: Throwable) {
                _state.value = SerialState.Error(e.message ?: e.toString())
                return@launch
            }
            serialPort = sp
            _state.value = SerialState.Open(config.path)
            launch { readLoop(sp.inputStream) }
            launch { writeLoop(sp.outputStream, newChannel) }
        }
    }

    /** 异步入队发送,非阻塞;返回是否入队成功(未打开/队列异常返回 false)。 */
    fun send(data: ByteArray): Boolean =
        writeChannel?.trySend(data)?.isSuccess == true

    fun close() {
        _state.value = SerialState.Closed   // 先置关闭,避免 readLoop 被关流唤醒时误报 Error
        serialPort?.tryClose()              // 关流 → 解除 readLoop 的阻塞 read
        writeChannel?.close()
        scope?.cancel()
        serialPort = null
        writeChannel = null
        scope = null
    }

    private suspend fun CoroutineScope.readLoop(input: InputStream) {
        val decoder = decoderFactory()
        val buf = ByteArray(2048)
        try {
            while (isActive) {
                val n = input.read(buf)
                if (n < 0) break          // EOF
                if (n == 0) continue
                for (frame in decoder.decode(buf, n)) {
                    _frames.tryEmit(frame)
                }
            }
        } catch (e: Throwable) {
            if (isActive && _state.value is SerialState.Open) {
                _state.value = SerialState.Error(e.message ?: "read error")
            }
        }
    }

    private suspend fun writeLoop(output: OutputStream, channel: Channel<ByteArray>) {
        try {
            while (true) {
                val data = channel.receiveCatching().getOrNull() ?: break
                output.write(data)
                output.flush()
            }
        } catch (e: Throwable) {
            if (_state.value is SerialState.Open) {
                _state.value = SerialState.Error(e.message ?: "write error")
            }
        }
    }
}

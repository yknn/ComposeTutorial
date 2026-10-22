package com.example.composetutorial.serial

/**
 * 帧解码器:把串口收到的"字节流"切成一个个完整的"业务帧",
 * 解决粘包(一次 read 里含多帧)和拆包(一帧被拆到多次 read)。
 *
 * 实现是有状态的(内部维护累积缓冲区,不完整数据留到下次),
 * 因此每个连接应独占一个实例 —— SerialPortClient 会在每次 open 时新建。
 */
interface FrameDecoder {
    /** 喂入本次读到的前 length 个字节,返回这次能凑齐的所有完整帧(0~N 个)。 */
    fun decode(data: ByteArray, length: Int): List<ByteArray>

    /** 连接重开时清空残留。 */
    fun reset()
}

/** 累积缓冲 + 逐帧提取的通用骨架。子类只需实现 tryReadFrame()。 */
abstract class AbstractFrameDecoder : FrameDecoder {

    private var buffer = ByteArray(0)

    final override fun decode(data: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()
        buffer += if (length == data.size) data else data.copyOf(length)
        val frames = ArrayList<ByteArray>()
        while (true) {
            val frame = tryReadFrame() ?: break
            frames.add(frame)
        }
        return frames
    }

    override fun reset() {
        buffer = ByteArray(0)
    }

    /** 尝试从缓冲区头部切一帧;不够返回 null(并按需丢弃脏数据重同步)。 */
    protected abstract fun tryReadFrame(): ByteArray?

    // —— 供子类操作缓冲区的小工具 ——
    protected fun size(): Int = buffer.size

    /** 取走前 n 字节并返回。 */
    protected fun take(n: Int): ByteArray {
        val out = buffer.copyOfRange(0, n)
        buffer = buffer.copyOfRange(n, buffer.size)
        return out
    }

    /** 丢弃前 n 字节(重同步用)。 */
    protected fun drop(n: Int) {
        buffer = buffer.copyOfRange(n.coerceIn(0, buffer.size), buffer.size)
    }

    /** 从 from 开始查找 pattern 起始下标,找不到返回 -1。 */
    protected fun indexOf(pattern: ByteArray, from: Int = 0): Int {
        if (pattern.isEmpty() || buffer.size < pattern.size) return -1
        outer@ for (i in from..buffer.size - pattern.size) {
            for (j in pattern.indices) {
                if (buffer[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** 读无符号整数(1/2/4 字节,big/little endian)。 */
    protected fun readUInt(offset: Int, len: Int, bigEndian: Boolean): Long {
        var value = 0L
        for (k in 0 until len) {
            val b = buffer[offset + k].toLong() and 0xFF
            value = if (bigEndian) (value shl 8) or b else value or (b shl (8 * k))
        }
        return value
    }
}

/**
 * 分隔符分帧:适合文本行协议(\n、\r\n)或自定义结束符(如 ETX 0x03)。
 * @param includeDelimiter 返回帧是否包含分隔符本身
 * @param maxFrameLength   超过仍无分隔符就丢弃,防脏数据撑爆内存
 */
class DelimiterFrameDecoder(
    private val delimiter: ByteArray,
    private val includeDelimiter: Boolean = false,
    private val maxFrameLength: Int = 8192,
) : AbstractFrameDecoder() {

    init {
        require(delimiter.isNotEmpty()) { "delimiter 不能为空" }
    }

    override fun tryReadFrame(): ByteArray? {
        val idx = indexOf(delimiter)
        if (idx < 0) {
            if (size() > maxFrameLength) drop(size() - (delimiter.size - 1))
            return null
        }
        val consumed = take(idx + delimiter.size)
        return if (includeDelimiter) consumed else consumed.copyOfRange(0, idx)
    }
}

/** 定长分帧:每帧固定 length 字节。 */
class FixedLengthFrameDecoder(private val length: Int) : AbstractFrameDecoder() {

    init {
        require(length > 0) { "length 必须 > 0" }
    }

    override fun tryReadFrame(): ByteArray? =
        if (size() >= length) take(length) else null
}

/**
 * 长度字段分帧:工业 / STM32 最常用。典型帧结构:
 *   [可选 header 同步头][...][长度字段][payload][可选校验/尾]
 *
 * 帧总长 = (lengthFieldOffset + lengthFieldLength) + lengthAdjustment + 长度字段值
 * (与 Netty LengthFieldBasedFrameDecoder 同一套模型)
 *
 * @param header            同步头,如 byteArrayOf(0xAA.toByte(), 0x55.toByte());空=不做头部对齐
 * @param lengthFieldOffset 长度字段相对帧首的偏移(通常 = header.size)
 * @param lengthFieldLength 长度字段字节数(1/2/4)
 * @param lengthAdjustment  长度字段"没算进去"的额外字节(如尾部 2 字节 CRC 未计入 → 填 2)
 * @param bigEndian         长度字段字节序
 * @param maxFrameLength    安全上限,超限则滑一字节重同步
 */
class LengthFieldFrameDecoder(
    private val header: ByteArray = ByteArray(0),
    private val lengthFieldOffset: Int,
    private val lengthFieldLength: Int,
    private val lengthAdjustment: Int = 0,
    private val bigEndian: Boolean = true,
    private val maxFrameLength: Int = 16384,
) : AbstractFrameDecoder() {

    init {
        require(lengthFieldLength == 1 || lengthFieldLength == 2 || lengthFieldLength == 4) {
            "lengthFieldLength 只能是 1/2/4"
        }
        require(lengthFieldOffset >= header.size) { "lengthFieldOffset 不能落在 header 之内" }
    }

    override fun tryReadFrame(): ByteArray? {
        if (header.isNotEmpty()) {
            val h = indexOf(header)
            if (h < 0) {
                if (size() >= header.size) drop(size() - (header.size - 1))
                return null
            }
            if (h > 0) drop(h)
        }
        val lengthFieldEnd = lengthFieldOffset + lengthFieldLength
        if (size() < lengthFieldEnd) return null
        val length = readUInt(lengthFieldOffset, lengthFieldLength, bigEndian)
        val frameLength = (lengthFieldEnd + lengthAdjustment + length).toInt()
        if (frameLength <= 0 || frameLength > maxFrameLength) {
            drop(1)
            return null
        }
        if (size() < frameLength) return null
        return take(frameLength)
    }
}

package com.example.composetutorial.serial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 串口调试界面:演示 SerialPortClient + FrameDecoder 的异步读写与分帧。
 *
 * 本例用换行分隔符分帧(文本行协议)。换成 STM32 二进制协议时,只需换解码器工厂,例如:
 *   SerialPortClient {
 *       LengthFieldFrameDecoder(
 *           header = byteArrayOf(0xAA.toByte(), 0x55.toByte()),
 *           lengthFieldOffset = 2,   // header(2) 之后就是长度字段
 *           lengthFieldLength = 1,   // 1 字节长度
 *           lengthAdjustment = 1,    // 尾部 1 字节校验未计入长度
 *       )
 *   }
 */
@Composable
fun SerialPortDemoScreen(modifier: Modifier = Modifier) {
    val client = remember {
        SerialPortClient { DelimiterFrameDecoder(byteArrayOf('\n'.code.toByte())) }
    }
    val state by client.state.collectAsState()
    val isOpen = state is SerialState.Open

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    var log by remember { mutableStateOf("") }
    fun appendLog(line: String) {
        log = (log + timeFmt.format(Date()) + "  " + line + "\n").takeLast(12000)
    }

    var path by remember { mutableStateOf("/dev/ttyS3") }
    var baud by remember { mutableStateOf("9600") }
    var sendText by remember { mutableStateOf("01 03 00 00 00 0A") }
    var sendAsHex by remember { mutableStateOf(true) }
    var appendNewline by remember { mutableStateOf(true) }

    LaunchedEffect(client) {
        client.frames.collect { frame ->
            appendLog("RX(" + frame.size + "): " + HexUtils.format(frame) + "  | " + HexUtils.ascii(frame))
        }
    }
    LaunchedEffect(state) {
        when (val s = state) {
            is SerialState.Open -> appendLog("— 已打开 " + s.path + " —")
            is SerialState.Error -> appendLog("ERR: " + s.message)
            else -> {}
        }
    }
    DisposableEffect(Unit) { onDispose { client.close() } }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("设备") },
                singleLine = true,
                enabled = !isOpen,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = baud,
                onValueChange = { baud = it.filter(Char::isDigit) },
                label = { Text("波特率") },
                singleLine = true,
                enabled = !isOpen,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = "状态:" + when (val s = state) {
                SerialState.Closed -> "已关闭"
                SerialState.Opening -> "打开中…"
                is SerialState.Open -> "已连接 " + s.path
                is SerialState.Error -> "错误 " + s.message
            },
            style = MaterialTheme.typography.labelMedium,
        )

        Button(
            onClick = {
                if (isOpen) client.close()
                else client.open(SerialPortClient.Config(path, baud.toIntOrNull() ?: 9600))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (isOpen) "关闭串口" else "打开串口") }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = sendText,
                onValueChange = { sendText = it },
                label = { Text(if (sendAsHex) "发送 (HEX)" else "发送 (文本)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HEX", style = MaterialTheme.typography.labelSmall)
                Switch(checked = sendAsHex, onCheckedChange = { sendAsHex = it })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = appendNewline, onCheckedChange = { appendNewline = it })
            Text(
                text = "发送追加 \\n(配合分隔符分帧)",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Button(
            onClick = {
                var bytes = if (sendAsHex) HexUtils.parse(sendText) else sendText.toByteArray()
                if (bytes == null) {
                    appendLog("ERR: HEX 格式不对")
                    return@Button
                }
                if (appendNewline) bytes += '\n'.code.toByte()
                if (client.send(bytes)) {
                    appendLog("TX(" + bytes.size + "): " + HexUtils.format(bytes))
                } else {
                    appendLog("ERR: 发送失败(未打开?)")
                }
            },
            enabled = isOpen,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("发送") }

        OutlinedButton(onClick = { log = "" }, modifier = Modifier.fillMaxWidth()) {
            Text("清空日志")
        }

        val scroll = rememberScrollState()
        LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
        Text(
            text = log.ifEmpty { "日志…" },
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

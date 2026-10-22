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
 * 最小串口调试界面:
 * - 填设备节点 + 波特率,打开/关闭
 * - HEX 或文本发送
 * - 接收数据以 HEX 追加到日志
 */
@Composable
fun SerialPortDemoScreen(modifier: Modifier = Modifier) {
    val manager = remember { SerialPortManager() }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    var path by remember { mutableStateOf("/dev/ttyS3") }
    var baud by remember { mutableStateOf("9600") }
    var sendText by remember { mutableStateOf("01 03 00 00 00 0A") }
    var sendAsHex by remember { mutableStateOf(true) }
    var isOpen by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    fun appendLog(line: String) {
        log = (log + timeFmt.format(Date()) + "  " + line + "\n").takeLast(8000)
    }

    DisposableEffect(Unit) {
        onDispose { manager.close() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
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

        Button(
            onClick = {
                if (isOpen) {
                    manager.close()
                    isOpen = false
                    appendLog("— 已关闭 —")
                } else {
                    manager.open(
                        path = path,
                        baudrate = baud.toIntOrNull() ?: 9600,
                        onData = { data -> appendLog("RX: " + HexUtils.format(data)) },
                        onError = { e ->
                            appendLog("ERR: " + e.message)
                            isOpen = false
                        },
                    )
                    isOpen = manager.isOpen
                    if (isOpen) appendLog("— 已打开 " + path + " @ " + baud + " —")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isOpen) "关闭串口" else "打开串口")
        }

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

        Button(
            onClick = {
                val bytes = if (sendAsHex) HexUtils.parse(sendText) else sendText.toByteArray()
                when {
                    bytes == null -> appendLog("ERR: HEX 格式不对")
                    manager.write(bytes) ->
                        appendLog("TX: " + if (sendAsHex) HexUtils.format(bytes) else sendText)
                    else -> appendLog("ERR: 发送失败(串口未打开?)")
                }
            },
            enabled = isOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("发送")
        }

        OutlinedButton(onClick = { log = "" }, modifier = Modifier.fillMaxWidth()) {
            Text("清空日志")
        }

        val scroll = rememberScrollState()
        LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
        Text(
            text = log.ifEmpty { "日志…" },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

package com.pianocanvas.midi.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A device that can be used as a MIDI source. Only devices with an output port
 * (a keyboard or BLE MIDI adapter that produces MIDI) are offered: those are the
 * ones the app can record from.
 */
data class MidiDeviceOption(
    val id: Int,
    val name: String,
    /** True when the device exposes an output port the app can receive from. */
    val canReceive: Boolean,
    /** True when the device exposes an input port the app can send to. */
    val canSend: Boolean,
)

sealed interface MidiInputEvent {
    data class NoteOn(val pitch: Int, val velocity: Int, val channel: Int) : MidiInputEvent
    data class NoteOff(val pitch: Int, val channel: Int) : MidiInputEvent
    data class Sustain(val on: Boolean, val channel: Int) : MidiInputEvent
    data class ControlChange(val controller: Int, val value: Int, val channel: Int) : MidiInputEvent
    data class PitchBend(val value: Int, val channel: Int) : MidiInputEvent
}

/**
 * Thin wrapper around [android.media.midi.MidiManager]. It enumerates USB and
 * BLE MIDI devices, opens the first usable one automatically, forwards parsed
 * messages as [MidiInputEvent] and can send an all-notes-off back to the device.
 *
 * The Android MIDI API covers both USB and Bluetooth LE MIDI (the system routes
 * BLE MIDI devices into the same service), so no Bluetooth permission is needed.
 */
class MidiInputManager(context: Context) {

    private val appContext = context.applicationContext
    private val manager: MidiManager? =
        appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())

    val supported: Boolean get() = manager != null

    private val _devices = MutableStateFlow<List<MidiDeviceOption>>(emptyList())
    val devices: StateFlow<List<MidiDeviceOption>> = _devices.asStateFlow()

    private val _connectedDeviceId = MutableStateFlow<Int?>(null)
    val connectedDeviceId: StateFlow<Int?> = _connectedDeviceId.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _events = MutableSharedFlow<MidiInputEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<MidiInputEvent> = _events.asSharedFlow()

    private var inputPort: MidiInputPort? = null
    private var outputPort: MidiOutputPort? = null
    private var device: MidiDevice? = null
    private var started = false

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            refreshDevices()
            if (_connectedDeviceId.value == null) {
                _devices.value.firstOrNull { it.canReceive }?.let { connect(it.id) }
            }
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (device.id == _connectedDeviceId.value) disconnect()
            refreshDevices()
        }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(message: ByteArray, offset: Int, count: Int, timestamp: Long) {
            runCatching { processMessage(message, offset, count) }
        }
    }

    fun start() {
        val m = manager ?: return
        if (started) return
        started = true
        runCatching { m.registerDeviceCallback(deviceCallback, handler) }
        refreshDevices()
        _devices.value.firstOrNull { it.canReceive }?.let { connect(it.id) }
    }

    fun refreshDevices() {
        val m = manager ?: return
        _devices.value = m.devices
            .map { info ->
                MidiDeviceOption(
                    id = info.id,
                    name = deviceName(info),
                    canReceive = info.outputPortCount > 0,
                    canSend = info.inputPortCount > 0,
                )
            }
            .sortedWith(compareByDescending<MidiDeviceOption> { it.canReceive }.thenBy { it.name })
    }

    /** Pass null to disconnect the current device. */
    fun connect(id: Int?) {
        val m = manager ?: return
        if (id == null) {
            disconnect()
            return
        }
        if (_connectedDeviceId.value == id && device != null) return
        disconnect()
        val info = m.devices.firstOrNull { it.id == id } ?: return
        runCatching {
            m.openDevice(
                info,
                { opened ->
                    if (opened == null) return@openDevice
                    openPorts(opened, info)
                },
                handler,
            )
        }.onFailure {
            _connectedDeviceId.value = null
            _connectedDeviceName.value = null
        }
    }

    private fun openPorts(opened: MidiDevice, info: MidiDeviceInfo) {
        val output = runCatching {
            if (info.outputPortCount > 0) opened.openOutputPort(0) else null
        }.getOrNull()
        val input = runCatching {
            if (info.inputPortCount > 0) opened.openInputPort(0) else null
        }.getOrNull()
        if (output == null && input == null) {
            runCatching { opened.close() }
            return
        }
        output?.connect(receiver)
        device = opened
        outputPort = output
        inputPort = input
        _connectedDeviceId.value = info.id
        _connectedDeviceName.value = deviceName(info)
    }

    fun disconnect() {
        runCatching { outputPort?.close() }
        runCatching { inputPort?.close() }
        runCatching { device?.close() }
        outputPort = null
        inputPort = null
        device = null
        _connectedDeviceId.value = null
        _connectedDeviceName.value = null
    }

    /** Best-effort all-notes-off / sustain-off back to the connected device. */
    fun sendAllNotesOff() {
        val port = inputPort ?: return
        val channel = 0
        runCatching {
            port.send(byteArrayOf((0xB0 or channel).toByte(), 64, 0), 0, 3)
            port.send(byteArrayOf((0xB0 or channel).toByte(), 123, 0), 0, 3)
            port.send(byteArrayOf((0xB0 or channel).toByte(), 120, 0), 0, 3)
        }
    }

    fun shutdown() {
        if (started) {
            runCatching { manager?.unregisterDeviceCallback(deviceCallback) }
            started = false
        }
        sendAllNotesOff()
        disconnect()
        _devices.value = emptyList()
    }

    private fun processMessage(message: ByteArray, offset: Int, count: Int) {
        val end = (offset + count).coerceAtMost(message.size)
        var i = offset
        while (i < end) {
            val status = message[i].toInt() and 0xFF
            if (status < 0x80) {
                i++
                continue
            }
            val high = status and 0xF0
            val channel = status and 0x0F
            when (high) {
                0x80, 0x90 -> {
                    if (i + 2 >= end) return
                    val pitch = message[i + 1].toInt() and 0x7F
                    val velocity = message[i + 2].toInt() and 0x7F
                    if (high == 0x90 && velocity > 0) {
                        _events.tryEmit(MidiInputEvent.NoteOn(pitch, velocity, channel))
                    } else {
                        _events.tryEmit(MidiInputEvent.NoteOff(pitch, channel))
                    }
                    i += 3
                }
                0xA0 -> {
                    if (i + 2 >= end) return
                    i += 3
                }
                0xB0 -> {
                    if (i + 2 >= end) return
                    val controller = message[i + 1].toInt() and 0x7F
                    val value = message[i + 2].toInt() and 0x7F
                    if (controller == 64) {
                        _events.tryEmit(MidiInputEvent.Sustain(value >= 64, channel))
                    } else {
                        _events.tryEmit(MidiInputEvent.ControlChange(controller, value, channel))
                    }
                    i += 3
                }
                0xC0, 0xD0 -> i += 2
                0xE0 -> {
                    if (i + 2 >= end) return
                    val lsb = message[i + 1].toInt() and 0x7F
                    val msb = message[i + 2].toInt() and 0x7F
                    _events.tryEmit(MidiInputEvent.PitchBend((msb shl 7) or lsb, channel))
                    i += 3
                }
                else -> i += 1
            }
        }
    }

    private fun deviceName(info: MidiDeviceInfo): String {
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        if (!name.isNullOrBlank()) return name
        val product = info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        val manufacturer = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        val combined = listOfNotNull(manufacturer, product)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (combined.isNotBlank()) return combined
        return if (info.type == MidiDeviceInfo.TYPE_BLUETOOTH) "Bluetooth MIDI" else "MIDIデバイス"
    }
}

package com.mazecrypto.app.hw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A Ledger Nano X, Stax or Flex over Bluetooth LE. The pairing itself is
 * Android's (the code is shown on the Ledger); after that, APDUs go out on the
 * write characteristic and come back as notifications, framed like USB minus
 * the channel id, in chunks of the MTU the device reports.
 *
 * The caller holds BLUETOOTH_SCAN / BLUETOOTH_CONNECT (Android 12+) or location
 * (older) before using anything here.
 */
@SuppressLint("MissingPermission")
class LedgerBle private constructor(private val gatt: BluetoothGatt, private val cb: Callback) : Transport {
    override val kind = "ble"
    private val lock = Mutex()
    private var mtu = 20

    /** A known Ledger BLE model: its GATT service and the notify / write characteristics. */
    class Model(val name: String, val service: UUID, val notify: UUID, val write: UUID)

    private class Callback : BluetoothGattCallback() {
        val connected = CompletableDeferred<Boolean>()
        val services = CompletableDeferred<Boolean>()
        val mtuDone = CompletableDeferred<Int>()
        var written = CompletableDeferred<Boolean>()
        var descWritten = CompletableDeferred<Boolean>()
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile var closed = false

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) connected.complete(true)
            else {
                closed = true
                connected.complete(false)
                services.complete(false)
                mtuDone.complete(0)
                written.complete(false)
                descWritten.complete(false)
                incoming.close()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { services.complete(status == BluetoothGatt.GATT_SUCCESS) }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { mtuDone.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            written.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value.copyOf())
        }
        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { incoming.trySend(it.copyOf()) }
        }
    }

    private lateinit var writeChar: BluetoothGattCharacteristic

    private suspend fun write(value: ByteArray) {
        cb.written = CompletableDeferred()
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(writeChar, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeChar.value = value
                gatt.writeCharacteristic(writeChar)
            }
        }
        if (!ok) throw LedgerError("disconnected")
        if (withTimeoutOrNull(5_000) { cb.written.await() } != true) throw LedgerError("disconnected")
    }

    private suspend fun setup(model: Model) {
        val svc = gatt.getService(model.service) ?: throw LedgerError("no_device")
        writeChar = svc.getCharacteristic(model.write) ?: throw LedgerError("no_device")
        val notify = svc.getCharacteristic(model.notify) ?: throw LedgerError("no_device")
        gatt.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD) ?: throw LedgerError("no_device")
        cb.descWritten = CompletableDeferred()
        val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, on) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { cccd.value = on; gatt.writeDescriptor(cccd) }
        if (!started) throw LedgerError("disconnected")
        // This is the first encrypted operation: Android shows the pairing request here, so wait for the user.
        if (withTimeoutOrNull(60_000) { cb.descWritten.await() } != true) throw LedgerError("pairing")
        // Ask the device how large a frame may be.
        write(byteArrayOf(0x08, 0, 0, 0, 0))
        val r = withTimeoutOrNull(5_000) { cb.incoming.receive() }
        mtu = if (r != null && r.isNotEmpty() && r[0].toInt() == 0x08 && r.size >= 6) (r[5].toInt() and 0xff).coerceAtLeast(20) else 20
    }

    override suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray = lock.withLock {
        if (cb.closed) throw LedgerError("disconnected")
        while (cb.incoming.tryReceive().isSuccess) Unit           // nothing stale from a previous command
        for (p in Framing.packets(apdu, mtu, null)) write(p)
        val reader = Framing.Reader(null)
        withTimeoutOrNull(timeoutMs) {
            var result: ByteArray? = null
            while (result == null) {
                val pkt = cb.incoming.receiveCatching().getOrNull() ?: throw LedgerError("disconnected")
                result = reader.feed(pkt)
            }
            result
        } ?: throw LedgerError("timeout")
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private fun ledgerUuid(model: String, part: String) = UUID.fromString("13d63400-2c97-${model}-$part-4c6564676572")
        val MODELS = listOf(
            Model("Nano X", ledgerUuid("0004", "0000"), ledgerUuid("0004", "0001"), ledgerUuid("0004", "0002")),
            Model("Stax", ledgerUuid("6004", "0000"), ledgerUuid("6004", "0001"), ledgerUuid("6004", "0002")),
            Model("Flex", ledgerUuid("3004", "0000"), ledgerUuid("3004", "0001"), ledgerUuid("3004", "0002")),
        )

        class Found(val device: BluetoothDevice, val name: String, val rssi: Int)

        /** Nearby Ledgers advertising one of the known services, as they are seen. */
        fun scan(ctx: Context): Flow<Found> = callbackFlow {
            val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            if (scanner == null) {
                close(LedgerError("bluetooth_off"))
                return@callbackFlow
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    trySend(Found(r.device, r.scanRecord?.deviceName ?: r.device.name ?: "Ledger", r.rssi))
                }
                override fun onScanFailed(errorCode: Int) { close(LedgerError("scan")) }
            }
            val filters = MODELS.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it.service)).build() }
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            awaitClose { runCatching { scanner.stopScan(callback) } }
        }

        /**
         * Ledgers already paired with this phone. A Ledger that is connected (to this phone
         * or another) stops advertising, so a scan alone would not find it again.
         */
        fun bonded(ctx: Context): List<Found> = runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { d -> val n = d.name.orEmpty(); listOf("Nano X", "Stax", "Flex", "Ledger").any { n.contains(it, ignoreCase = true) } }
                .map { Found(it, it.name ?: "Ledger", 0) }
        }.getOrDefault(emptyList())

        /** Connect, retrying: Android's first GATT connection to a device often fails (status 133). */
        suspend fun connect(ctx: Context, device: BluetoothDevice): LedgerBle {
            var last: Throwable? = null
            repeat(3) { attempt ->
                try {
                    return connectOnce(ctx, device)
                } catch (e: LedgerError) {
                    if (e.code == "pairing") throw e
                    last = e
                    kotlinx.coroutines.delay(1_200L * (attempt + 1))
                }
            }
            throw last ?: LedgerError("disconnected")
        }

        private suspend fun connectOnce(ctx: Context, device: BluetoothDevice): LedgerBle {
            val cb = Callback()
            val gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: throw LedgerError("disconnected")
            try {
                if (withTimeoutOrNull(20_000) { cb.connected.await() } != true) throw LedgerError("disconnected")
                gatt.requestMtu(156)
                withTimeoutOrNull(5_000) { cb.mtuDone.await() }
                if (!gatt.discoverServices() || withTimeoutOrNull(10_000) { cb.services.await() } != true) throw LedgerError("disconnected")
                val model = MODELS.firstOrNull { gatt.getService(it.service) != null } ?: throw LedgerError("no_device")
                return LedgerBle(gatt, cb).also { withTimeout(90_000) { it.setup(model) } }
            } catch (e: Throwable) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                throw if (e is kotlinx.coroutines.TimeoutCancellationException) LedgerError("timeout") else e
            }
        }
    }
}

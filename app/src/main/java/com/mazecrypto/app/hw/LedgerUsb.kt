package com.mazecrypto.app.hw

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * A Ledger on the phone's USB port (USB-C or an OTG adapter). The device's
 * generic HID interface is driven directly through Android's USB host API:
 * 64-byte interrupt transfers on channel 0x0101, no driver, no library.
 */
class LedgerUsb private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : Transport {
    override val kind = "usb"
    private val lock = Mutex()

    override suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray = lock.withLock {
        withContext(Dispatchers.IO) {
            for (p in Framing.packets(apdu, PACKET, CHANNEL)) {
                if (conn.bulkTransfer(epOut, p, p.size, 2_000) != p.size) throw LedgerError("disconnected")
            }
            val reader = Framing.Reader(CHANNEL)
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(PACKET)
            var result: ByteArray? = null
            while (result == null) {
                if (System.currentTimeMillis() > deadline) throw LedgerError("timeout")
                // Short reads in a loop: the user may take a while to confirm on the device.
                val n = conn.bulkTransfer(epIn, buf, buf.size, 500)
                if (n > 0) result = reader.feed(buf.copyOf(n))
            }
            result
        }
    }

    override fun close() {
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }

    companion object {
        const val VENDOR = 0x2c97
        private const val CHANNEL = 0x0101
        private const val PACKET = 64
        private const val ACTION = "com.mazecrypto.app.USB_PERMISSION"

        fun find(ctx: Context): UsbDevice? =
            ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { it.vendorId == VENDOR }

        /** Ask Android for access to [device]; true when granted (at once if it already was). */
        suspend fun permission(ctx: Context, device: UsbDevice): Boolean {
            val um = ctx.getSystemService(UsbManager::class.java) ?: return false
            if (um.hasPermission(device)) return true
            return suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.action != ACTION) return
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || um.hasPermission(device))
                    }
                }
                ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                // The system fills in the extras, so the intent must be mutable — and explicit, for Android 14.
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION).setPackage(ctx.packageName), flags or PendingIntent.FLAG_UPDATE_CURRENT)
                um.requestPermission(device, pi)
            }
        }

        fun open(ctx: Context, device: UsbDevice): LedgerUsb {
            val um = ctx.getSystemService(UsbManager::class.java) ?: throw LedgerError("no_device")
            // The generic HID interface (usage page 0xFFA0) is interface 0; FIDO/U2F and WebUSB come after it.
            val ifaces = (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                .sortedBy { it.id }
            for (iface in ifaces) {
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
                if (epIn == null || epOut == null) continue
                val conn = um.openDevice(device) ?: throw LedgerError("permission")
                if (!conn.claimInterface(iface, true)) {
                    conn.close()
                    continue
                }
                return LedgerUsb(conn, iface, epIn, epOut)
            }
            throw LedgerError("no_device")
        }
    }
}

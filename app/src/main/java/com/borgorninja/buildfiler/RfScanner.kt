package com.borgorninja.buildfiler

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager

data class RfDevice(
    val name: String,
    val type: String,
    val address: String,
    val rssi: Int
)

class RfScanner(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothAdapter: BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    fun scanNearby(): List<RfDevice> {
        val list = mutableListOf<RfDevice>()

        // 1. Current connected wifi link
        try {
            val info = wifiManager?.connectionInfo
            if (info != null && !info.ssid.isNullOrBlank() && info.ssid != "<unknown ssid>") {
                val ssid = info.ssid.replace("\"", "")
                list.add(
                    RfDevice(
                        name = "$ssid (Active Uplink)",
                        type = "WIFI UPLINK",
                        address = info.bssid ?: "00:00:00:00:00:00",
                        rssi = info.rssi
                    )
                )
            }
        } catch (_: Exception) {}

        // 2. Wifi scan results if accessible
        try {
            val scanResults = wifiManager?.scanResults ?: emptyList()
            for (w in scanResults) {
                val ssid = if (w.SSID.isNullOrBlank()) "Hidden AP" else w.SSID
                list.add(
                    RfDevice(
                        name = ssid,
                        type = "WIFI 802.11",
                        address = w.BSSID ?: "Unknown",
                        rssi = w.level
                    )
                )
            }
        } catch (_: Exception) {}

        // 3. Bluetooth devices / beacons
        try {
            val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
            for (d in bonded) {
                val name = d.name ?: "Wireless Beacon"
                list.add(
                    RfDevice(
                        name = name,
                        type = "BT / BLE NODE",
                        address = d.address,
                        rssi = -65
                    )
                )
            }
        } catch (_: Exception) {}

        return list.distinctBy { it.address }.sortedByDescending { it.rssi }.take(6)
    }
}

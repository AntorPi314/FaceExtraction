package com.antor.face.extraction.utils

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Device এর current WiFi IP address automatically detect করে
     */
    fun getWifiIpAddress(context: Context): String {
        try {
            // Primary method: WifiManager
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: NetworkInterface scan
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address: InetAddress = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                        val ip = address.hostAddress ?: continue
                        if (ip.startsWith("192.168") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return "127.0.0.1"
    }

    fun getServerUrl(context: Context, port: Int): String {
        val ip = getWifiIpAddress(context)
        return "http://$ip:$port"
    }
}

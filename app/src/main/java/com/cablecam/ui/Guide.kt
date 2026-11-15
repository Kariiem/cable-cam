package com.cablecam.ui

import com.cablecam.net.Links
import com.cablecam.settings.Mode

/** The texts of the in-app guide. Keep in sync with README.md and PLAN.md ("OBS setup"). */
object Guide {
    fun connect(mode: Mode): String = when (mode) {
        Mode.CABLE ->
            "1. Plug the phone into the PC with a USB cable.\n" +
                "2. Phone: turn on USB tethering (button below, or Settings > Mobile network > Hotspot & tethering).\n" +
                "3. Tap Start. The address of the USB link appears above.\n\n" +
                "Windows 10/11 sets up the USB network by itself; nothing to install. " +
                "Turn mobile data off if you do not want the PC to use it."
        Mode.WIRELESS ->
            "Wi-Fi: phone and PC on the same network, or the PC connected to the phone's hotspot.\n" +
                "Bluetooth: pair the phone, turn on Bluetooth tethering on the phone, then on Windows open the " +
                "Bluetooth device and choose \"Connect using > Access point\". Bluetooth is slow: the app lowers the " +
                "bitrate by itself (about 1.2 Mbit/s), so expect a soft picture.\n" +
                "Tap Start, then use the address that matches how the PC is connected."
    }

    val adbAlternative =
        "If USB tethering is blocked or the address keeps changing, use adb on the PC (USB debugging on):\n" +
            "  ${Links.ADB_COMMAND}\nand use ${Links.ADB_URL} in OBS."

    val obs =
        "In OBS:\n" +
            "1. Sources > + > Media Source. Uncheck \"Local File\".\n" +
            "2. Input: the address from this app, like http://192.168.42.129:8765/cam.ts. Input Format: leave empty.\n" +
            "3. Network Buffering: 0 MB. Reconnect Delay: 1 s. Hardware decoding: on. " +
            "\"Restart playback when source becomes active\": off. If your OBS has an FFmpeg Options field, " +
            "try: fflags=nobuffer flags=low_delay\n" +
            "4. For Teams, Zoom or Meet: Start Virtual Camera in OBS, then pick \"OBS Virtual Camera\" in the app.\n\n" +
            "You can check the address in a browser first: it shows a status page.\n" +
            "Nothing is installed on the PC and no firewall rule is needed: OBS connects to the phone."

    val keepAlive =
        "The phone keeps the screen on while streaming; use Dim screen to save power. " +
            "If the stream stops when the screen is off or the app is in the background, exempt Cable Cam from " +
            "battery optimisation (Settings > Battery > App launch / Battery optimisation)."
}

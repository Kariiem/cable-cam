package com.cablecam.ui

import com.cablecam.net.Links
import com.cablecam.settings.Mode

/** The texts of the in-app guide. Keep in sync with README.md and PLAN.md ("OBS setup"). */
object Guide {
    fun connect(mode: Mode): String = when (mode) {
        Mode.CABLE ->
            "1. Plug the phone into the PC with a USB cable.\n" +
                "2. Phone: turn on USB tethering: Settings > Mobile network > Personal hotspot > USB tethering (the button opens the Mobile network page).\n" +
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
        "Optional, only if USB tethering is blocked or its address keeps changing. Needs adb (Android platform-tools) " +
            "on the PC and USB debugging on the phone, with the cable plugged in. On the PC run:\n" +
            "  ${Links.ADB_COMMAND}\nand use ${Links.ADB_URL} in OBS. Nothing else in the app needs adb."

    val obs =
        "In OBS:\n" +
            "1. Sources > + > Media Source. Uncheck \"Local File\".\n" +
            "2. Input: an address from this app. The ending picks the format: /cam.ts is H.264 (light on bandwidth), /cam.mjpg is Motion JPEG " +
            "(no keyframes, recovers from stalls at once, about 4x the bandwidth). Neither is the default: use the one you type. Input Format: leave empty.\n" +
            "3. Network Buffering: 0 MB (the default is 2 MB, which adds about 2 seconds of delay). Reconnect Delay: 1 s. Hardware decoding: on. " +
            "\"Restart playback when source becomes active\": off. In the FFmpeg Options field (needed for low delay) enter: fflags=nobuffer flags=low_delay (for /cam.ts also probesize=32768)\n" +
            "4. For Teams, Zoom or Meet: Start Virtual Camera in OBS, then pick \"OBS Virtual Camera\" in the app.\n\n" +
            "You can check the address in a browser first: it shows a status page.\n" +
            "Nothing is installed on the PC and no firewall rule is needed: OBS connects to the phone."

    val vpn =
        "If OBS cannot connect although the phone and PC are on the same network, check for a VPN on the phone " +
            "(DNS or ad blockers such as RethinkDNS run as one): while it is on, connections from the PC are blocked. " +
            "Pause it while streaming. Verified on the HONOR X9c: the stream was unreachable from the PC until the VPN was stopped."

    val wifi =
        "If your router has a 5 GHz network, use it on both the phone and the PC (often a separate name, for example ending in 5G). " +
            "Many ISP routers are 2.4 GHz only (the ZTE H168N is), and then the USB cable is the way to get a smooth picture. " +
            "On 2.4 GHz (about 72 Mbit/s here) the video crosses a crowded radio twice and arrives in bursts: " +
            "measured median jitter 26 ms and worst case 357 ms, with stalls up to 1.7 s before tuning. The USB cable avoids Wi-Fi altogether.\n" +
            "The app warns when the phone is on 2.4 GHz."

    val keepAlive =
        "The phone keeps the screen on while streaming; use Dim screen to save power. " +
            "If the stream stops when the screen is off or the app is in the background, exempt Cable Cam from " +
            "battery optimisation (Settings > Battery > App launch / Battery optimisation)."
}

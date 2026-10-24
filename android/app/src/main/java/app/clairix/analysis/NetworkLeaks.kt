package app.clairix.analysis

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.provider.Settings
import app.clairix.HealthFact
import app.clairix.Severity
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Whether a VPN, when one is up, actually carries everything: the lookups, both address
 * families, and this app. A VPN that leaves DNS with the router or the carrier tells
 * them every site visited; one that carries IPv4 while IPv6 goes round it hands the real
 * address to every IPv6 site; a proxy on the network sees every plain-HTTP request.
 *
 * [read] takes the picture from the platform; [facts] judges it and is plain Kotlin.
 */
object NetworkLeaks {

    data class Picture(
        val vpnUp: Boolean,
        /** Whether this app's own default network is the VPN; false means it is excluded from it. */
        val appOnVpn: Boolean,
        val vpnDns: List<String> = emptyList(),
        val underlyingDns: List<String> = emptyList(),
        val underlyingGateways: List<String> = emptyList(),
        val vpnRoutesV4: Boolean = false,
        val vpnRoutesV6: Boolean = false,
        val underlyingHasGlobalV6: Boolean = false,
        val proxy: String? = null,
        val privateDnsActive: Boolean = false,
        val privateDnsHost: String? = null,
        /** "Block connections without VPN": true, false, or null when Android does not say. */
        val lockdown: Boolean? = null,
        val underlyingName: String? = null
    )

    fun facts(p: Picture): List<HealthFact> {
        val out = ArrayList<HealthFact>(5)
        if (p.proxy != null) out += HealthFact(
            "net_proxy", "Network", "Proxy", p.proxy, Severity.MEDIUM,
            "This network sends web traffic through a proxy at ${p.proxy}. It sees every unencrypted request and the name of " +
                "every encrypted site. On a network you did not set up yourself, that is someone watching.",
            fixAction = Settings.ACTION_WIFI_SETTINGS
        )
        if (!p.vpnUp) return out

        if (!p.appOnVpn) out += HealthFact(
            "vpn_excluded", "Network", "VPN coverage", "Clairix is outside the VPN", Severity.MEDIUM,
            "A VPN is up but this app's traffic does not go through it, so the VPN is set to cover only some apps. Others may be " +
                "left out the same way. Check the VPN app's split-tunnelling or allowed-apps list."
        )

        // DNS: the VPN's own resolvers, Private DNS (encrypted, so its server choice is the VPN's business),
        // or the local network's, which is the leak.
        val dnsLeak = !p.privateDnsActive && (p.vpnDns.isEmpty() ||
            p.vpnDns.any { it in p.underlyingDns || it in p.underlyingGateways })
        out += if (dnsLeak) HealthFact(
            "vpn_dns", "Network", "DNS with VPN", "LEAKING to ${p.underlyingName ?: "the local network"}", Severity.HIGH,
            "The VPN is up but lookups go to " + (if (p.vpnDns.isEmpty()) "the network's own resolver" else p.vpnDns.joinToString()) +
                ", which is " + (p.underlyingName ?: "the Wi-Fi or mobile network") + "'s. Whoever runs it sees every site you visit, " +
                "VPN or not. Turn on the VPN app's DNS protection, or set Private DNS to a provider you choose.",
            fixAction = Settings.ACTION_WIRELESS_SETTINGS
        ) else HealthFact(
            "vpn_dns", "Network", "DNS with VPN",
            if (p.privateDnsActive) "Encrypted (Private DNS${p.privateDnsHost?.let { ": $it" } ?: ""})" else "Through the VPN (${p.vpnDns.joinToString()})"
        )

        out += when {
            p.vpnRoutesV6 -> HealthFact("vpn_v6", "Network", "IPv6 with VPN", "Through the VPN")
            p.underlyingHasGlobalV6 && !p.vpnRoutesV6 && p.vpnRoutesV4 -> HealthFact(
                "vpn_v6", "Network", "IPv6 with VPN", "Not carried", Severity.LOW,
                "The VPN carries IPv4 only. Android blocks IPv6 for a VPN that declares none, so this is usually safe, but a VPN " +
                    "app that allows IPv6 without routing it leaks your real address to every IPv6 site. If the VPN offers IPv6 " +
                    "support or IPv6 leak protection, turn it on."
            )
            else -> HealthFact("vpn_v6", "Network", "IPv6 with VPN", "No IPv6 on this network")
        }

        out += when (p.lockdown) {
            true -> HealthFact("vpn_lockdown", "Network", "Block without VPN", "On")
            false -> HealthFact(
                "vpn_lockdown", "Network", "Block without VPN", "Off", Severity.MEDIUM,
                "If the VPN drops, apps carry on over the open network until it reconnects, and some reconnect before the VPN " +
                    "does. Settings → Network & internet → VPN → (the VPN) → Always-on VPN and Block connections without VPN.",
                fixAction = Settings.ACTION_VPN_SETTINGS
            )
            null -> HealthFact(
                "vpn_lockdown", "Network", "Block without VPN", "Not readable",
                detail = "Android does not tell apps whether this is on. Check Settings → Network & internet → VPN → (the VPN) → " +
                    "Block connections without VPN."
            )
        }
        return out
    }

    /** The picture from the platform. Needs ACCESS_NETWORK_STATE only. */
    fun read(context: Context): Picture {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Picture(vpnUp = false, appOnVpn = false)
        val all = runCatching { @Suppress("DEPRECATION") cm.allNetworks.toList() }.getOrDefault(emptyList())
        fun caps(n: android.net.Network) = runCatching { cm.getNetworkCapabilities(n) }.getOrNull()
        fun link(n: android.net.Network): LinkProperties? = runCatching { cm.getLinkProperties(n) }.getOrNull()
        val vpn = all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val underlying = all.firstOrNull { n ->
            val c = caps(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val active = cm.activeNetwork
        val activeLink = active?.let { link(it) }
        val uLink = underlying?.let { link(it) }
        val vLink = vpn?.let { link(it) }
        val uCaps = underlying?.let { caps(it) }
        val proxy = (activeLink ?: uLink)?.httpProxy?.let { "${it.host}:${it.port}" }?.takeIf { !it.startsWith("null") }
        val lockdown = runCatching { Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown") == 1 }.getOrNull()
        return Picture(
            vpnUp = vpn != null,
            appOnVpn = vpn != null && active != null && caps(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            vpnDns = vLink?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') } ?: emptyList(),
            underlyingDns = uLink?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') } ?: emptyList(),
            underlyingGateways = uLink?.routes?.mapNotNull { it.gateway?.takeIf { g -> !g.isAnyLocalAddress }?.hostAddress?.substringBefore('%') } ?: emptyList(),
            vpnRoutesV4 = vLink?.routes?.any { it.destination.address is Inet4Address } == true,
            vpnRoutesV6 = vLink?.routes?.any { it.destination.address is Inet6Address } == true ||
                vLink?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true,
            underlyingHasGlobalV6 = uLink?.linkAddresses?.any { a ->
                val ad = a.address
                ad is Inet6Address && !ad.isLinkLocalAddress && !ad.isSiteLocalAddress && !ad.isLoopbackAddress
            } == true,
            proxy = proxy,
            privateDnsActive = (activeLink ?: uLink)?.isPrivateDnsActive == true,
            privateDnsHost = (activeLink ?: uLink)?.privateDnsServerName,
            lockdown = lockdown,
            underlyingName = when {
                uCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "the Wi-Fi network"
                uCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "the mobile carrier"
                else -> null
            }
        )
    }
}

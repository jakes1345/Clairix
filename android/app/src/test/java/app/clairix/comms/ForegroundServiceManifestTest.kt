package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The foreground-service declarations Google Play reviews, read from the source manifests the
 * merger folds together: the relay connection is a special-use service with its stated reason,
 * and every type a service declares has its FOREGROUND_SERVICE_* permission in the same APK
 * (Android 14 refuses startForeground otherwise).
 */
class ForegroundServiceManifestTest {

    private val main = manifest("src/main/AndroidManifest.xml")
    private val suite = manifest("src/suite/AndroidManifest.xml")

    @Test
    fun theRelayConnectionIsASpecialUseServiceWithItsReason() {
        val comms = services(main).single { it.androidAttr("name") == ".comms.CommsService" }
        assertEquals("specialUse", comms.androidAttr("foregroundServiceType"))
        val subtype = children(comms, "property").single { it.androidAttr("name") == SUBTYPE_PROPERTY }
        assertEquals(
            "Keeps the end-to-end-encrypted relay connection open so incoming calls and messages " +
                "arrive without a Google push service",
            subtype.androidAttr("value"),
        )
    }

    @Test
    fun theRemoteMessagingTypeIsGoneEverywhere() {
        for (doc in listOf(main, suite)) {
            assertFalse(permissions(doc).contains("android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING"))
            assertTrue(services(doc).none { "remoteMessaging" in types(it) })
        }
        assertTrue(permissions(main).contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        // The service asks Android for the same type the manifest declares.
        val source = File("src/main/java/app/clairix/comms/CommsService.kt").readText()
        assertTrue(source.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        assertFalse(source.contains("FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING"))
    }

    @Test
    fun everyDeclaredTypeHasItsPermissionInTheSameApk() {
        // The comms-only APK is src/main alone; the suite APKs are src/main plus src/suite.
        assertEquals(emptySet<String>(), missingTypePermissions(services(main), permissions(main)))
        assertEquals(
            emptySet<String>(),
            missingTypePermissions(services(main) + services(suite), permissions(main) + permissions(suite)),
        )
    }

    /** The FOREGROUND_SERVICE_<TYPE> permissions [services]' declared types need that [held] lacks. */
    private fun missingTypePermissions(services: List<Element>, held: Set<String>): Set<String> =
        services.flatMap { types(it) }
            // A short service needs no type permission.
            .filter { it != "shortService" }
            .map { "android.permission.FOREGROUND_SERVICE_" + upperSnake(it) }
            .filterNot { it in held }
            .toSet()

    private fun upperSnake(camel: String): String =
        camel.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    private fun types(service: Element): List<String> =
        service.androidAttr("foregroundServiceType").split('|').map { it.trim() }.filter { it.isNotEmpty() }

    private fun services(doc: Element): List<Element> =
        children(doc, "application").flatMap { children(it, "service") }

    private fun permissions(doc: Element): Set<String> =
        children(doc, "uses-permission").map { it.androidAttr("name") }.toSet()

    private fun children(parent: Element, tag: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun manifest(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SUBTYPE_PROPERTY = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    }
}

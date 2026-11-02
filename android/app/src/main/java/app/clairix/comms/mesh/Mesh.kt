package app.clairix.comms.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.clairix.Store
import app.clairix.comms.CommsRepository
import app.clairix.comms.lora.Lora
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The offline mesh as the rest of the app sees it: whether the owner has it on, the
 * bundles this phone carries, and the radio. Off by default, because a phone running
 * it advertises a Clairix service UUID that anyone scanning can see: it says "a Clairix
 * phone is here", never whose. It is for when the network is down or not to be trusted.
 */
object Mesh {

    private const val PREFS = "mesh"
    private const val KEY_ON = "on"
    private const val FILE = "mesh_store.json"

    @Volatile private var store: MeshStore? = null
    @Volatile private var node: MeshNode? = null
    // Holds only the application context, which lives as long as the process.
    @SuppressLint("StaticFieldLeak") @Volatile private var aware: MeshAware? = null
    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    val PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
    )

    /**
     * What the page asks for when the mesh is turned on: [PERMISSIONS], which it needs,
     * and on Android 13 and later Nearby Wi-Fi devices for Wi-Fi Aware, which it uses
     * when granted and goes without when not.
     */
    val REQUESTED: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PERMISSIONS + Manifest.permission.NEARBY_WIFI_DEVICES else PERMISSIONS

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun permitted(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun store(context: Context): MeshStore =
        store ?: synchronized(this) { store ?: MeshStore(Store(context.applicationContext, FILE)).also { store = it } }

    /** Turns the mesh on or off, starting or stopping its service. */
    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        val intent = Intent(app, MeshService::class.java)
        if (on && permitted(app)) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
    }

    /**
     * Starts the service again if the owner left the mesh on and it is not
     * running; for app start. A mesh already running is left as it is, rather
     * than taken through a foreground start again every time the app opens.
     */
    fun resume(context: Context) {
        if (node != null) return
        if (enabled(context) && permitted(context)) runCatching {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, MeshService::class.java))
        }
    }

    internal fun startNode(context: Context) {
        if (node != null) return
        val n = MeshNode(
            context, store(context),
            ownTags = { CommsRepository.meshTags() },
            onDeliver = { data -> CommsRepository.receiveFromMesh(data) },
            _status = _status,
            // A conversation open on screen: a message may be about to need the mesh.
            conversationOpen = { CommsRepository.openPeer != null },
            onPace = { full -> aware?.setFullPace(full) }
        )
        node = n
        if (MeshAware.usable(context)) {
            aware = MeshAware(
                context, store(context),
                ownTags = { CommsRepository.meshTags() },
                onDeliver = { data -> CommsRepository.receiveFromMesh(data) },
                status = _status
            ).also { it.start() }
        }
        _status.value = _status.value.copy(carrying = store(context).size, awareSupported = aware != null)
        n.start()
        // A paired LoRa radio rides on the same service: it carries the same bundles, farther.
        Lora.attach(context)
    }

    internal fun stopNode() {
        Lora.detach()
        aware?.stop()
        aware = null
        node?.stop()
        node = null
    }
}

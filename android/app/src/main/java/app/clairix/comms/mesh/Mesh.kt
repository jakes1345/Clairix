package app.clairix.comms.mesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.clairix.Store
import app.clairix.comms.CommsRepository
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
    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    val PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
    )

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

    /** Starts the service again if the owner left the mesh on; for app start. */
    fun resume(context: Context) {
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
            _status = _status
        )
        node = n
        _status.value = _status.value.copy(carrying = store(context).size)
        n.start()
    }

    internal fun stopNode() {
        node?.stop()
        node = null
    }
}

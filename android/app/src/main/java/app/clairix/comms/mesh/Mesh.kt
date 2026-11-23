package app.clairix.comms.mesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.clairix.Store
import app.clairix.comms.CommsRepository
import app.clairix.comms.lora.LoraLink
import app.clairix.comms.lora.LoraStatus
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
    private const val KEY_LORA = "lora_radio"
    private const val KEY_LORA_NAME = "lora_radio_name"

    @Volatile private var store: MeshStore? = null
    @Volatile private var node: MeshNode? = null
    @Volatile private var loraLink: LoraLink? = null
    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()
    private val _lora = MutableStateFlow(LoraStatus())
    /** The LoRa bridge through a paired Meshtastic radio, when one is chosen. */
    val lora: StateFlow<LoraStatus> = _lora.asStateFlow()

    /** The chosen Meshtastic radio's address and name, or null. */
    fun loraRadio(context: Context): Pair<String, String>? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val addr = prefs.getString(KEY_LORA, null) ?: return null
        return addr to (prefs.getString(KEY_LORA_NAME, null) ?: addr)
    }

    /**
     * Chooses the Meshtastic radio to bridge to LoRa, or forgets it with null. Takes
     * effect at once when the mesh is running, and from its next start otherwise.
     */
    fun setLoraRadio(context: Context, address: String?, name: String? = null) {
        val app = context.applicationContext
        val editor = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (address == null) editor.remove(KEY_LORA).remove(KEY_LORA_NAME)
        else editor.putString(KEY_LORA, address).putString(KEY_LORA_NAME, name ?: address)
        editor.apply()
        synchronized(this) {
            loraLink?.stop()
            loraLink = null
            _lora.value = LoraStatus(radioName = name)
            if (address != null && node != null) startLora(app)
        }
    }

    private fun startLora(context: Context) {
        val (addr, name) = loraRadio(context) ?: return
        _lora.value = LoraStatus(radioName = name)
        val link = LoraLink(
            context, addr, store(context),
            ownTags = { CommsRepository.meshTags() },
            onDeliver = { data -> CommsRepository.receiveFromMesh(data) },
            status = _lora,
        )
        loraLink = link
        link.start()
    }

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
            conversationOpen = { CommsRepository.openPeer != null }
        )
        node = n
        _status.value = _status.value.copy(carrying = store(context).size)
        n.start()
        synchronized(this) { if (loraLink == null) startLora(context) }
    }

    internal fun stopNode() {
        node?.stop()
        node = null
        synchronized(this) { loraLink?.stop(); loraLink = null }
    }
}

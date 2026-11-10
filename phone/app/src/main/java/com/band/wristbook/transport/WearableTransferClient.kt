package com.band.wristbook.transport

import android.content.Context
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

enum class TransferError {
    BAND_NOT_FOUND,
    WATCH_UNRESPONSIVE,
    FILE_INIT_FAILED,
    PACKET_SEND_FAILED,
    BLOCK_ACK_TIMEOUT
}

class WearableTransferClient(context: Context, private val scope: CoroutineScope) {
    private val nodeApi = Wearable.getNodeApi(context)
    private val authApi = Wearable.getAuthApi(context)
    private val messageApi = Wearable.getMessageApi(context)
    @Volatile
    private var activeNode: Node? = null

    @Volatile
    private var handshakeDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var transferAckDeferred: CompletableDeferred<JSONObject>? = null

    private val messageListener = OnMessageReceivedListener { _, bytes ->
        try {
            var text = bytes.decodeToString().trim()
            if (text.startsWith("\"") && text.endsWith("\"")) {
                text = JSONTokener(text).nextValue().toString()
            }
            val packet = JSONObject(text)
            val tag = packet.optString("tag")
            if (tag == "handshake") {
                val step = packet.optInt("step", 0)
                if (step > 0) {
                    handshakeDeferred?.complete(true)
                }
                if (step < 2) {
                    val reply = JSONObject().put("tag", "handshake").put("step", step + 1).toString()
                    activeNode?.let { messageApi.sendMessage(it.id, reply.toByteArray(Charsets.UTF_8)) }
                }
            } else if (tag == "book") {
                transferAckDeferred?.complete(packet)
            }
        } catch (_: Exception) {}
    }

    suspend fun queryConnectedBand(): Result<String> {
        val deferred = CompletableDeferred<Result<String>>()
        nodeApi.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNullOrEmpty()) {
                deferred.complete(Result.failure(Exception()))
            } else {
                val node = nodes[0]
                activeNode = node
                val permissions = arrayOf(Permission.DEVICE_MANAGER)
                authApi.checkPermissions(node.id, permissions).addOnSuccessListener { granted ->
                    if (granted.isNotEmpty() && !granted[0]) {
                        authApi.requestPermission(node.id, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener {
                                deferred.complete(Result.success(node.name ?: "Band 10"))
                            }
                            .addOnFailureListener {
                                deferred.complete(Result.success(node.name ?: "Band 10"))
                            }
                    } else {
                        deferred.complete(Result.success(node.name ?: "Band 10"))
                    }
                }.addOnFailureListener {
                    deferred.complete(Result.success(node.name ?: "Band 10"))
                }
            }
        }.addOnFailureListener { e ->
            deferred.complete(Result.failure(e))
        }
        return deferred.await()
    }

    private suspend fun unregisterListener(nodeId: String) {
        val deferred = CompletableDeferred<Unit>()
        messageApi.removeListener(nodeId)
            .addOnSuccessListener { deferred.complete(Unit) }
            .addOnFailureListener { deferred.complete(Unit) }
        deferred.await()
    }

    private suspend fun registerListener(nodeId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        messageApi.addListener(nodeId, messageListener)
            .addOnSuccessListener { deferred.complete(true) }
            .addOnFailureListener { deferred.complete(false) }
        return deferred.await()
    }

    private suspend fun launchTransferPage(nodeId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        nodeApi.launchWearApp(nodeId, "/pages/transfer")
            .addOnSuccessListener { deferred.complete(true) }
            .addOnFailureListener { deferred.complete(false) }
        return deferred.await()
    }

    private suspend fun sendPacket(nodeId: String, jsonString: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        messageApi.sendMessage(nodeId, jsonString.toByteArray(Charsets.UTF_8))
            .addOnSuccessListener { deferred.complete(true) }
            .addOnFailureListener { deferred.complete(false) }
        return deferred.await()
    }

    fun transferBook(
        fileName: String,
        content: String,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onError: (TransferError) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val failure = performTransfer(fileName, content) { progress ->
                withContext(Dispatchers.Main) { onProgress(progress) }
            }
            withContext(Dispatchers.Main) {
                if (failure == null) onSuccess() else onError(failure)
            }
        }
    }

    private suspend fun performTransfer(
        fileName: String,
        content: String,
        onProgress: suspend (Float) -> Unit
    ): TransferError? {
        if (queryConnectedBand().isFailure) return TransferError.BAND_NOT_FOUND
        val nodeId = activeNode?.id ?: return TransferError.BAND_NOT_FOUND

        unregisterListener(nodeId)
        if (!registerListener(nodeId)) return TransferError.WATCH_UNRESPONSIVE

        try {
            launchTransferPage(nodeId)
            delay(LAUNCH_DELAY_MS)

            if (!performHandshake(nodeId)) return TransferError.WATCH_UNRESPONSIVE

            val chunks = splitIntoChunks(content, CHUNK_CHARS)
            if (chunks.isEmpty()) return TransferError.FILE_INIT_FAILED

            transferAckDeferred = CompletableDeferred()
            val startPacket = JSONObject()
                .put("tag", "book")
                .put("action", "start")
                .put("fileName", fileName)
                .put("totalChunks", chunks.size - 1)
                .toString()
            sendPacket(nodeId, startPacket)

            val ready = withTimeoutOrNull(START_TIMEOUT_MS) { transferAckDeferred?.await() }
            if (ready?.optString("status") != "ready") return TransferError.FILE_INIT_FAILED

            for (index in chunks.indices) {
                transferAckDeferred = CompletableDeferred()
                val chunkPacket = JSONObject()
                    .put("tag", "book")
                    .put("action", "chunk")
                    .put("index", index)
                    .put("payload", chunks[index])
                    .toString()

                if (!sendPacket(nodeId, chunkPacket)) return TransferError.PACKET_SEND_FAILED

                val ack = withTimeoutOrNull(CHUNK_TIMEOUT_MS) { transferAckDeferred?.await() }
                val status = ack?.optString("status")
                if (status != "next" && status != "completed") return TransferError.BLOCK_ACK_TIMEOUT

                onProgress((index + 1).toFloat() / chunks.size.toFloat())
            }
            return null
        } finally {
            withContext(NonCancellable) { unregisterListener(nodeId) }
        }
    }

    private suspend fun performHandshake(nodeId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        handshakeDeferred = deferred
        val init = JSONObject().put("tag", "handshake").put("step", 0).toString()
        repeat(HANDSHAKE_ATTEMPTS) {
            sendPacket(nodeId, init)
            if (withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { deferred.await() } == true) return true
        }
        return false
    }

    private fun splitIntoChunks(text: String, size: Int): List<String> {
        val chunks = ArrayList<String>((text.length + size - 1) / size)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    private companion object {
        const val CHUNK_CHARS = 4000
        const val LAUNCH_DELAY_MS = 800L
        const val HANDSHAKE_ATTEMPTS = 10
        const val HANDSHAKE_TIMEOUT_MS = 650L
        const val START_TIMEOUT_MS = 5000L
        const val CHUNK_TIMEOUT_MS = 6000L
    }
}

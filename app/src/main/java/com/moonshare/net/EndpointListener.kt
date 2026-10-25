package com.moonshare.net

import com.moonshare.data.model.TransferTask

/**
 * Callbacks que comparten los dos extremos del enlace. El [TransferEngine] los
 * implementa; así los endpoints no saben nada de Compose, de notificaciones ni de
 * DataStore, y se pueden testear solos.
 */
interface EndpointListener {

    /** El otro lado se presentó tras el HANDSHAKE. */
    fun onPeerIdentified(name: String, deviceId: String) {}

    /** Tarea nueva agregada a la cola visible. */
    fun onTaskQueued(task: TransferTask) {}

    /** Progreso de un ítem. [bytesPerSec] es instantáneo, promediado en la ventana local. */
    fun onProgress(itemId: String, transferred: Long, total: Long, bytesPerSec: Long) {}

    /** Un archivo terminó (ok o no). Acá suena la gota de agua. */
    fun onFileFinished(itemId: String, ok: Boolean, error: String? = null) {}

    /** Sesión terminada por cualquier motivo. [error] null = cierre limpio. */
    fun onSessionClosed(error: String? = null) {}

    /**
     * Solo del lado que recibe: pide consentimiento al usuario. Suspende hasta que
     * acepte, rechace o expire [timeoutMs]. Devuelve los ids aceptados (vacío = todo rechazado).
     */
    suspend fun awaitConsent(fromName: String, tasks: List<TransferTask>, timeoutMs: Long): List<String> =
        tasks.map { it.itemId }

    /** El receptor necesita saber el destino antes de escribir: lo provee el engine. */
    fun consentTimeoutMs(): Long = 120_000L
}

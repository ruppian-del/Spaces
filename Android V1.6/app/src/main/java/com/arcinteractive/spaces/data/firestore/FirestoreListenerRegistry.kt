package com.arcinteractive.spaces.data.firestore

import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.ConcurrentHashMap

object FirestoreListenerRegistry {
    private val listeners = ConcurrentHashMap<String, ListenerRegistration>()

    fun register(key: String, registration: ListenerRegistration?): ListenerRegistration? {
        val delegate = registration ?: run {
            remove(key)
            return null
        }

        listeners.remove(key)?.remove()
        listeners[key] = delegate
        return ManagedListenerRegistration(key, delegate)
    }

    fun remove(key: String) {
        listeners.remove(key)?.remove()
    }

    fun stopAllFirestoreListeners() {
        val activeListeners = listeners.values.toList()
        listeners.clear()
        activeListeners.forEach { registration ->
            runCatching { registration.remove() }
        }
    }

    private fun removeDelegate(key: String, delegate: ListenerRegistration) {
        listeners.remove(key, delegate)
        delegate.remove()
    }

    private class ManagedListenerRegistration(
        private val key: String,
        private val delegate: ListenerRegistration
    ) : ListenerRegistration {
        override fun remove() {
            removeDelegate(key, delegate)
        }
    }
}

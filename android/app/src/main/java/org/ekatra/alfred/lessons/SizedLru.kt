package org.ekatra.alfred.lessons

/** A least-recently-used map bounded by total size in bytes. Thread-safe. */
internal class SizedLru<V : Any>(private val maxBytes: Long) {
    private class Entry<V>(val value: V, val bytes: Long)

    private val map = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {}
    private var total = 0L

    @Synchronized
    operator fun get(key: String): V? = map[key]?.value

    @Synchronized
    fun put(key: String, value: V, bytes: Long) {
        map.remove(key)?.let { total -= it.bytes }
        map[key] = Entry(value, bytes)
        total += bytes
        // Evict the least recently used, but never the entry just added.
        val it = map.entries.iterator()
        while (total > maxBytes && map.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            total -= e.value.bytes
            it.remove()
        }
    }

    @Synchronized
    fun remove(key: String) { map.remove(key)?.let { total -= it.bytes } }

    @Synchronized
    fun clear() { map.clear(); total = 0 }

    @get:Synchronized
    val size: Int get() = map.size

    @get:Synchronized
    val totalBytes: Long get() = total
}

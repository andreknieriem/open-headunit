package android.util

class SparseArray<T>(initialCapacity: Int) {
    private val values = HashMap<Int, T>(initialCapacity)
    fun get(key: Int): T = values.getValue(key)
    fun put(key: Int, value: T) { values[key] = value }
}

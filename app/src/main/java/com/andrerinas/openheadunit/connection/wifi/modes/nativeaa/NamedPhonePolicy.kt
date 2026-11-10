package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/** Names are not unique, so a name must pin exactly one bonded address before a phone is woken. */
object NamedPhonePolicy {

    /** A bonded device: its address, the names it answers to (remote name, alias) and the driver verdict. */
    data class Paired(
        val mac: String,
        val names: List<String>,
        val verdict: DriverCandidatePolicy.Verdict
    )

    sealed class Result {
        data class Match(val mac: String) : Result()
        data class Ambiguous(val macs: List<String>) : Result()
        object NotAPhone : Result()
        object NotFound : Result()
        object NotPaired : Result()
    }

    fun resolve(name: String, paired: List<Paired>): Result {
        val wanted = name.trim()
        if (wanted.isEmpty()) return Result.NotFound
        val named = paired.filter { p -> p.names.any { it.trim().equals(wanted, ignoreCase = true) } }
        if (named.isEmpty()) return Result.NotFound
        val phones = named.filter { it.verdict != DriverCandidatePolicy.Verdict.NOT_A_PHONE }
            .map { it.mac }.distinctBy { it.uppercase() }
        return when {
            phones.isEmpty() -> Result.NotAPhone
            phones.size == 1 -> Result.Match(phones[0])
            else -> Result.Ambiguous(phones)
        }
    }

    /** A MAC is trusted as given, but must be bonded; the bonded spelling is the one returned. */
    fun resolveMac(mac: String, paired: List<Paired>): Result {
        val found = paired.firstOrNull { it.mac.equals(mac.trim(), ignoreCase = true) }
        return if (found == null) Result.NotPaired else Result.Match(found.mac)
    }
}

package com.andrerinas.openheadunit.aap

/**
 * Who gets the [com.andrerinas.openheadunit.contract.NavigationUpdateIntent] broadcast.
 *
 * The broadcast always goes out with the signature permission, so only apps signed with this
 * app's key receive it. Nothing here changes that. It carries the current road, the next road,
 * distances and the ETA, which is location data, so any other app gets it only when the user
 * named that app in Settings. Each named app gets its own copy addressed with `setPackage()`:
 * an explicit target is the only app that can receive it, and on Android 8 and later it also
 * reaches a manifest receiver, which an implicit broadcast does not.
 *
 * There is deliberately no "every app" choice, and an empty or blank name never widens the copy
 * to an implicit broadcast.
 */
object NavigationUpdateSharePolicy {

    /**
     * The packages that get an addressed copy, sorted so the order does not depend on the set.
     *
     * @param sharingAvailable false in the playstore flavor, which never shares (see
     * `NavigationShareControl`); nothing is sent to anyone there beyond the signature broadcast.
     * @param chosenPackages what the user picked in Settings.
     * @param ownPackage this app, which already gets the signature broadcast.
     */
    fun targetPackages(
        sharingAvailable: Boolean,
        chosenPackages: Set<String>,
        ownPackage: String
    ): List<String> {
        if (!sharingAvailable) return emptyList()
        return chosenPackages
            .map { it.trim() }
            .filter { isPackageName(it) && it != ownPackage }
            .distinct()
            .sorted()
    }

    /**
     * Two or more dot-separated segments of letters, digits and underscores, each starting with a
     * letter, as Android requires of an application id. Anything else is not an app that could be
     * installed, so it is dropped instead of being passed to `setPackage()`.
     */
    fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}

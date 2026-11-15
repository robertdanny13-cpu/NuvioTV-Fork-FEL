package com.nuvio.tv.core.player.firetvfel

import com.nuvio.tv.core.player.amlfel.AmlFelSupport

/**
 * Which backend serves "Native DV7 FEL/MEL". One setting, two boxes:
 *  - Ugoos AM9 Pro with the FEL kernel: the Amlogic dual-layer amstream port ([AmlFelSupport]),
 *  - Fire TV Cube 3 with the Fire TV AV Magisk module: MediaCodec plus the module's /dev/dvfel ([FireTvFel]).
 * On any other box the setting behaves exactly like Auto.
 */
internal object NativeFelRoute {
    /** The AM9 route can run (dual-layer port, Dolby Vision driver, FEL kernel, libdovi). */
    fun isAmlUsable(bits: Int = AmlFelSupport.deviceBits()): Boolean = AmlFelSupport.isNativeFelUsable(bits)

    /** Native FEL can run on this box through either backend. */
    fun isUsable(bits: Int = AmlFelSupport.deviceBits()): Boolean = isAmlUsable(bits) || FireTvFel.available()

    /** Native FEL runs through the Fire TV Cube MediaCodec adapter (and not the AM9 renderer). */
    fun usesFireTvRoute(bits: Int = AmlFelSupport.deviceBits()): Boolean = !isAmlUsable(bits) && FireTvFel.available()

    /**
     * The box has hardware for a deliberate Native FEL choice (AM9 port present, or a ready Fire TV Cube).
     * Used by the assessment so a saved Native FEL choice is not "corrected" to Auto.
     */
    fun isDeviceUsable(): Boolean = AmlFelSupport.isDeviceUsable() || FireTvFel.available()
}

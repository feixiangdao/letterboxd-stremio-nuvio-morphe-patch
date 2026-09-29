package app.playerbridge.patches.douban

import app.morphe.patcher.Fingerprint

/**
 * Douban 7.135.0 is protected by the NetEase NIS wrapper, so the real
 * MovieActivity2 bytecode is not present in the APK on disk.
 *
 * Instead we inject into the wrapper's Instrumentation proxy. At runtime it
 * receives the real Activity instance after the protected classes are loaded.
 */
object DoubanActivityCreateFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        classDef.type ==
            "Lcom/netease/nis/wrapper/plugin/InstrumentationProxy;" &&
            method.name == "callActivityOnCreate"
    },
)

package app.playerbridge.patches.douban

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.playerbridge.patches.shared.Constants.COMPATIBILITY_DOUBAN

private const val EXTENSION_CLASS =
    "Lapp/playerbridge/extension/DoubanPlayerBridgeExtension;"

/**
 * Runtime bridge for the protected Douban APK.
 *
 * The business dex is packed by NetEase NIS, therefore a conventional
 * fingerprint against MovieActivity2 cannot work before the app is unpacked
 * at runtime. The shell Instrumentation sees every Activity, so we hook its
 * callActivityOnCreate and hand the real Activity object to the extension.
 */
@Suppress("unused")
val addDoubanPlayerButtonsPatch = bytecodePatch(
    name = "Add Stremio + Nuvio buttons (Douban)",
    description = "Adds independent Stremio and Nuvio buttons to Douban movie/TV " +
        "detail pages. Uses runtime Activity detection so it works with the NIS-protected APK.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DOUBAN)

    extendWith("extensions/extension.mpe")

    execute {
        DoubanActivityCreateFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, " +
                "$EXTENSION_CLASS->onActivityCreated(Landroid/app/Activity;)V",
        )
    }
}

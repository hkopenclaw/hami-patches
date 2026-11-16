package app.morphe.patches.hami.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_HAMI = Compatibility(
        name = "Hami Video",
        packageName = "com.cht.ottPlayer",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x005AAA,
        signatures = setOf(
            // 由 Hami Video 7.12.1017 官方 APK 嘅 v2 簽名證書抽出嚟嘅 SHA-256 fingerprint。
            // 作用：只准 patch 真正由 Hami 官方簽名嘅 APK。
            "6c86dadba1002af532c9acfe3fd1d34e92795ef013c4a74d699659143bdac043"
        ),
        targets = listOf(
            AppTarget(
                version = "7.12.1017",
                versionCode = 1017,
                minSdk = 25
            )
        )
    )
}

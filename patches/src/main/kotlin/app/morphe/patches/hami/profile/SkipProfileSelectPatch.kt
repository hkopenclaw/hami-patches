package app.morphe.patches.hami.profile

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.hami.shared.Constants.COMPATIBILITY_HAMI

/**
 * 跳過啟動嗰陣嘅「切換成員」頁（v1）。
 *
 * 背景（已核實，Hami Video 7.12.1017）：
 * 啟動流程入面，MainActivity$2$9.onNext(List) 喺登入之後 check App.p
 * （由 server 拎返嚟嘅 profile list）——多過一個 profile（size > 1）
 * 就無條件開 UserProfileSelectActivity（「切換成員」嗰頁），
 * 完全唔理用戶上次揀咗邊個。揀咗嘅 profile 係經
 * setDeviceUserProfileId → server API SET_DEVICE_USER_PROFILEID 記低，
 * 每次登入 server 會喺 Login 入面返埋個 userProfileId 俾 app。
 * 但啟動嗰陣個 decision 位淨係數 profile 數目，唔會 check 呢個已存值。
 * 成個 app 亦都搵唔到任何「記住選擇／唔再問」設定。
 *
 * 做法：喺 MainActivity$2$9.onNext 嘅登入檢查之後、數 profile 數目之前，
 * 插入一段檢查——讀返已存嘅 userProfileId（經 AccountManager.c 拎 Login，
 * 再 getUserProfileId），如果有值而且仲喺而家個 profile list（App.p）
 * 入面，就直接跳去 proceed 位（buildViews／getMainMenu），唔開揀頁。
 * 如果無值、空字串、或者嗰個 profile 已經唔喺 list 入面，
 * 就行返原本流程，照彈揀頁。
 *
 * Register 分析（已核實，MainActivity$2$9.onNext(List)）：
 * - 呢個 method 一共用 8 個 register：v0–v5 係 locals，v6 = this，
 *   v7 = List 參數（但 method 入面實際用 static App.p，唔用個參數）。
 * - 插入點（index 6，登入檢查之後、sget-object App.p 之前）嗰陣：
 *   v0 = 1（const，之後 size check 會用，唔可以掂），v6 = this，
 *   v1、v2、v3、v4、v5 未用過，v7 係登入 boolean（之後會被 App.p 覆寫）。
 * - 呢段 code 用 v1 裝 Login／profileId，v2 裝 boolean 結果，
 *   v7 裝 MainActivity／App.p。唔掂 v0、v6。
 * - Proceed 位係 instruction [79]（由 goto +6fh 同 if-eqz 嗰兩個
 *   branch 嘅 bytecode offset 核實咗，兩個都指去 [79]）。
 *   用 ExternalLabel 指去呢個現有 instruction，唔使自己計 offset。
 * - 唔 set bDelayHandleDeeplink：原本開揀頁嗰條路會將佢 set 做 true
 *   嚟 delay 個 deeplink；skip 嗰陣唔開頁，留返 false，
 *   proceed 位會即刻 handleDeepLink，同得一個 profile 嗰條路一樣。
 *
 * 已知限制：Login 入面嘅 userProfileId 係由 server 喺登入嗰陣提供。
 * 如果 server 嗰邊無返呢個值（或者 app 用咗 cache 咗嘅舊 Login），
 * 個 patch 會行返原本流程（彈揀頁），唔會 crash，淨係無 skip 到。
 * 呢個要實機測試先確認到。
 */
private object MainActivityProfileSelectFingerprint : Fingerprint(
    definingClass = "Lcom/cht/ottPlayer/ui/MainActivity\$2\$9;",
    name = "onNext",
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        methodCall(
            "Lcom/cht/ottPlayer/util/AccountManager;->i(Landroid/content/Context;)Z"
        ),
        methodCall(
            "Lcom/cht/ottPlayer/ui/UserProfileSelectActivity;->buildIntent(Landroid/content/Context;Lcom/cht/ottPlayer/util/OnNextListener;)Landroid/content/Intent;"
        ),
    )
)

val skipProfileSelectPatch = bytecodePatch(
    name = "Skip profile selection on launch",
    description = "Skips the member/profile selection page on launch when a profile " +
        "was already chosen (remembers the last selected profile).",
) {
    compatibleWith(COMPATIBILITY_HAMI)

    execute {
        MainActivityProfileSelectFingerprint.method.apply {
            // 插入點：index 6，登入檢查（if-eqz）之後、sget-object App.p 之前。
            // Proceed 位：instruction [79]（view setup＋buildViews 嗰段開頭）。
            addInstructionsWithLabels(
                6,
                """
                    iget-object v7, v6, Lcom/cht/ottPlayer/ui/MainActivity$2$9;->this$1 Lcom/cht/ottPlayer/ui/MainActivity$2;
                    iget-object v7, v7, Lcom/cht/ottPlayer/ui/MainActivity$2;->this$0 Lcom/cht/ottPlayer/ui/MainActivity;
                    invoke-static {v7}, Lcom/cht/ottPlayer/util/AccountManager;->c(Landroid/content/Context;)Lcom/cht/ottPlayer/model/Login;
                    move-result-object v1
                    if-eqz v1, :morphe_fallthrough
                    invoke-virtual {v1}, Lcom/cht/ottPlayer/model/Login;->getUserProfileId()Ljava/lang/String;
                    move-result-object v1
                    if-eqz v1, :morphe_fallthrough
                    invoke-virtual {v1}, Ljava/lang/String;->isEmpty()Z
                    move-result v2
                    if-nez v2, :morphe_fallthrough
                    sget-object v7, Lcom/cht/ottPlayer/App;->p Ljava/util/List;
                    if-eqz v7, :morphe_fallthrough
                    invoke-interface {v7, v1}, Ljava/util/List;->contains(Ljava/lang/Object;)Z
                    move-result v2
                    if-eqz v2, :morphe_fallthrough
                    goto :morphe_proceed
                    :morphe_fallthrough
                    nop
                """,
                ExternalLabel("morphe_proceed", getInstruction(79))
            )
        }
    }
}

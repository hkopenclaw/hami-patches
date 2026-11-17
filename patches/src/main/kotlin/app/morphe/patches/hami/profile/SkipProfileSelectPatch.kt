package app.morphe.patches.hami.profile

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.hami.shared.Constants.COMPATIBILITY_HAMI

// Plan B 用嘅本地儲存：patch 自己記低用戶揀咗邊個 profile，唔依賴 server。
// 用一個 patch 專屬嘅 SharedPreferences 檔（morphe_hami／last_profile_id），
// 唔掂 app 自己嘅 prefs。檔名同 key 直接寫喺下面兩段 smali 入面。

/**
 * Plan B：patch 自己喺本地記低用戶揀咗邊個 profile（v1.0.2）。
 *
 * v1 失敗原因（已實機證實，2026-11-17）：v1 靠啟動嗰陣讀
 * `Login.getUserProfileId()`，但實測啟動嗰陣個 Login 入面係無呢個值
 * （null／空字串）——`Login.setUserProfileId` 喺成個 APK 從來無本地
 * caller，server 未必會喺登入嗰陣返呢個值俾 app，或者 app 用咗 cache
 * 咗嘅舊 Login。所以 v1 安全 fallback 去原本流程，照彈揀頁。
 * 唔係 module 問題，係資料來源本身唔可靠。
 *
 * Plan B 做法（兩部分）：
 * 1. 記低（[recordProfileChoicePatch]）：hook
 *    `UserProfileSelectActivity.setDeviceUserProfileId(Context, String,
 *    OnNextListener)`——成個 app 揀 profile 嘅唯一 choke point：揀頁撳
 *    profile 經 `switchUserProfileAndFinish` 行呢個 3-arg 版；intro
 *    dialog／加新用戶／`UserProfileSelectActivity$12.onNext` 經 2-arg
 *    版轉發去呢個 3-arg 版。喺 method 入口將個 profile id 寫入本地
 *    SharedPreferences（`morphe_hami`／`last_profile_id`）。Server
 *    嗰邊完全唔郁：原本嗰個 `SET_DEVICE_USER_PROFILEID` API call 照
 *    行，server 知嘅嘢同以前一模一樣；本地只係多記一份 copy。
 * 2. 啟動嗰陣讀返（[skipProfileSelectPatch]）：`MainActivity$2$9.onNext`
 *    嗰度唔再讀 Login，改讀本地 SharedPreferences。有值而且仲喺而家
 *    個 profile list（App.p）入面，就直接跳去 proceed 位
 *    （buildViews／getMainMenu），唔開揀頁；無值、空字串、或者嗰個
 *    profile 已經唔喺 list 入面，就行返原本流程，照彈揀頁。
 *
 * 代價：裝咗新 module 之後，要手動揀一次 profile 先至會開始 skip
 * （因為要先記低一次）。之後每次轉 profile（手動「切換成員」）都會
 * 更新返個本地值。
 */

// 3-arg setDeviceUserProfileId 係唯一 choke point。Filters 唯一性已核實
// （androguard 掃全 app：得呢一個 method 同時有 OttService.l2 同
// UserProfileSelectActivity$16.<init> 兩個 call）。
private object SetDeviceUserProfileIdFingerprint : Fingerprint(
    definingClass = "Lcom/cht/ottPlayer/ui/UserProfileSelectActivity;",
    name = "setDeviceUserProfileId",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Lcom/cht/ottPlayer/util/OnNextListener;"
    ),
    filters = listOf(
        methodCall(
            "Lcom/cht/ottPlayer/OttService;->l2(Landroid/content/Context; Ljava/lang/String; Ljava/lang/String;)Lio/reactivex/Flowable;"
        ),
        methodCall(
            "Lcom/cht/ottPlayer/ui/UserProfileSelectActivity\$16;-><init>(Landroid/content/Context; Ljava/lang/String; Ljava/util/List; Z Landroid/content/Context; Lcom/cht/ottPlayer/util/OnNextListener;)V"
        ),
    )
)

val recordProfileChoicePatch = bytecodePatch(
    name = "Record selected profile locally",
    description = "Records the profile the user picks into local SharedPreferences " +
        "so it can be remembered across launches.",
) {
    compatibleWith(COMPATIBILITY_HAMI)

    execute {
        SetDeviceUserProfileIdFingerprint.method.apply {
            // 插入點：index 0，method 入口。
            // Register 分析（已核實，3-arg setDeviceUserProfileId）：
            // - 呢個 method 一共用 10 個 register：v0–v6 係 locals，
            //   v7 = Context 參數，v8 = profile id（String）參數，
            //   v9 = OnNextListener 參數。
            // - Index 0 嗰陣 v0–v6 全部未用過（第一個用 v0 嘅係 [1]
            //   move-result-object，第一個用 v1 嘅係 [3]）。
            // - 呢段 code 用 v0 裝 SharedPreferences／Editor，v1 裝
            //   prefs 名／key 字串同 mode；唔掂 v7（Context）、v8
            //   （profile id）、v9。tries = 0，無 exception handler。
            addInstructions(
                0,
                """
                    const-string v0, "morphe_hami"
                    const/4 v1, 0x0
                    invoke-virtual {v7, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                    move-result-object v0
                    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
                    move-result-object v0
                    const-string v1, "last_profile_id"
                    invoke-interface {v0, v1, v8}, Landroid/content/SharedPreferences${'$'}Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences${'$'}Editor;
                    move-result-object v0
                    invoke-interface {v0}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
                """,
            )
        }
    }
}

/**
 * 跳過啟動嗰陣嘅「切換成員」頁（v2，Plan B）。
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
 * v1 用 Login.getUserProfileId() 做資料來源，實機證實啟動嗰陣係無值，
 * 所以 v2 改讀本地 SharedPreferences（由 recordProfileChoicePatch
 * 記低嘅 `last_profile_id`）。
 *
 * 做法：喺 MainActivity$2$9.onNext 嘅登入檢查之後、數 profile 數目之前，
 * 插入一段檢查——讀返本地記低嘅 profile id，如果有值而且仲喺而家個
 * profile list（App.p）入面，就直接跳去 proceed 位（buildViews／
 * getMainMenu），唔開揀頁。如果無值、空字串、或者嗰個 profile 已經唔喺
 * list 入面，就行返原本流程，照彈揀頁。
 *
 * Register 分析（已核實，MainActivity$2$9.onNext(List)，v1 沿用）：
 * - 呢個 method 一共用 8 個 register：v0–v5 係 locals，v6 = this，
 *   v7 = List 參數（但 method 入面實際用 static App.p，唔用個參數）。
 * - 插入點（index 6，登入檢查之後、sget-object App.p 之前）嗰陣：
 *   v0 = 1（const，之後 size check 會用，唔可以掂），v6 = this，
 *   v1、v2、v3、v4、v5 未用過，v7 係登入 boolean（之後會被 App.p 覆寫）。
 * - 呢段 code 用 v1 裝 SharedPreferences／profileId，v2 裝 boolean
 *   結果，v3、v4 裝 prefs 名／key／mode／defValue，v7 裝
 *   MainActivity／App.p。唔掂 v0、v6。
 * - Proceed 位係 instruction [79]（由 goto +6fh 同 if-eqz 嗰兩個
 *   branch 嘅 bytecode offset 核實咗，兩個都指去 [79]）。
 *   用 ExternalLabel 指去呢個現有 instruction，唔使自己計 offset。
 * - 唔 set bDelayHandleDeeplink：原本開揀頁嗰條路會將佢 set 做 true
 *   嚟 delay 個 deeplink；skip 嗰陣唔開頁，留返 false，
 *   proceed 位會即刻 handleDeepLink，同得一個 profile 嗰條路一樣。
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
                    iget-object v7, v6, Lcom/cht/ottPlayer/ui/MainActivity$2$9;->this$1:Lcom/cht/ottPlayer/ui/MainActivity$2;
                    iget-object v7, v7, Lcom/cht/ottPlayer/ui/MainActivity$2;->this$0:Lcom/cht/ottPlayer/ui/MainActivity;
                    const-string v3, "morphe_hami"
                    const/4 v4, 0x0
                    invoke-virtual {v7, v3, v4}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                    move-result-object v1
                    if-eqz v1, :morphe_fallthrough
                    const-string v3, "last_profile_id"
                    const-string v4, ""
                    invoke-interface {v1, v3, v4}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    if-eqz v1, :morphe_fallthrough
                    invoke-virtual {v1}, Ljava/lang/String;->isEmpty()Z
                    move-result v2
                    if-nez v2, :morphe_fallthrough
                    sget-object v7, Lcom/cht/ottPlayer/App;->p:Ljava/util/List;
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

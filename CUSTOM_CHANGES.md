# 本次自定义修改说明

在原版 BCR 3.7 基础上新增了两项功能，并配置了 GitHub Actions 自动编译 APK。

## 1. 通话悬浮录音球

- 设置页 → 通用 → **通话悬浮录音球**，开启时会请求“显示在其他应用上层”权限。
- 开启后，只要有来电/去电（包括响铃阶段），就会出现一个可拖动的悬浮球。
- 点一下悬浮球：
  - 当前没有在录音 → 立即开始录音（无视“自动录音”总开关，只要录音权限已授予）。
  - 当前正在录音 → 停止并保存这段录音。
- 悬浮球颜色：蓝色 = 未录音，红色 = 正在录音。
- 相关代码：`FloatingButtonService.kt`、`RecorderInCallService.kt` 中的
  `toggleManualRecording()`、`Preferences.floatingButtonEnabled`。

> 注意：如果你同时开启了自动录音（Call recording 总开关），当通话状态再次变化时
> （比如接通、保持等）有可能会重新触发自动录音逻辑。这个悬浮球主要是为“不想自动
> 录音、只想手动控制”的场景设计的。

## 2. 备份 / 恢复全部设置

- 设置页 → **备份** 分类下新增“备份设置”“恢复设置”两项。
- 备份：把所有 SharedPreferences（录音规则、输出格式、文件名模板、各开关等）导出成
  一个 JSON 文件，通过系统文件选择器保存到任意位置。
- 恢复：选择之前导出的 JSON 文件，一次性还原全部设置。若其中包含输出目录，会尝试
  重新申请该目录的持久化访问权限。
- 相关代码：`Preferences.exportToJson()` / `Preferences.importFromJson()`，
  `SettingsViewModel.backupSettings()` / `restoreSettings()`。

## 3. GitHub Actions 自动编译 APK

新增 `.github/workflows/build-apk.yml`：

- 触发条件：`push`（任意分支）或手动触发（Actions 页面 “Run workflow”）。
- 步骤：checkout → 装 JDK 21 → `./gradlew assembleDebug` → 把生成的 debug APK
  作为 workflow 的 **Artifact** 上传（名字是 `BCR-debug-apk`）。
- Debug 包用 Android 默认的 debug 签名，可以直接安装，不需要你提供任何签名密钥。
- 编译完成后，去 GitHub 仓库的 **Actions** 标签页 → 对应的 workflow run →
  下面的 **Artifacts** 区域下载 zip，解压后就是可安装的 `.apk`。

原有的 `.github/workflows/ci.yml`（PR / push 到 master 时跑构建测试）和
`release.yml`（打 tag 时创建正式 Release，需要你自己配置签名密钥）都原样保留，没有
改动。

## 4. 声明 CAPTURE_VOICE_COMMUNICATION_OUTPUT 权限（微信/VoIP 通话录音前置准备）

- `AndroidManifest.xml` 新增一条 `<uses-permission android:name="android.permission.
  CAPTURE_VOICE_COMMUNICATION_OUTPUT" />`。
- 这是 `AudioPlaybackCaptureConfiguration` 抓取 `USAGE_VOICE_COMMUNICATION`（微信等
  VoIP 通话使用的音频 usage）所必须的签名级权限，普通三方应用即使拿到 MediaProjection
  也无法抓取这类音频流，必须由系统显式授予。
- 这条权限本身**不会自动生效**：还需要配合设备本地 `/system/etc/permissions/`（或对应
  Magisk 模块）下同包名的 `privapp-permissions-*.xml` 文件里加入同名条目，系统才会在
  这个特权 App 请求该权限时自动批准（而不是弹权限申请框，实际上普通权限框也弹不出这种
  签名级权限）。
- 目前仅完成权限声明，尚未接入实际的 VoIP 通话检测/录音逻辑，是后续开发的前置步骤。

## 5. WeChatCallAccessibilityService（诊断第一步，尚不录音）

- 新增 `WeChatCallAccessibilityService.kt` + `res/xml/wechat_accessibility_service_config.xml`，
  在 `AndroidManifest.xml` 里注册为一个无障碍服务。
- 配置文件里 `packageNames="com.tencent.mm"` 限定这个服务**只能收到微信的窗口事件**，收不到
  任何其他 App 的信息。
- 目前这个服务**只打日志，不做任何检测/录音判断**：每次微信前台窗口发生变化（
  `typeWindowStateChanged` / `typeWindowContentChanged`），就把当前窗口的 class 名字和屏幕上
  所有可见文字打到 logcat 里（tag 是 `WeChatCallAccessibilityService`）。
- 目的是先摸清楚微信语音/视频通话界面在无障碍树里长什么样（哪个 class、哪些文字/按钮），
  再据此写"识别通话开始/结束"的判断逻辑，避免瞎猜。
- 装上新 APK 后需要手动去 系统设置 → 无障碍 里找到这个服务并手动开启，这一步无法自动
  完成（Android 系统限制，无障碍服务必须用户手动授权）。
- **踩坑记录**：第一版漏加了 `android:directBootAware="true"`，导致系统在开机解锁前的
  Direct Boot 阶段直接跳过这个服务（日志里能看到
  `Ignoring non-encryption-aware service`），服务从来没真正运行过。已补上这个属性，跟
  `RecorderInCallService` 保持一致。
- **踩坑记录 2**：修好上面那个问题后，服务确实跑起来了，但只抓到一次
  `TYPE_WINDOW_STATE_CHANGED`（class 是通用的 `android.widget.FrameLayout`，不是正常
  Activity 类名），且读不到任何文字。原因是原来用 `rootInActiveWindow` 取根节点——这个
  API 拿的是"当前有输入焦点的窗口"，而微信的通话界面大概率是以悬浮层/系统弹出窗的形式画
  出来的，跟"活跃窗口"不是同一个，导致取到的根本不是通话界面那个窗口。改成用
  `windows`（遍历所有当前可见窗口）逐个检查每个窗口自己的包名，不再依赖
  `rootInActiveWindow`。同时把配置文件里的 `packageNames="com.tencent.mm"` 限制也去掉了，
  改成代码里对每个窗口单独判断包名——因为悬浮窗触发的事件，系统未必会把它标记成属于微信
  这个包，配置层面的包名过滤可能会把这类事件直接吞掉。
- **最终结论（2026-09-10，已用 `dumpsys activity activities` + `dumpsys accessibility`
  在同一次调用里交叉验证）**：微信语音/视频通话用的是同一个 Activity
  （`com.tencent.mm.plugin.voip.ui.VideoActivity`）。即使确认它正处于系统聚焦、最前台状态，
  无障碍系统的窗口列表里依然完全没有这个窗口——不是时机问题、不是代码问题，是系统级别把这
  个窗口对第三方无障碍服务隐藏了（大概率是 `FLAG_SECURE`）。**这条路线到此为止**，
  `WeChatCallAccessibilityService` 保留在代码里仅作记录，不再往这个方向投入。改用下面的
  通知监听方案做通话检测。

## 6. WeChatCallNotificationListenerService（通话检测已可用，尚未接入录音）

- 新增 `WeChatCallNotificationListenerService.kt`，在 `AndroidManifest.xml` 里注册为一个
  `NotificationListenerService`。
- **实测数据（2026-09-10，真实语音通话）**：微信接通后会发一条常驻通知，`title` 是联系人
  昵称，`text`/`ticker` 是"语音通话中"；振铃阶段没有这条通知，一接通才出现——这对我们反而
  是好事，通知一出现就等于"可以开始录了"，不用额外判断是否已接通。挂断时微信会自己撤掉这条
  通知，`onNotificationRemoved` 的 `reason=8`（`REASON_APP_CANCEL`），跟"挂断"这个动作完全
  对得上。
- 检测逻辑：`text` 匹配"语音通话中"/"视频通话中"（视频通话的具体文案尚未实测，按语音通话
  的命名习惯推测，如遇不准还需再抓一次视频通话的真实数据校正）+ `ongoing=true` 才算命中；
  用 `StatusBarNotification.key`（而不是 `id`/`tag`）匹配开始/结束是同一条通知，这个字段由
  系统保证唯一，比较稳妥。
- 目前只做检测，**没有接入任何录音逻辑**：检测到开始/结束时弹 Toast + 打日志，方便肉眼验证
  这套判断规则本身是否准确（尤其是要多测几次，包括视频通话、被拒接的来电等边界情况），
  确认稳定之后再接上 `AudioPlaybackCaptureConfiguration` 那部分。
- 装上新 APK 后需要手动去 系统设置 里搜索"通知使用权"（不同 ROM 归类的位置不一样，直接
  搜比在分类里翻更可靠）找到这个服务并手动开启。
- **实测边界情况记录（2026-09-10）**：
  1. 语音通话、视频通话都能正常触发（"视频通话中"这个猜测的文案实测确认正确）。
  2. 打出去但对方没接就挂断，也会触发"通话开始"——因为这条通知从通话界面一出现（包括
     振铃/等待阶段）就已经存在，不是接通了才有。对录音功能来说问题不大（顶多多录几秒
     等待音），但意味着"通话时长"跟"实际接通时长"不完全等价。
  3. 连续拨打两通电话，第二通的通知延迟了几秒才出现（实测延迟约 9 秒）——推测是微信自己
     内部处理上一通挂断的收尾比较慢，不是我们检测逻辑的问题。这意味着连续通话场景下，
     第二通电话开头这几秒可能会被漏录，目前还没有解决办法，先记录在案。

## 7. WeChatCallCaptureService（第一次真正尝试抓音频，实验性）

- 新增三个文件：
  - `WeChatCallAudioCaptureHolder.kt`：一个简单的单例，存放 `MediaProjection` 令牌，供
    通知监听服务和录音服务之间传递（两者生命周期不同，不能直接互相持有引用）。
  - `WeChatCallGrantActivity.kt`：专门用来弹出系统的 `MediaProjection` 授权确认框的最小化
    Activity，拿到令牌后存进上面那个单例。**带了独立桌面图标**（见下面
    `WeChatCallGrantActivityLauncher` 那个 `activity-alias`），因为之前实测发现这台设备上
    通过 `su` 调用 `am start` 会被 SELinux 拦截（`Failed transaction`），没法用 Termux
    命令拉起这个页面，只能靠手动点桌面图标。图标名字是"BCR 微信录音测试"。
  - `WeChatCallCaptureService.kt`：一个前台服务（`foregroundServiceType="microphone"`），
    同时录两路原始 PCM（不编码、不混音，方便直接判断"到底有没有抓到真实数据"）：
    - `mic_<时间戳>.pcm`：麦克风，用 `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
      （自带回声消除）
    - `remote_<时间戳>.pcm`：微信播放出来的声音，用
      `AudioPlaybackCaptureConfiguration` 抓取 `USAGE_VOICE_COMMUNICATION`（这个 usage
      是之前用 `dumpsys audio` 实测确认过的，见第 3 轮验证）
    - 文件存在 `getExternalFilesDir(null)/wechat_call_test/` 下，格式：16kHz、单声道、
      16-bit PCM，没有任何文件头（纯裸数据）。
- `WeChatCallNotificationListenerService` 检测到通话开始/结束时，现在会实际
  `startForegroundService`/`stopService` 这个录音服务，不再只是弹 Toast。
- **这一步要验证的核心问题**：`CAPTURE_VOICE_COMMUNICATION_OUTPUT` 这个权限在这台设备的
  Android 13 系统里根本不存在（第 4 节已确认），但 `WeChatCallAccessibilityService`
  确实是一个已经在正常运行的、激活状态的无障碍服务——即使它读不到微信窗口内容
  （被 `FLAG_SECURE` 挡住了，见第 5 节），它"处于激活状态"这件事本身，有可能已经足够
  让系统放行 `USAGE_VOICE_COMMUNICATION` 的抓取（这条判断逻辑在 Android 源码里大概率是
  "有权限 或者 是激活的无障碍服务"，两者满足其一即可）。**这个服务千万不能删/关**，
  就算它现在对检测通话已经没用了，也可能仍是让录音这一步成立的必要条件。
- 用法：先手动打开一次桌面上的"BCR 微信录音测试"图标、同意系统弹出的授权框（这一步
  只需要做一次，只要 App 进程不被系统杀掉，令牌会一直有效，可以反复用于多次通话——但
  进程被杀之后就要重新点一次这个图标）。然后正常打微信电话，挂断后去
  `wechat_call_test` 目录下看 `remote_*.pcm` 这个文件：如果字节数很大、内容不是清一色的
  静音，说明真的抓到了；如果文件很小或者全是静音，说明这条路在这台设备上走不通，需要
  另想办法。
- **实测结果（2026-09-10）**：确认排除了"进程被杀导致令牌失效"这个可能——全程盯着测试，
  每次打电话前都重新点过桌面图标、状态栏也确认了录屏图标常驻。但 `remote_*.pcm` 始终没
  被创建，`mic_*.pcm` 正常（约 1.4MB/40 秒）。日志明确抓到了具体报错：
  ```
  E WeChatCallCaptureService: Failed to start playback capture
  E WeChatCallCaptureService: java.lang.UnsupportedOperationException: Error: could not register audio policy
       at android.media.AudioRecord$Builder.buildAudioPlaybackCaptureRecord(AudioRecord.java:778)
       at android.media.AudioRecord$Builder.build(AudioRecord.java:909)
       at com.chiller3.bcr.WeChatCallCaptureService.startCapture(WeChatCallCaptureService.kt:156)
  ```
  `dumpsys media_projection` 确认 `MediaProjection` 本身是有效的（排除"忘记授权"），
  说明卡在 `AudioRecord.Builder().build()` 这一步——系统的 `AudioPolicyManager` 直接拒绝
  注册这个音频策略。这个异常信息比较笼统，权限不够、无障碍身份不满足条件、或者这台设备
  /ROM 干脆没实现这个能力，三种情况在 Java 层看到的都是同一句话，没法单从这行异常区分。
- **新增 `EXTRA_TEST_USAGE_MEDIA` 自测模式**：为了把"是通话这个 usage 被单独拦"和
  "整个 AudioPlaybackCapture 机制在这台设备上就是坏的"这两种可能分开，`WeChatCallGrantActivity`
  拿到 `MediaProjection` 后，现在会自动额外跑一次 15 秒的自测——把
  `addMatchingUsage(USAGE_VOICE_COMMUNICATION)` 换成 `addMatchingUsage(USAGE_MEDIA)`
  （普通应用允许抓取的类型，不需要任何特殊权限），全程与微信无关，测试期间手动放一段
  音乐/视频即可。如果这次 `remote_*.pcm` 有数据，说明抓取机制本身是通的，问题只在"通话"
  这个特定 usage 被单独拦截；如果这次依然是 0 字节或者同样的异常，说明整个机制在这台
  设备上都不可用，跟"是不是通话"无关。
- 顺带把日志从容易误导人的单一"Capture started"行，拆成了三行清楚的状态
  （`Mic capture: OK` / `Playback capture: OK|FAILED`），避免"对方声音其实没成功，但日志
  看起来像是都成功了"这种误判。

## 8. 退而求其次：外放 + 麦克风混合录音（放弃干净双轨方案）

- **对照实验结论（2026-09-11）**：分别在无障碍服务开启、关闭两种状态下各打一次真实微信
  语音电话，两次报的错一字不差（同样的 `could not register audio policy`，同样的堆栈行号），
  证明无障碍服务是否激活，对这台设备上 `USAGE_VOICE_COMMUNICATION` 被拒绝这件事完全没有
  影响。结合此前"该权限在系统里根本不存在"、"`USAGE_MEDIA` 能正常抓取"这两个已确认的事实，
  可以定性：**这是这台设备（PixelExperience / Android 13）在音频策略层面没有实现"允许抓取
  通话音频"这个能力，跟权限、身份都无关**。这条抓取系统内部通话音频的技术路线到此为止，
  不再继续投入。
- 因此改回最朴素的方案：通话期间**自动开启免提**（`AudioManager.isSpeakerphoneOn = true`，
  挂断后自动恢复到通话前的原始状态），麦克风音源从 `VOICE_COMMUNICATION` 换成
  **普通 `MIC`**——因为 `VOICE_COMMUNICATION` 自带回声消除，会把免提外放出来、又被麦克风
  听回去的对方声音当成回声滤掉，等于白开免提。
- 代价说清楚：这不再是干净的双轨数字录音，是单轨、经过"外放→空气→麦克风"这一圈物理传播
  的混合录音，音质会明显打折扣（外放音质、环境噪音、可能有轻微延迟或回声），效果好坏还需
  要实际听感验证。
- `remote_*.pcm` 这条产出**只在 `EXTRA_TEST_USAGE_MEDIA` 自测模式下还会尝试**（用于以后
  换设备/换 ROM 时重新验证这条路是否走得通），真实通话不再重复尝试这个已确认必然失败的
  调用，避免每次通话都白白抛一次异常。
- 新增 `android.permission.MODIFY_AUDIO_SETTINGS` 权限声明，这是调用
  `AudioManager.setSpeakerphoneOn()` 的前提条件（普通权限，装的时候自动给，不需要用户
  手动同意）。

## 9. 清理无效代码 + 恢复完整通知日志（排查开头缺 10 秒的问题）

- **实测确认（2026-09-11）**：真机对比测试发现，免提开不开，麦克风都能清楚录到双方声音——
  说明这台设备听筒漏音本来就够用。同时发现第 8 节加的"自动开免提"那段代码根本没有实际
  生效（`AudioManager.setSpeakerphoneOn()` 这个系统级 API，对微信这种自己管理音频路由的
  App 不一定起作用，微信很可能完全没理会这个调用）。既然不开免提效果也一样好，这段代码
  连同 `MODIFY_AUDIO_SETTINGS` 权限声明一起删掉了，不留没用的代码。
- **新问题**：接通后大约 10 秒才弹"检测到通话开始"，录音也对应缺了开头这 10 秒。目前的
  猜测是：微信这条通知在真正变成"语音通话中"之前，可能有一个我们没匹配到的中间状态
  （比如"连接中"之类的文案），而实际的音频数据很可能在那个更早的阶段就已经开始传输了。
- 为了验证这个猜测，`WeChatCallNotificationListenerService` 恢复了此前诊断阶段用过的
  "无条件记录"逻辑：不管是否匹配关键词、是否是常驻通知，微信发出/撤销的**每一条**通知都会
  打进日志（tag 不变，前缀 `ALL:` 区分于原有的检测日志），跟正常的通话检测/录音逻辑并行
  存在、互不影响——现有的录音功能完全不受这次改动影响，只是多了一份更详细的记录。

## 10. 录音产出直接封装成 WAV，不用再手动转格式

- `WeChatCallCaptureService` 停止录音后，会把内存里刚录完的裸 PCM 数据加上标准的 44 字节
  WAV 文件头，另存为 `mic_<timestamp>.wav`（沿用同样的采样率/位深参数：16kHz、单声道、
  16-bit），原来的临时 `.pcm` 文件用完即删。之后 `wechat_call_test` 目录下看到的就是能
  直接用任意播放器打开试听的 `.wav` 文件了，不用再手动跑 `ffmpeg` 转换。
- 这一步是纯"加文件头"，没有任何编码运算，实现和读写成本都很低；受限于目前是一次性把整
  段录音读进内存再写文件，理论上通话时间特别长（比如几十分钟以上）时会占用较多内存，
  现阶段测试用的通话时长（几十秒到几分钟）不受影响，以后如果要支持长时间通话，需要换成
  "先占位写文件头、录完再回填实际长度"的流式写法。

## 11. 微信通话录音复用真实电话录音的自定义输出目录

- 讨论结论：格式方面 WAV 已经完全够用，不需要接入项目里那套 `Format`/`Encoder`/`Container`
  编码管线；但**自定义保存目录**这部分值得复用——项目原有的 `OutputDirUtils` 工具类里已经有
  现成的、真实电话录音也在用的 `moveToOutputDir()` 方法，接进来的成本很低，不用自己重新写
  一遍 SAF（Storage Access Framework）相关代码。
- `WeChatCallCaptureService` 停止录音、把裸 PCM 封装成 WAV 之后（仅限真实通话，
  `EXTRA_TEST_USAGE_MEDIA` 自测模式的文件依然留在 App 私有目录，不搬动——那些只是内部
  自测用的临时文件，不是要保留的正式录音），会调用 `OutputDirUtils.moveToOutputDir()`
  把这个 WAV 文件移动到 `Preferences.outputDirOrDefault`（也就是你在设置里选的那个目录，
  没设置过的话就是默认目录——跟真实电话录音用的是**同一个**目录设置）下的 **`WeChat`
  子文件夹**里。真实电话录音和微信通话录音因此会在同一个根目录下，但分开放在不同子文件夹，
  方便区分又不用分别设置两次保存位置。
- 用的 MIME 类型是 `audio/x-wav`，跟项目里 `WaveFormat.kt` 保持一致（这个具体字符串是
  Android SAF 用来判断该给文件加什么后缀的依据，用别的字符串可能导致后缀加不上）。

## 12. 并行诊断：`AudioManager.registerAudioPlaybackCallback` 作为更早的候选触发信号

- 背景：连续测试确认了检测代码本身反应极快（通知一出现，47 毫秒内录音就真正开始了），
  但真实通话开头依然会偶发缺几秒——用加强版的"无条件记录全部通知"日志测试后，确认微信
  自始至终只发一条通知（没有更早的、被漏掉的中间状态），说明缺的这几秒，缺在"用户实际接通"
  到"微信自己想起来发/更新这条通知"之间，这段完全在微信内部，不是我们这边反应慢。
- 新增 `registerPlaybackObserver()`，用公开、**不需要任何权限**的
  `AudioManager.AudioPlaybackCallback` API，实时监听系统里是否有
  `USAGE_VOICE_COMMUNICATION` 类型的播放流变为活跃/不活跃状态，只打日志（tag 前缀
  `PLAYBACK_CB:`），**不触发录音**，跟现有的通知检测并行、互不干扰。这是一个状态查询类
  API，不涉及拿到音频内容本身，所以不受本项目已确认的"无法抓取通话音频"这个限制影响。
- 目的：拿真实通话数据，对比这个信号出现的时间点，跟现有"检测到通知"的时间点，谁更早、
  早多少。如果这个信号明显更早，就把它从"仅观察"转正为真正的触发信号；如果跟通知同时甚至
  更晚，说明这条路也帮不上忙，等于确认了自动检测已经做到当前技术条件下的极限，届时改走
  手动开始/暂停按钮的方案。

## 13. 最终方案：悬浮球手动开始录音 + 自动停止录音

- **`AudioManager.registerAudioPlaybackCallback` 这条路也放弃了**：真实通话测试发现，这个
  信号在通话真正接通、微信发出"语音通话中"通知之前，会先忽真忽假地跳动好几次（推测是被
  拨号音/振铃提示音这类同样标了 `USAGE_VOICE_COMMUNICATION`、但不是真实通话内容的过渡声音
  触发的），不是一个干净、能直接当触发点用的信号，硬要用还得再加防抖逻辑，投入产出比不
  划算。相关的诊断代码（`PLAYBACK_CB`、以及排查通知中间状态用的"ALL: POSTED/REMOVED"完整
  日志）已经从 `WeChatCallNotificationListenerService` 里删掉，该文件精简回只保留最终采用
  的逻辑。
- **最终结论**：连续几轮真实通话测试都证实，自动检测这条路（不管是通知文本匹配还是
  `AudioPlaybackCallback`），从"用户实际接通"到"能拿到一个可靠的触发信号"之间，总有几秒钟
  的空档在我们控制范围之外——问题出在微信自己的行为（通知更新滞后、连接前的过渡音干扰），
  不是代码反应速度的问题（多次实测确认，从触发信号到 `AudioRecord` 真正开始写入，只需要
  几十毫秒）。
- 因此改为**完全手动开始、自动停止**的方案：
  - 检测到"语音通话中"/"视频通话中"这条通知出现时，不再自动开始录音，而是**弹出悬浮球**
    （复用项目里给真实电话用的那个 `FloatingButtonService`，跟真实电话共用同一套悬浮窗
    UI，不重复造轮子）。用户想录的时候自己点一下，因为点击是"人主动触发"，不存在等微信
    的问题，录音立刻（几十毫秒级）就能真正开始。
  - 检测到这条通知消失（无论是用户自己挂断、对方挂断、还是切去接听系统来电导致微信被
    动挂断），**自动停止**正在进行的录音并隐藏悬浮球——这一步继续用自动检测，因为"停止
    晚了几秒"顶多是多录几秒无关紧要的静音，不会像"开始晚了"那样丢失有效内容，两种情况
    不对称，所以只有"开始"改成手动，"停止"依然保留自动。
  - 只做"开始/停止"两态，不做"暂停"（用户明确表示不需要暂停这个功能，先保持简单）。
  - 真实电话和微信电话如果恰好同时发生（共用同一个悬浮球），第一版不做特殊处理，谁后
    触发就由谁的回调生效——这是个概率很低的边界情况，等真的遇到问题再说。
- **顺带把 `FloatingButtonService` 改成了通用组件**：原来它的点击回调是写死调用
  `RecorderInCallService.toggleManualRecordingFromBubble()`，现在 `show()` 多了一个
  `onTap: () -> Unit` 参数，调用方自己决定点击要做什么。真实电话那边（`RecorderInCallService`）
  相应改成显式传入原来的回调，行为完全不变；微信这边则传入自己的开始/停止逻辑。这是目前
  唯一一处同时涉及"微信录音"和"真实电话录音"两条线的改动，但改动本身很小，且真实电话的
  外部行为没有任何变化。

## 14. 清理 MediaProjection 相关的死代码

- 早期为验证"能不能直接抓微信通话的对方声音"做的一整套东西——`WeChatCallGrantActivity`
  （桌面上"BCR 微信录音测试"那个图标）、`WeChatCallAudioCaptureHolder`、
  `WeChatCallCaptureService` 里尝试 `AudioPlaybackCaptureConfiguration` 的那部分代码——
  已经全部删除。这条技术路线已经在第 8 节实锤确认走不通（`could not register audio
  policy`），现在录音方案是纯麦克风，完全不需要 `MediaProjection` 这一套。
- 删除范围：两个 `.kt` 文件整个删掉；`AndroidManifest.xml` 里对应的 `<activity>` +
  `<activity-alias>`（连同桌面图标）声明删掉；`strings_notranslate.xml` 里那条只给这个
  图标用的标签字符串删掉；`WeChatCallCaptureService` 大幅精简，只剩麦克风录音 →
  封装 WAV → 移动到用户输出目录这一条主线，删掉了 `testMode`、`remoteFile`、
  `remoteRecord`、`EXTRA_TEST_USAGE_MEDIA` 等所有只服务于那条死路的字段和逻辑。
- `WeChatCallAccessibilityService` 暂时没有一起删——它跟这次清理的是不同的技术路线（无
  障碍身份 vs. `MediaProjection`），虽然也已经被证实对录音这件事没有帮助，但保留成本很低，
  以后想清理随时可以再单独处理。

## 15. 新增开关：微信通话自动 / 手动录音

- `Preferences.wechatAutoRecord`（默认关闭，也就是默认手动）：新增到设置页里，紧跟在
  "悬浮录音按钮"那个开关下面。
- **关闭（默认，手动）**：跟现在一样，检测到微信通话就弹悬浮球，用户自己点击才开始录音。
- **打开（自动）**：检测到微信通话就直接开始录音，悬浮球同时弹出来，并且**一开始就是
  "录音中"的外观**（不是"未录音"），单纯用来给用户一个"确实正在录"的可视确认，不需要
  再点一下才变成录音状态；这个模式下悬浮球依然可以点，点一下会提前手动结束这次录音。
- **不管选哪种模式，"停止录音"永远是自动的**——检测到微信那条通知消失（无论是谁挂断、还是
  切去接系统来电），都会自动停止并保存录音、收起悬浮球，不需要用户操心这一步，也不受这个
  开关影响。
- 这个开关本质上是一个明确的取舍，已经和用户确认过：自动模式偶尔会因为微信自己通知得慢、
  或者系统把 App 进程"冻结"导致唤醒延迟（详见下方讨论），录丢开头几秒钟；手动模式不会有
  这个问题，但需要用户自己记得去点悬浮球。默认选择手动，图省心的用户可以自己去设置里打开
  自动模式。
- **讨论过、但没有实现**：能不能事后检测"这次是不是录丢了开头几秒"并给出提示——结论是
  基本做不到，因为这段被跳过的时间，恰好发生在我们自己的代码还没开始运行之前，没有任何
  时间戳可以拿来对比。唯一能做的是一种不精确的"经验提示"（比如"最近后台服务是刚恢复运行
  的，这次可能有延迟"），但准确性存疑，暂不实现，等自动模式实际用起来后再看要不要加。
- **关于悬浮球偶尔延迟出现的原因（不是本次改动修复的问题，只是分析记录）**：用户反馈
  "即使提前手动打开过 App，隔一段时间后第一次打微信电话仍会延迟 7 秒左右出现悬浮球，
  紧接着第二次就不会"——这个规律更符合 Android 12+ 引入的"应用冻结"（Cached App
  Freezer）机制，而不是"进程被杀"：App 退到后台一段时间后会被系统整个冻结，等真有通知
  送达时才唤醒，这个唤醒过程本身要花几秒钟；唤醒后短时间内不会再被冻结，所以连续测试时
  只有第一次会慢。这个不是应用代码能直接解决的，只能建议用户去系统设置里给这个 App 关闭
  电池优化/加入自启动保护名单，帮助降低被冻结的概率，无法保证根治。真实电话录音不受这
  个问题影响，是因为它走的是系统 Telecom 电话框架，有更高优先级的进程保护。

## 如何推送到你自己的仓库

这个压缩包里已经包含了一个初始化好的 git 仓库（`.git` 目录），本次改动已经提交为
一个 commit。步骤：

```bash
# 解压后进入目录
cd BCR-3.7

# 关联你自己在 GitHub 上新建的空仓库
git remote add origin git@github.com:<你的用户名>/<仓库名>.git

# 推送（如果远程仓库不是空的，把 master 换成你需要的分支名，或者先 git pull --rebase）
git push -u origin master
```

推送后打开 GitHub 仓库的 **Actions** 页面，应该能看到 “Build APK” 这个工作流自动开始
运行，几分钟后就能在该次运行页面底部的 Artifacts 里下载编译好的 APK。

## 4. 悬浮球能在锁屏（含 PIN/指纹/面容）上显示了

- 之前的悬浮球（`FloatingButtonService`，普通 `TYPE_APPLICATION_OVERLAY` 悬浮窗）只要设了安全锁屏
  （PIN/图案/密码/指纹/人脸），系统就会把它压在锁屏窗口下面，这是 Android 的安全限制，普通权限
  绕不过去。
- 新增了 `FloatingBubbleActivity`：来电时如果检测到锁屏是锁着的，就改用这个小 Activity 代替悬浮窗——
  它用的是官方公开的 `Activity#setShowWhenLocked` / `Activity#setTurnScreenOn`（API 27+，本项目
  minSdk 28，恒可用）来实现"显示在安全锁屏之上"，这跟系统来电界面、闹钟应用用的是同一套机制，不需要
  root、不需要无障碍、不需要系统签名。
- `FloatingBubbleUi.kt` 里抽出了悬浮球的外观（图标/背景色）和拖拽/点击逻辑，`FloatingButtonService`
  和 `FloatingBubbleActivity` 共用这一份代码，行为完全一致。
- `RecorderInCallService.updateFloatingButtonVisibility()` 现在会检查
  `KeyguardManager.isKeyguardLocked`：锁屏就显示 `FloatingBubbleActivity`，没锁屏就显示原来的
  `FloatingButtonService`；并且注册了一个广播接收器（监听解锁 `ACTION_USER_PRESENT` 和熄屏
  `ACTION_SCREEN_OFF`），在通话过程中锁屏状态发生变化时，能及时切换这两种显示方式。
- 屏幕彻底黑屏（灭屏）的情况：正常来电本身会由系统电话子系统自动点亮屏幕，配合这里的
  `setTurnScreenOn(true)`，来电时应该会自动亮屏并显示悬浮球；如果你这台设备来电时黑屏确实不会自动
  点亮，那是系统电话/省电策略层面的问题，不在这次改动能覆盖的范围内。

### 4.1 补丁：悬浮球一开始还是不显示的原因

第一版实现里 `FloatingBubbleActivity.show()` 是直接 `context.startActivity(intent)`，结果实测锁屏
时还是不出现。原因是：Android 10 及以上系统对"从后台直接启动 Activity"有严格限制——
`RecorderInCallService` 在系统眼里就是个后台 Service，从它这里直接 `startActivity()`，系统会**静默
拦截**（不一定报异常，日志里可能只有一行不起眼的 warning），这跟锁屏与否没关系，未锁屏时之所以看起来
"正常"，是因为那时走的是 `FloatingButtonService` 的悬浮窗方案，根本没触发这条限制。

修复方式：改成用**全屏意图通知（full-screen-intent notification）**去触发这个 Activity，而不是直接
调用 `startActivity()`。这是 Android 官方专门为"来电、闹钟"这类需要立刻弹出界面的场景开的口子——系统
来电界面本身也是这么实现的：

- `Notifications.kt` 新增了 `notifyFloatingBubbleTrigger()` / `cancelFloatingBubbleTrigger()`，
  发一条静默的高优先级通知，带上指向 `FloatingBubbleActivity` 的 `fullScreenIntent`；锁屏或熄屏时，
  系统会直接把这个 Activity 拉起来，而不是仅仅显示一条通知。
- `FloatingBubbleActivity.show()` 改成调用这个方法；Activity 一旦真的显示出来，会立刻把这条触发用的
  通知撤掉，通知栏里不会留痕迹。
- 补充了 `USE_FULL_SCREEN_INTENT` 权限声明。**这里有一点需要你注意**：Android 14（API 34）起，这个
  权限默认在系统层面是开着的，但如果你是从 **Google Play 商店**安装的（不适用于你这种自己编译/
  侧载安装的场景），Play 商店会自动帮你把这权限收回，除非这个 App 被归类为"通话/闹钟类"应用。你这边
  是自己编译侧载安装（或者 Magisk 模块方式），按官方说明这个权限应该默认是开着的，理论上不用你手动
  管；如果装上以后还是不行，去 系统设置 → 应用 → BCR → 权限 或"特殊访问权限"里找一下有没有"全屏通知"
  相关的开关，确认它是开着的（不同厂商 ROM 叫法可能不一样）。

## 5. 悬浮球限制在屏幕内拖动 + 放大到 1.5 倍

- `FloatingBubbleUi.attachTouchHandler()` 里，拖动时的新坐标会被 clamp 到
  `[0, 屏幕宽/高 - 悬浮球边长]` 区间内，不会再拖出屏幕外。
- 悬浮球尺寸（含图标内边距）统一放大为原来的 1.5 倍，见 `FloatingBubbleUi.SIZE_SCALE`。

## 6. 设置页"通话悬浮录音球"移到第二项

- 在 `SettingsScreen.kt` 里，把它从"通用"分组的最后一项，挪到了"通话录音"下面，紧接着的第二项。

## 7. 备份恢复：录音路径丢失时给出明确提示

- 结论：如果你是在"清除过 App 数据"或"卸载重装"之后再恢复备份，录音目录的访问权限在系统层面已经被
  收回了（这是 Android 存储访问框架 SAF 的设计——目录授权跟"这次安装 + 用户亲自选过这个文件夹"绑定，
  单靠备份里存的一个 URI 字符串，代码层面没法重新拿到访问权），这种情况下路径**没法**做到完全自动
  恢复。
- 能做、也已经做的是：把这种情况的体验改清楚。`Preferences.importFromJson()` 现在会返回一个
  `Boolean`，标记"备份里有录音目录，但这次没能重新拿到访问权限"；`SettingsViewModel` /
  `SettingsScreen` 据此会弹出一条专门的提示："已恢复设置，但录音保存目录的访问权限已失效…需要重新
  选择一次"，而不是像以前那样什么都不提示、悄悄退回默认路径。

## 8. 锁屏悬浮球架构重做：改用 `FLAG_SHOW_WHEN_LOCKED`，修复"出现了但接不了电话"

第 4 节里那套"锁屏时用 `FloatingBubbleActivity` 顶替悬浮窗"的方案，实测有两个问题：

1. 有概率不出现悬浮球（全屏意图通知在部分场景下没能可靠拉起 Activity）。
2. 出现悬浮球时，除了悬浮球本身，屏幕其它地方（包括接听按钮）完全点不了——这不是漏配了哪个
   flag，是这套方案本身的架构缺陷：`FloatingBubbleActivity` 用 `setShowWhenLocked` 顶到最上层时，
   是在**遮挡（occlude）**锁屏，锁屏本身这时候已经不是一个还在接收触摸的窗口了，所以悬浮球以外的
   区域"触摸穿透"穿的是空气，不是还活着的锁屏/接听按钮。

修复方式：不用 Activity 顶替，改成直接给 `FloatingButtonService` 原有的那个普通悬浮窗
（`TYPE_APPLICATION_OVERLAY`）加一个 `FLAG_SHOW_WHEN_LOCKED` flag（外加 `FLAG_NOT_TOUCH_MODAL` +
`FLAG_TURN_SCREEN_ON`）。这是一个从 Android 1.0 就有的老 flag，不需要 `INTERNAL_SYSTEM_WINDOW`
那种签名权限，`SYSTEM_ALERT_WINDOW`（本项目已有）就够用：

- 锁屏本身根本没被替换/遮挡掉，还是原来那个"活着"的窗口，悬浮球只是叠在它上面，
  `FLAG_NOT_TOUCH_MODAL` 穿透的是"悬浮球范围之外，回到还在正常运作的锁屏/接听按钮"，这才是真正
  意义上的穿透。
- 一套逻辑打天下，不用再区分锁屏/解锁两种情况。

具体改动：

- **删除**了 `FloatingBubbleActivity.kt` 整个文件、`Notifications.kt` 里全屏通知那一套
  （`CHANNEL_ID_FLOATING_BUBBLE` 频道、`notifyFloatingBubbleTrigger()` / `cancelFloatingBubbleTrigger()`
  方法，及对应字符串资源）、`AndroidManifest.xml` 里的 `USE_FULL_SCREEN_INTENT` 权限声明和
  `FloatingBubbleActivity` 的 `<activity>` 声明、`themes.xml` 里的 `Theme.BCR.FloatingBubble` 主题。
  旧的 `floating_bubble` 通知频道会在下次 `Notifications.updateChannels()` 运行时被当作
  legacy 频道自动删除，不会在已安装用户的通知设置里留下一个不再使用的死频道。
- `FloatingButtonService.kt` 的 `WindowManager.LayoutParams` 里加上了 `FLAG_SHOW_WHEN_LOCKED`、
  `FLAG_NOT_TOUCH_MODAL`、`FLAG_TURN_SCREEN_ON`（按你之前确认的，没加 `FLAG_KEEP_SCREEN_ON`——
  该熄屏还是正常熄屏，只是悬浮球本身不受影响，重新亮屏/解锁时又能看到）。
- `RecorderInCallService.kt` 里 `updateFloatingButtonVisibility()` 大幅简化：删掉了
  `KeyguardManager`、判断 `isKeyguardLocked` 的分支、监听解锁/熄屏广播的 `lockStateReceiver`——
  现在不管锁屏还是解锁，统一只用 `FloatingButtonService`。

## 9. 悬浮球三态修复：区分"未录音 / 暂停中 / 录音中"，点击逻辑跟外观对齐

这是你实测出来的第二个 bug，根源是外观判断和点击判断依据不一致：

- 悬浮球外观原来只看 `state == RECORDING && !isPaused`——"暂停"状态下会被判定为"未录音"的蓝色外观。
- 但点击后执行的 `toggleManualRecording()` 判断的是"这个通话有没有一个 recorder 对象"——不管这个
  recorder 是"正在录"还是"暂停中"，只要存在就走"停止"分支。

于是"自动开始录音"开启、且"自动录音规则"的"初始状态"是"暂停，直至手动恢复"时：recorder 其实已经
创建好了（只是暂停着），悬浮球却显示成蓝色"未录音"外观，你以为点一下是"开始录音"，实际代码走的是
"停止"分支——把这个还没真正录进内容的 recorder 直接终止掉，生成了一个空录音文件。

修复：

- `FloatingBubbleUi.kt` 新增 `BubbleState` 三态枚举（`NOT_RECORDING` / `PAUSED` / `RECORDING`），
  外观从两态（`Boolean`）改成三态，新增了一个琥珀色的"暂停中"背景
  `bg_floating_bubble_paused.xml`（蓝=未录音，琥珀=暂停中，红=录音中）。
- `RecorderInCallService.kt` 新增 `currentBubbleState()`，取代原来的 `isCurrentCallRecording()`，
  按 recorder 的 `state`/`isPaused` 计算出上面三态之一。
- `toggleManualRecording()` 改成按这三态分支处理，跟外观完全对齐：
  - 未录音（没有 recorder）→ 开始录音（逻辑不变）。
  - 暂停中（有 recorder 且 `isPaused == true`）→ **新增分支**：`recorder.isPaused = false`，跟持久
    通知栏"恢复"按钮做的事完全一样，录音线程会自己继续往下录，不会再被误终止成空文件。
  - 录音中（有 recorder 且 `isPaused == false`）→ 停止并保存（逻辑不变）。

## 10. 悬浮球缩小到约 80% 大小

`FloatingBubbleUi.SIZE_SCALE` 从 `1.5f` 改成了 `1.2f`（1.5 × 0.8 = 1.2），图标内边距是跟着这个倍数
算的，所以也跟着等比缩小，不需要额外改动。

## 11. 悬浮球记住上次拖动的位置

之前 `FloatingButtonService` 每次显示都用写死的坐标（左边、屏幕上三分之一处），拖到哪都不会被记住。

- `Preferences.kt` 新增了一对可空的浮点数偏好 `floatingButtonPosition`，存的是悬浮球左上角相对**屏幕宽/高的比例**（0f~1f），不是绝对像素——这样下次读取时哪怕分辨率、方向不一样（比如换了台设备，或者系统显示缩放变了），换算出来的位置依然落在合理范围内，不会因为存的是老分辨率下的像素值而跑出新屏幕。
- `FloatingBubbleUi.attachTouchHandler` 新增了一个 `onDragEnd` 回调，只在**一次拖拽结束**（`ACTION_UP`/`ACTION_CANCEL` 且确实发生了拖动）时触发一次，不会在拖动过程中的每个 `ACTION_MOVE` 都写一次盘。
- `FloatingButtonService.onCreate()`：如果 `Preferences` 里存过位置，就换算成当前屏幕下的像素坐标（并 `coerceIn` 一次夹到屏幕范围内）；如果从没拖动保存过，还是用原来那个默认位置。拖拽结束后通过 `onDragEnd` 换算成比例存回去。

## 12. 去掉每次录音都多出的 `.log` 文件

根因是 `Preferences.kt` 里：

```kotlin
get() = BuildConfig.DEBUG || prefs.getBoolean(PREF_DEBUG_MODE, false)
```

只要是 debug 包（我们 Actions 里 `assembleDebug` 编译出来的正是 debug 包），这一项永远被短路成 `true`，导致设置里那个隐藏开关（长按"关于"里的版本号）在 debug 包上完全不起作用，`RecorderThread.kt` 里"是否保留这份 logcat 调试日志"的判断也就永远是"保留"。

按你选的方案 A，把这行改成只看用户存的开关：

```kotlin
get() = prefs.getBoolean(PREF_DEBUG_MODE, false)
```

现在不管 debug 包还是 release 包，这个开关默认都是关闭的，跟"是否为 debug 编译"完全脱钩——以后如果你真的需要抓日志排查问题，长按版本号打开这个"调试模式"仍然有效，也仍然会同时显示调试选项分组。

## 13. "通话录音"改名"通话自动录音" + 悬浮球颜色说明入口

- `values-zh-rCN/strings.xml` 里 `pref_call_recording_name` 从"通话录音"改成"通话自动录音"（只改了中文；英文默认串 `Call recording` 没动，需要的话再说）。
- "通话悬浮录音球"开关下面的说明，从原来一整段静态灰字，拆成了两部分：一句不变的简短说明，加一行可点击的"查看悬浮球颜色说明"（蓝色文字），点击后弹出一个 `AlertDialog`（新文件 `settings/FloatingButtonHelpDialog.kt`），里面是你确认过的那版完整说明，含一张用 `Row`/`Column` 手搭的四列小表格（不依赖任何表格库），内容包括：
  - 首次使用必须开一次两个开关并授权的提示；
  - "悬浮球是新增功能、和原版设置组合起来比较绕，请测试后再用"的提醒；
  - "快速版"（想要类似国产手机默认设置，看表格第二行）；
  - 那张"通话悬浮录音球 / 通话自动录音 / 命中规则的初始状态 / 接通后悬浮球初始颜色"四列表；
  - 响铃阶段统一蓝色的补充说明；
  - 三种颜色分别代表什么、点击后会怎样的说明。
  这段文字连同表格内容都作为字符串资源写在 `values/strings.xml`（英文）和 `values-zh-rCN/strings.xml`（中文，用你确认的原文）两份里，不是写死在代码里的。

## 14. 悬浮球改成原作者"暂停/恢复"按钮的等价物，不再有独立的"停止并保存"语义

跟你确认过之后，悬浮球彻底改成通知栏暂停/恢复按钮的另一个入口，不再引入原作者没有的新概念：

- `toggleManualRecording()` 里"录音中"这个分支，从原来的"停止并保存"（`stopManualRecording()`，会提前终止并落盘这段录音）改成了 `recorder.isPaused = !recorder.isPaused`——跟通知栏"暂停"按钮 (`isPaused = action == ACTION_PAUSE`) 完全同一件事。一通电话从头到尾只有一份录音，什么时候真正结束、落盘，只取决于电话什么时候挂断，这跟原作者的设计（以及通知栏本身的行为）完全一致了。
- 因此专门为"停止并保存"写的 `stopManualRecording()` 变成了死代码，已删除。
- 悬浮球现在的三态是：🔵 未录音（点击开始）→ 🔴 录音中（点击暂停）→ 🟠 已暂停（点击恢复）→ 回到 🔴，直到挂断电话为止。

## 15. 命中"忽略"规则的来电，悬浮球直接不显示

之前"是否显示悬浮球"只看"通话悬浮录音球"开关和"有没有来电"，不管这个号码是不是命中了"忽略"规则——命中忽略的号码，悬浮球依然会显示成蓝色"未录音，点击开始"，容易让人产生"都设置忽略了怎么还能手动录"的疑惑。

现在 `updateFloatingButtonVisibility()` 里新增了 `isCallIgnoredByRules(call)`：只要这个号码当前没有 recorder 在跑，就单独跑一次规则匹配（复用 `CallMetadataCollector` 拿到这通电话的号码/去电还是来电/哪张卡这些信息，交给 `RecordRule.evaluate` 判断），如果匹配结果是"忽略"，悬浮球就完全不显示，跟"开关本身没开"是同一种隐藏效果。

几点说明：

- 这个匹配本身是本地的通讯录数据库查询，不涉及网络，代价很小，多算一次不是问题——但严格说不是纯内存计算，会有一次轻量的 ContentProvider 查询（之前我口误说"没有磁盘 IO"，这里更正一下）。
- 理论上存在一个极短的时间窗口：如果"自动开始录音"是开着的，电话一接通，`RecorderThread` 会先被创建（悬浮球可能一闪显示成录音中的红色），紧接着它自己跑完规则匹配、发现命中"忽略"就自我终止，悬浮球再切换成隐藏。这个窗口按代码逻辑几乎是接通后瞬间发生，正常情况下应该感知不到，如果你实测发现有明显的"先出现红色悬浮球、一闪又消失"，告诉我，我们再针对性处理这个时序问题。

## 16. 说明文案改回一段固定小字，不再需要弹窗

因为悬浮球现在就是通知栏暂停/恢复按钮的另一个入口，不再有"跟自动录音规则绕在一起"的复杂逻辑要解释，之前加的说明弹窗（`FloatingButtonHelpDialog.kt`、表格字符串、"查看颜色说明"链接）全部删掉了，回到最简单的形态：

"通话悬浮录音球"开关下面就是一段固定的灰色小字，包含：一句功能说明（悬浮球就是通知栏暂停/恢复按钮的另一个入口，方便在锁屏等没法下拉通知栏的场景使用）、一句首次使用需要开两个开关并授权的提醒，加上你确认的三行颜色说明（已经按"红色=暂停"改好）。中英文字符串都同步改了。

## 未验证事项

这里没有 Android SDK/网络环境，所以本次修改是**代码走查后交付，未在 Android
Studio 或 CI 里实际编译验证**。建议推送后先看 Actions 是否编译成功；如果报错，把
错误日志发给我，我可以继续修。

第 8 节的 `FLAG_SHOW_WHEN_LOCKED` 方案在你这台红米 K40S（PE 官方，Android 13）上是否真的能在锁屏上
正常显示、且不影响接听，这个我这边没有真机没法验证，需要你实际打一通电话（响铃阶段就锁屏）试一下；
如果还是不行，把现象（悬浮球完全不出现 / 出现但还是挡触摸 / 别的现象）告诉我，我再继续排查。

第 11 节的位置记忆同理没法用真机验证拖拽手感，麻烦顺手测一下：拖到别的位置、结束通话、再打一次电话，
悬浮球是不是停在你上次放的地方；另外如果这期间旋转过屏幕或者换过分辨率相关设置，留意一下悬浮球有没有
跑出屏幕外。

第 15 节的"忽略规则隐藏悬浮球"麻烦也实测一下：找一个命中"忽略"规则的号码（或者去"自动录音规则"里新建
一条），互相打个电话看悬浮球是不是真的没出现；如果"自动开始录音"是开着的，也留意一下接通瞬间有没有那种
"悬浮球一闪而过"的现象。

## 17. 悬浮球：接通前的颜色时机、手动点按跳过暂停、蓝/橙合并成绿色+文字提示

这一节是好几处连着改的，都是围绕"悬浮球在还没接通电话时应该长什么样、点了之后应该发生什么"这个主题：

### 17.1 手动点按悬浮球时，跳过"自动录音规则"的暂停初始状态

之前不管是自动开始录音，还是用户手动点悬浮球开始录音，走的都是同一套 `RecorderThread.evaluateRules()`
逻辑——如果"自动录音规则"里这个号码匹配到的规则，初始状态是"暂停，直至手动恢复"，那不管是自动触发还是
手动点悬浮球触发，新建出来的 `RecorderThread` 都会先把 `isPaused` 设成 `true`。这就导致：规则配的是
"暂停"时，用户在电话还没接通、悬浮球还是蓝色（未录音）的时候点一下，会先跳到🟠橙色（已暂停），而不是直接
开始录音，得再点一次才真正开始录。

现在给 `RecorderThread` 加了一个构造参数 `forceImmediateStart`，`RecorderInCallService.toggleManualRecording()`
里"未录音→点击"这个分支创建 `RecorderThread` 时会传 `true`；`evaluateRules()` 里原来的
`isPaused = initialState == RecordRule.InitialState.PAUSED`，现在改成
`isPaused = !forceImmediateStart && initialState == RecordRule.InitialState.PAUSED`。
也就是：只要是用户自己手动点悬浮球触发的录音，不管规则怎么配，都直接开始录音，不会经过暂停这一步；
规则的"暂停"设置只在真正的"自动录音"路径下才生效。

### 17.2 电话还没接通时，悬浮球提前预判"接通后是不是会自动开始录音"

如果"通话自动录音"开着，且匹配到的规则初始状态是"立即录音"，之前的表现是：电话响铃、还没接通时，
悬浮球因为压根还没有 `RecorderThread`（`callsToRecorders` 里查不到这通电话），会显示成🔵蓝色
（未录音）；等真正接通、`RecorderThread` 创建出来之后，才会跳成🔴红色（录音中）。

`RecorderInCallService` 里新增了 `willRecordImmediatelyIfStarted(call)`：在还没有 recorder 的情况下，
提前用跟 `isCallIgnoredByRules()` 同一套（一次性、本地、不涉及网络的）规则匹配逻辑，算出"如果现在就有
一个 recorder，它会不会是立刻开始录音、不暂停的状态"——只要"自动录音"开着，且匹配结果不是"忽略"、
初始状态是"立即录音"，`currentBubbleState()` 就直接把这通电话预判成🔴录音中，不用等真的接通、真的建出
recorder 才切颜色。这只影响悬浮球这一层的"预判显示"，`RecorderThread` 到底什么时候真正开始录音、
录音本身什么时候真正落笔，逻辑完全没变。

### 17.3 悬浮球出现的第一帧就是正确的颜色，不再"先默认色、一晃再变色"

排查 17.2 为什么一开始没生效时，发现了一个跟规则匹配无关的时序问题：`updateFloatingButtonVisibility()`
里是先调用 `FloatingButtonService.show(this)` 把悬浮球服务启动起来，再紧接着调用一次"把状态设成当前应该
显示的颜色"。但 `show()` 内部用的是 `context.startService(...)`，这是异步的——服务真正跑到
`onCreate()`（创建悬浮球视图、套上默认颜色）要等当前这段代码执行完、回到消息循环之后才会发生。也就是说，
紧跟在 `show()` 后面那次"设置颜色"的调用，实际执行的时候服务实例压根还没创建出来，直接被无声跳过了；
等 `onCreate()` 真正跑完，用的是写死的默认蓝色，而且没人再补一次刷新。

现在把"该显示什么颜色"这个参数直接带进 `show()`：`FloatingButtonService.show(context, initialState)`，
内部用一个 `pendingInitialState` 暂存这个值，`onCreate()` 一开始就读这个值来初始化外观，而不是硬编码
`NOT_RECORDING`。这样悬浮球从第一帧出现开始颜色就是对的，不会有"先蓝一下、再跳到目标颜色"的闪烁。

### 17.4 🔵未录音 和 🟠已暂停 合并成 🟢绿色，悬浮球下面常驻一行小字提示

这两个状态给用户的意思其实是一回事——"现在没在录，点一下就能让它录起来"——区别只在于点下去是"新建一份
录音"还是"接着录之前已经存在、暂停了的那份"，这个区别用户不需要在悬浮球这一层分辨，通知栏本身还是能看到
（有暂停中的 recorder 就会有"已暂停"这条通知，压根没开始录就没有通知）。所以：

- `FloatingBubbleUi.updateAppearance()` 里 `NOT_RECORDING` 和 `PAUSED` 两个状态现在映射到同一张背景图，
  原来蓝色的 `bg_floating_bubble_idle.xml` 直接改成了绿色（`#CC2E7D32`），不再单独用橙色；
  `bg_floating_bubble_paused.xml` 已删除。
- 决定"点一下之后到底是新建录音还是恢复暂停的录音"这套判断（`callsToRecorders[call]` 有没有、
  `isPaused` 是不是 true）完全没动，只是这两种情况现在共享同一个绿色外观。
- 悬浮球从原来的纯图标 `ImageView`，换成了"圆形图标 + 下面一行文字"的竖排小布局
  （`FloatingBubbleUi.createBubbleView()`），文字带一个半透明黑色圆角底（新增的
  `bg_floating_bubble_label.xml`），白色文字，保证不管悬浮球被拖到什么背景上都看得清，不用做描边这种更
  复杂的效果。文字内容跟着状态走：🟢绿色是"点击开始录音"，🔴红色是"点击暂停录音"。
- 因为两种状态下文字长度可能不一样，悬浮球窗口的宽度取的是两段文字里较宽的那个（`bubbleWidthPx()`
  用 `Paint.measureText` 量出来，取较大值），状态切换时悬浮球本身的宽高不会跟着变，不用重新计算拖拽范围、
  不会有跳动。悬浮球整体因此比之前明显大了一圈（多了文字这一块），可以拖动到任意位置，之前"限制在屏幕内
  拖动"的边界计算也同步换成了按新的宽/高分别计算（原来是假设正方形，用同一个 `bubbleSizePx`）。

### 17.5 设置页说明文案：常驻小字改简短 + "ⓘ"图标点开详细说明

悬浮球开关下面原来那段常驻小字（功能说明 + 首次使用提醒 + 颜色图例）内容已经比较多，这次又要加一条
"这是新增功能、跟自动录音规则组合起来比较绕、请自行测试确认"的提醒，字数会更长。所以改成了：

- 开关标题"通话悬浮录音球"右边加了一个小小的"ⓘ"按钮（纯文字符号，不是图片资源，读屏软件会读出
  "通话悬浮录音球说明"这个 content description）。
- 开关下面常驻显示的小字精简成一句话，点"ⓘ"弹出一个对话框（`AlertDialog`），完整说明放在对话框里，
  包括：功能介绍、首次使用提醒、🟢🔴 两色图例，以及新加的"这是新增功能，请先测试确认"这段提醒。
- 中英文字符串都同步改了（`pref_floating_button_desc` 变短，新增
  `pref_floating_button_help_title`/`pref_floating_button_help_body`/
  `pref_floating_button_help_content_description`，以及悬浮球用到的两条文字提示
  `floating_bubble_label_not_recording`/`floating_bubble_label_recording`）；除中英文外的其它语言
  暂时还是旧文案（会自动回退显示英文/原翻译，不影响编译，只是还没跟着这次改动更新，之后有空再补）。

## 未验证事项（本节新增）

同样是没有 Android SDK/真机环境，本节改动是代码走查后交付，没有实际编译、没有在真机上验证效果，建议：

- 悬浮球现在的窗口尺寸从"正方形图标"变成了"图标+文字的竖长条"，麻烦重点看一下：文字有没有被裁切、
  拖到屏幕四个角落时文字会不会被推出屏幕外、锁屏场景下会不会因为悬浮球变大了一些而挡住接听/挂断按钮。
- 17.1/17.2 这两条改动请按你最早报的三种设置组合分别打一遍电话，确认响铃阶段悬浮球颜色、点击后的反应
  都符合预期。
- 设置页"ⓘ"按钮点击后对话框能不能正常弹出/关闭，长文字在对话框里滚动是否正常。

## 本次新增：README 说明/截图、"关于本修改版"弹窗、Release 只发 Magisk zip

- **README.md**：文件最前面加了一段引用说明（fork 自原仓库、改了什么、跟原作者无关、
  问题去本仓库 Issues 反馈），下面附了两张截图（`app/images/mod-incoming-call.jpg`
  来电界面、`app/images/mod-active-call.jpg` 通话中界面），原有内容一字未动。
- **"关于本修改版"**：设置页"关于"分类下、"Version"条目下面新增一条 Preference，
  点击弹出 `AlertDialog`，内容是修改说明 + 两行可点击链接（原始项目、本项目地址，
  点击用 `Intent.ACTION_VIEW` 调系统浏览器打开）。相关代码在 `SettingsScreen.kt`
  里的 `showModInfoDialog` 状态和对应的 `item(key = "mod_info")`；文案在
  `strings.xml` 的 `pref_mod_info_*` 几条。只加了默认（英文）`values/strings.xml`，
  没有同步到 `values-zh-rCN` 等其它语言目录。
- **Release 流程**：`.github/workflows/build-apk.yml` 新增了一个 `release` job，
  只有推送 `v` 开头的 tag 才会触发：跑仓库原有的 Gradle 任务 `zipDebug`（把
  debug 签名的 apk 和 `app/magisk` 下的安装脚本打包成 Magisk 模块 zip，
  跟手动打包进 Magisk 里的是同一个东西），然后只把这个 zip 发布到 GitHub
  Release，不上传单独的 apk（因为 BCR 需要系统特权，裸 apk 装了也用不了）。
  原有的 `build-apk` job 和官方 `release.yml`（依赖作者自己的签名密钥）都没动。

### 未验证事项（本节新增）

同样没有实际编译验证：

- 新对话框里的两行链接文字颜色、点击区域是否符合预期，深色/浅色模式下观感如何。
- `zipDebug` 这个任务名和产物文件名模式（`find app/build -iname '*-debug.zip'`）
  是基于读代码推断的，第一次打 tag 触发 release 时建议去 Actions 页面确认这一步
  真的能找到文件，如果报"文件未找到"，需要把 `find` 的匹配模式改成实际产物路径。

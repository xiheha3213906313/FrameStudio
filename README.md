# 映序 · Frame Studio

适用于 Android 10 及以上的个人图片创作应用。使用北京地域的火山方舟标准按量 API Key。应用内包含中文创作台、作品集、任务和设置。

## 第一次生成

1. 在方舟控制台开通需要的图片模型，创建有调用权限的 API Key。
2. 安装 APK，打开「设置」中的「API Key」，粘贴密钥并保存。应用同步当前项目可见的图片模型，列表中的「即将下线」来自服务端状态。
3. 选择模型，填写画面描述，或从「灵感库」选取一段提示词。在同一创作页点击「参考图」，优先打开系统照片选择器。设备未提供该选择器时，应用内相册可在授权后按相册浏览并多选。有参考图时调用图生图，无参考图时调用文生图。
4. 在「画布设置」选择分辨率、智能比例或固定比例、输出格式和生成张数。水印默认关闭。支持关联组图的模型按组图上限请求，单图模型选择多张时按独立请求排队。切换模型或重启应用时，生成张数恢复为 1。支持透明背景和图层拆分的模型会显示对应开关。
5. 查看「费用明细」并点击「生成图片」。生成后留在创作台。任务页用标题右侧图标筛选模型，可按成功或失败查看。相邻且参考图和提示词相同的失败记录合并显示，展开后可查看每条任务。长按进入多选，全选失败会选中所有失败记录，包括响应中断。有新图到达时，任务页会打开作品集。任务角标显示进行中任务，作品缩略图完整进入屏幕后会标记已读。作品集支持按模型筛选、在预览内缩放、适应窗口时左右滑动、查看和复用参数，以及用当前图片发起编辑。

## 模型与计费

- 使用 Bearer API Key 请求 `GET /api/v3/models`，从模型领域、输出模态、任务类型及 Seedream 型号识别图片模型。首次联网同步前可使用内置目录。同步失败保留上次成功结果。密钥变更会清除该账号的模型缓存。系统在联网时安排每日同步，打开应用时也会检查缓存是否满 24 小时。
- 设置支持自定义 Model ID 和 Endpoint ID。为接入点选择实际对应的基础模型，即可使用其参数和计费规则。未知模型使用服务端默认尺寸，可选填统一输出单价，留空显示「待核价」。
- 提交前预估参考图费用、输出图费用及组图或图层数量上限。服务端响应后按 `usage.input_images`、`usage.generated_images` 和各输出图实际像素计算。固定单价使用整数分运算，分档依据总像素。各任务保存提交时的费率快照。
- 内置费率按 2026-11-18 官方标准按量定价核对。免费额度、协议折扣、预付费接入点及套餐等以账号账单为准。应用的费用统计仅包括本机保留的记录。Agent Plan 专用密钥使用不同地址与计费方式，本版本面向标准按量服务。

## 任务、图片与错误

图片生成使用 `POST /api/v3/images/generations`。支持 SSE 的模型逐张记录结果链接，其余模型在本次请求中返回结果。Android 前台服务串行消费 SQLite 中的本机队列，持久化发送阶段、HTTP 状态、方舟 Request ID 和收到的图片链接。连接生成服务时限为 45 秒，等待响应的读取时限为 15 分钟。

- 「请求未提交」表示生成请求体未发送。「响应中断」表示上传中断、已发送后未取得完整响应，或旧任务缺少发送阶段。重开应用后会继续已排队任务和未完成的原图下载。只要原请求可能已送达，就保留记录供核对。
- 方舟的标准图片生成接口在原响应返回产物。客户端编号和方舟 Request ID 用于日志关联或核对用量。提交前已有 TLS 日志投递或 TOS 转存时，可以从这些服务取回临时链接或原图，在任务中下载或导入。账单可用任务里的时间和模型核对。
- 一组图片部分失败时保留成功作品及每张失败原因。方舟返回成功后即计算输出用量，下载故障不会被误当成零费用。原图网络下载遇到临时故障会自动重试，仍有问题可在临时 URL 有效期内单独补下载。
- 处理 HTTP 与顶层 JSON 错误、单图错误、密钥和权限问题、模型不可用、内容审核、余额或配额不足、IPM 限流、服务异常、超时与 TLS 连接错误。保留方舟原始错误码、请求 ID 与可用的 Retry-After 信息。
- 私有目录中的原图可离线查看。删除任务前会确认，同时删除该任务的私有原图。系统相册副本独立保存。卸载应用前可将需要的原图导出。
- API Key 由 Android Keystore 的 AES-GCM 密钥加密。密钥仅随发往方舟 API 的 HTTPS 请求发送。图片下载使用独立连接。正式构建禁用明文流量、应用备份和外部页面访问应用桥接接口。

## 构建与验证

用 Android Studio 打开本目录，安装 Android SDK Platform 35，采用 JDK 17 或 Android Studio 自带的兼容 JDK，构建 `app`。也可运行 `./gradlew assembleDebug`，Windows 使用 `gradlew.bat assembleDebug`。默认 Gradle 调试签名适用于本地开发。自行分发时请使用自己的 Android 签名配置。

`node --test tests/pricing.test.cjs` 运行计价边界与组图、图层、未知模型用例。运行 `./gradlew testDebugUnitTest` 可复测模型筛选、请求字段、HTTP 与单图错误、超时及下载凭证隔离。首次构建需要联网获取 Android Gradle Plugin 与 Gradle。

## 排查与恢复

[单请求用量查询](https://docs.volcengine.com/docs/ark/get-inference-request-usage-api) · [火山方舟日志采集](https://console.volcengine.com/ark/region:cn-beijing/docs/log-collection) · [TOS 数据订阅](https://docs.volcengine.com/docs/torch-object-storage/creating-data-subscription-rules)

## 官方资料与示例照片

[模型列表 API](https://console.volcengine.com/ark/region:cn-beijing/docs/models-api) · [图片生成 API](https://console.volcengine.com/ark/region:cn-beijing/docs/image-generation-api) · [图片生成教程](https://console.volcengine.com/ark/region:cn-beijing/docs/image-generation-tutorial) · [模型价格](https://console.volcengine.com/ark/region:cn-beijing/docs/model-pricing) · [错误码](https://console.volcengine.com/ark/region:cn-beijing/docs/error-codes) · [API Key 管理](https://console.volcengine.com/ark/region:cn-beijing/docs/manage-api-keys)

灵感库内置摄影参考来自 Unsplash，提示词为本应用编写。[赤岩旷野](https://images.unsplash.com/photo-1509316785289-025f5b846b35) · [山谷来信](https://images.unsplash.com/photo-1464822759023-fed622ff2c3b) · [潮汐之间](https://images.unsplash.com/photo-1518837695005-2083093ee35b)

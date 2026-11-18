# 映序 · Frame Studio

适用于 Android 10 及以上的个人图片创作应用。使用北京地域的火山方舟标准按量 API Key。应用内包含中文创作台、作品集、任务和设置。

## 第一次生成

1. 在方舟控制台开通需要的图片模型，创建有调用权限的 API Key。
2. 安装 APK，点击右上角「连接方舟」，粘贴密钥并保存。应用同步当前项目可见的图片模型，列表中的「即将下线」来自服务端状态。
3. 选择模型，填写画面描述，或从「灵感库」选取一段提示词。参考图模式通过 Android 文件选择器导入本机图片，长边超过 2048 像素时缩小后上传。
4. 在「画布设置」选择分辨率、比例、输出格式、水印或组图张数。选项按基础模型能力显示。支持透明背景和图层拆分的模型会显示对应开关。
5. 查看「费用明细」并点击「生成图片」。在「任务」查看状态，「作品集」可查看原图、分享或导出到系统相册的 Pictures/映序 目录。

## 模型与计费

- 使用 Bearer API Key 请求 `GET /api/v3/models`，从模型领域、输出模态、任务类型及 Seedream 型号识别图片模型。首次联网同步前可使用内置目录。同步失败保留上次成功结果。密钥变更会清除该账号的模型缓存。
- 设置支持自定义 Model ID 和 Endpoint ID。为接入点选择实际对应的基础模型，即可使用其参数和计费规则。未知模型使用服务端默认尺寸，可选填统一输出单价，留空显示「待核价」。
- 提交前预估参考图费用、输出图费用及组图或图层数量上限。服务端响应后按 `usage.input_images`、`usage.generated_images` 和各输出图实际像素计算。固定单价使用整数分运算，分档依据总像素。各任务保存提交时的费率快照。
- 内置费率按 2026-11-18 官方标准按量定价核对。免费额度、协议折扣、预付费接入点及套餐等以账号账单为准。应用的费用统计仅包括本机保留的记录。Agent Plan 专用密钥使用不同地址与计费方式，本版本面向标准按量服务。

## 任务、图片与错误

图片生成使用非流式 `POST /api/v3/images/generations`。Android 前台服务串行消费 SQLite 中的本机队列，通知可返回任务页。请求返回后先保存结果清单，再逐张下载，原图保存在应用私有目录。每个任务最多提交一次。排队任务可以取消；已送达服务端的请求等待结果。

- 设备重开应用时继续排队和未完成的下载。提交后响应丢失或进程中断时标为「结果待确认」，用户可查账后复用参数另开任务。
- 一组图片部分失败时保留成功作品及每张失败原因。方舟返回成功后即计算输出用量，下载故障不会被误当成零费用。下载失败可在临时 URL 有效期内单独补下载。
- 处理 HTTP 与顶层 JSON 错误、单图错误、密钥和权限问题、模型不可用、内容审核、余额或配额不足、IPM 限流、服务异常、超时与 TLS 连接错误。保留方舟原始错误码、请求 ID 与可用的 Retry-After 信息。
- 私有目录中的原图可离线查看。删除任务前会确认，同时删除该任务的私有原图。系统相册副本独立保存。卸载应用前可将需要的原图导出。
- API Key 由 Android Keystore 的 AES-GCM 密钥加密。密钥仅随发往方舟 API 的 HTTPS 请求发送。图片下载使用独立连接。正式构建禁用明文流量、应用备份和外部页面访问应用桥接接口。

## 构建与验证

用 Android Studio 打开本目录，安装 Android SDK Platform 35，采用 JDK 17 或 Android Studio 自带的兼容 JDK，构建 `app`。也可运行 `./gradlew assembleDebug`，Windows 使用 `gradlew.bat assembleDebug`。默认 Gradle 调试签名适用于本地开发。自行分发时请使用自己的 Android 签名配置。

`node --test tests/pricing.test.cjs` 运行计价边界与组图、图层、未知模型用例。运行 `./gradlew testDebugUnitTest` 可复测模型筛选、请求字段、HTTP 与单图错误、超时及下载凭证隔离。首次构建需要联网获取 Android Gradle Plugin 与 Gradle。

## 官方资料与示例照片

[模型列表 API](https://console.volcengine.com/ark/region:cn-beijing/docs/models-api) · [图片生成 API](https://console.volcengine.com/ark/region:cn-beijing/docs/image-generation-api) · [图片生成教程](https://console.volcengine.com/ark/region:cn-beijing/docs/image-generation-tutorial) · [模型价格](https://console.volcengine.com/ark/region:cn-beijing/docs/model-pricing) · [错误码](https://console.volcengine.com/ark/region:cn-beijing/docs/error-codes) · [API Key 管理](https://console.volcengine.com/ark/region:cn-beijing/docs/manage-api-keys)

灵感库内置摄影参考来自 Unsplash，提示词为本应用编写。[赤岩旷野](https://images.unsplash.com/photo-1509316785289-025f5b846b35) · [山谷来信](https://images.unsplash.com/photo-1464822759023-fed622ff2c3b) · [潮汐之间](https://images.unsplash.com/photo-1518837695005-2083093ee35b)

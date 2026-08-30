# 红果后台听（LSPosed）

一个仅作用于 **红果免费短剧**（`com.phoenix.read`）的 LSPosed 模块：播放短剧时按 Home 或锁屏，保留当前视频的音频播放。

## 使用

1. 安装 release APK。
2. 在 LSPosed 中启用“红果后台听”，作用域勾选“红果免费短剧”。
3. 强行停止并重新打开红果（或重启手机）。
4. 开始播放短剧后按 Home 或锁屏。

模块只在应用离开前台后的 4 秒内拦截播放器自动发出的 `pause`。保护窗口结束后，耳机、通知栏或应用自身发出的暂停操作可以正常工作。

### 1.0.1

- 修复部分 Android 15 系统按 Home 返回桌面时不触发 `performUserLeaving`、导致保护未开启的问题。

## 兼容性

- Android 7.0+；LSPosed API 93+。
- 目标包名：`com.phoenix.read`。
- 已针对红果 7.3.5.32 (73532) 的短剧播放器 `x05.w` 验证静态调用关系，并为 TTVideoEngine、Android MediaPlayer、Media3/ExoPlayer 标准实现提供兜底 Hook。
- 红果更新可能替换或混淆播放器。若失效，请在 LSPosed 日志中搜索 `HongguoBackgroundAudio`，并在反馈时附上红果版本号与相关日志。

本模块不修改账号、广告、付费或内容访问逻辑。

## 构建

```powershell
./gradlew.bat assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。当前项目为方便个人安装，Release 构建使用本机 Android debug 证书；正式公开发布时应替换为自己的长期签名证书。

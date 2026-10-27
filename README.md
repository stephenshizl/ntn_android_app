# NTN Terminal Android App

Android 10 及以上；通过经典蓝牙 SPP/CTRL2 与 MCU 通信，通过 BLE RCSP 查询 AC6323A 固件版本并升级蓝牙固件。

## 使用

- 在 Android 蓝牙设置中配对终端，回到 App 刷新并连接。首页自动查询蓝牙固件版本及设备地址；断开后清除旧版本。MCU 版本有独立查询按钮。
- “终端 / AT调试”页显示终端 GNSS 信息，提供 MCU 与 NTN 两个 AT 入口。AT 命令名称兼容大小写，参数保持原样；NTN 返回片段合并显示，并说明最终成功或错误原因。`AT+MCU...` 请使用 MCU 入口。
- “设置”页集中管理工作模式、UDP 服务器、蓝牙名称和 RS232 波特率。连接后从 MCU 读取持久配置；地址或远端端口未配置时留空。切换页面保留未保存输入，点击“读取终端设置”会重新读取设备值。
- 透传模式（0）将 RS232 AT 指令转发至 NTN。数据终端模式（1）将 RS232 数据打包为 UDP，经 NTN 发送。
- 服务器支持 IPv4 或 ASCII 域名，最长253字符，不含 URL 协议和端口。远端 UDP 端口为1–65535，本地端口为0–65535，0表示自动。保存时按顺序等待 MCU 确认，再回读核对；失败时提示部分配置可能已改变，不声称已全部保存。
- 无 SIM 卡时可测试基本 AT、配置读写、蓝牙与 MCU 通信；实际 NTN 注册和 UDP 收发需要相应网络条件。未接 GNSS 天线时可能无定位。

## 蓝牙 OTA

“读取蓝牙版本 / 升级固件”进入独立升级页面，扫描并连接目标 BLE 设备即可读取版本，读版本无需选择固件。升级只选择蓝牙 `.ufw` 文件，MCU 和 NTN 固件使用各自的升级流程。

设备需要预装支持 OTA 的蓝牙固件。本项目使用杰理官方 Android OTA SDK。单备份升级需保持电源、蓝牙和 App 运行；升级中断后可连接恢复 Loader，选择相同固件继续。App 同时处理普通应用和强制恢复 Loader 的鉴权回调。升级后重新连接读取实际版本。

App 版本功能不修改 MCU 固件，也不增加 MCU RAM。App OTA 与固件中 USB/强制下载的支持情况分别验证，不能互相替代。

## 验证

使用 JDK17、Android SDK36：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

单元测试覆盖 CTRL2、GNSS、OTA 文件与队列策略、AT 大小写和参数保留、地址与端口校验。`TerminalSettingsInstrumentedTest` 使用模拟发送器，验证保存等待确认、错误中止、请求ID匹配及回读核对，不会修改实际 MCU NV。

连接 Android 设备后，可通过 Gradle connectedDebugAndroidTest 执行仪器测试；离线环境缺少 UTP 主机插件时，也可安装两个 debug APK 后调用原生运行器：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.youyou.ntn_bt.test/androidx.test.runner.AndroidJUnitRunner
```

# UI screenshots

[English overview](../../README.md) · [中文概览](../../README.zh-CN.md)

| File | Screen | Source |
| --- | --- | --- |
| [welcome.png](welcome.png) | Client before connecting a library | Render |
| [library.png](library.png) | Sample library, one app installed, status toast | Render |
| [running.png](running.png) | Running-app card on the silver backdrop | Render |
| [appearance.png](appearance.png) | 外观 sheet: backdrop, dimming, accent color | Render |
| [counter.png](counter.png) | Counter module from the earlier template validation run | Emulator capture |

The renders show the redesigned client at 720 × 1280 (360 × 640 dp at 2×). They
were drawn from the client's own layout values, colors and backdrop images with
an HTML replica, because no Android emulator was available when the UI changed.
They are not device captures, and small details such as font rendering, system
bars and the dialog position can differ on a phone. The library name, host and
the second app are sample data.

`counter.png` is an unedited `adb exec-out screencap -p` capture from an Android
14 / x86_64 emulator at 720 × 1280.

To replace the renders with device captures, use a clean client on a 720 × 1280
emulator, connect a demo catalog, and capture each screen after loading
finishes. Check the images before committing.

渲染图按客户端实际的布局数值、配色和背景图绘制，并非真机截图，字体、系统栏和弹窗位置等细节可能与手机上略有不同。计数器画面为 Android 14 模拟器实拍。

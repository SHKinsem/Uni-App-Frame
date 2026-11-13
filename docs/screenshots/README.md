# UI screenshots

[English overview](../../README.md) · [中文概览](../../README.zh-CN.md)

These are unedited Android screenshots, captured with `adb exec-out screencap -p`
on an Android 14 / x86_64 emulator at 720 × 1280.

| File | Screen |
| --- | --- |
| [welcome.png](welcome.png) | 0.4.0 client before connecting a library |
| [library.png](library.png) | 0.4.0 client with a demo catalog containing the Flutter counter template |
| [counter.png](counter.png) | Counter module from the earlier template validation run |

The library uses a local HTTPS test server. Its address is for the emulator,
not a public app source. The counter is the standard Flutter starter example.

To refresh the screenshots, use a separate Android user with a clean client,
connect a demo catalog and open the template module. Keep the same screen size
and capture each screen after loading finishes. Check the images before committing.

前两张是 0.4.0 客户端的新截图；计数器画面来自之前的模板验证记录，均为 Android 14
模拟器上的原始截图。图中的地址属于模拟器测试环境，不能作为公开应用库使用。

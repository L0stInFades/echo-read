# baselineprofile 模块

Baseline Profile 生成器 + Macrobenchmark（启动耗时、阅读页翻页帧耗时）。需要连接真机或模拟器，通过 `ANDROID_SERIAL` 指定设备。

## 生成 Baseline Profile

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateReleaseBaselineProfile
```

结果写入 `app/src/release/generated/baselineProfiles/`（`saveInSrc = true`），随 release 包一起打进 APK；发布前请提交该目录。

## 运行 Macrobenchmark

```sh
ANDROID_SERIAL=70976d ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

结果位于 `baselineprofile/build/outputs/connected_android_test_additional_output/…/*.json`。

- `StartupBenchmark`：冷启动，`startupNone` vs `startupBaselineProfile` 对比 profile 收益。
- `ReaderScrollBenchmark`：打开示例书后翻页 8 次的 `FrameTimingMetric`。

## 依赖的 testTag

`shelf.sample`、`shelf.book`、`reader.page`、`reader.back`（app 开启 `testTagsAsResourceId`，用 `By.res(tag)` 匹配）。

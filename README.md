# waaous-addon

Meteor Client（彗星）附加模块 —— Fabric 客户端 mod，目标 **Minecraft 1.21.11**（Yarn 映射）。

包含三个模块：

| 模块 | 类 | 作用 |
|---|---|---|
| SmartMiner | `com.waaous.addon.modules.SmartMiner` | 自动挖矿：环形扫描目标矿 → Baritone 寻路 → 自动换镐 → 安全挖掘 → 拾取掉落物 |
| QQGroupDetector | `com.waaous.addon.modules.QQGroupDetector` | 从聊天栏（含私信）与 boss 条提取 5–10 位 QQ 群号，去重写入 `QQnumber.txt` |
| RallyFollow | `com.waaous.addon.modules.RallyFollow` | 挂电集结跟随：公开聊天出现关键词（默认「挂电集结」）时自动回复（默认「谁可思谁闻」，可配置） |

入口：`com.waaous.addon.WaaousAddon`（`MeteorAddon`，`fabric.mod.json` 的 `meteor` entrypoint），并注册自定义分类 `waaous-addon`。

## 构建方案（离线优先）

- **插件**：`net.fabricmc.fabric-loom-remap` 1.17.19 —— 只做重映射（把 intermediate jar 重映射回 Yarn），不解 Minecraft 本体，无需 `runClient`。
- **依赖全部本地 vendored**（`libs/`）：`meteor-client-1.21.11-86.jar`、`baritone-1.21.11.jar`、`orbit-0.2.4.jar`。
- **离线 Gradle**：`gradle-dist/gradle-9.5.1/`（完整发行版，已随工程复制）。
- **离线 JDK**：`gradle.properties` 里 `org.gradle.java.installations.paths=E:/jdk-21.0.4`。
- **本地 Maven 仓库**：`maven-local/`（yarn 1.21.11+build.6 / lorenz-tiny 4.0.2 / mercury 0.4.3）。

## 编译

Windows 双击 [`build.bat`](build.bat)（内部指向本地 Gradle + JDK 21）：

```bat
build.bat
```

或手动：

```bat
gradlew.bat build
```

成品：`build/libs/waaous-addon-0.2.0.jar`（已构建成品在 [`artifacts/`](artifacts)）。

> 首次构建若 `fabric-loom-remap` 插件尚未在本机 `~/.gradle` 缓存，会联网解析一次（走 `maven.aliyun.com` / `bmclapi` / `maven.fabricmc.net`）；之后即离线。

## 目录结构

```
waaous-addon/
├── build.gradle / settings.gradle / gradle.properties   # 构建脚本
├── gradlew / gradlew.bat / gradle/wrapper/              # Gradle wrapper（9.5.1）
├── build.bat                                            # 一键离线编译
├── src/main/java/com/waaous/addon/                      # 当前源码（第 4 代）
│   ├── WaaousAddon.java
│   └── modules/{SmartMiner,QQGroupDetector,RallyFollow}.java
├── src/main/resources/fabric.mod.json
├── libs/                 # 本地 vendored 依赖（intermediate jar）
├── maven-local/          # 本地 Maven 仓库（loom-remap 三依赖）
├── gradle-dist/          # 离线 Gradle 9.5.1 发行版
├── artifacts/            # 编译成品（含第 2 代 dev jar，见子目录 dev/）
├── archive/              # 历史各时期快照（见 archive/README.md）
└── tooling/              # 构建辅助（cache-warm、download-yarn、mercury 探针等）
```

## 历史各时期

四代演进已归档在 [`archive/`](archive)，详见 [`archive/README.md`](archive/README.md)。

# 发布到 Steam 创意工坊

Necesse 没有独立的上传工具，上传在**游戏内**完成：用开发模式启动游戏，然后在「Mod」页面对开发模组点「上传到 Steam 创意工坊」。流程依据反编译的 `SteamDevModProvider` 和 `LoadedDevMod`（游戏 1.3.3）整理，与 Coop Pause 相同。

## 材料清单

| 文件 | 用途 |
|---|---|
| `src/main/resources/preview.png` | 640×640 预览图，打进 jar 的 `resources/preview.png`，上传时自动作为创意工坊封面。**jar 里没有这张图时，游戏不显示上传按钮**（`SteamDevModProvider` 中 `mod.preview != null` 是显示条件） |
| `docs/workshop/description.bbcode` | 创意工坊描述（Steam BBCode，中英双语，约 2700 字符；游戏上传框上限 5000 字符） |
| `docs/workshop/images/00-key-art.png` | 主视觉图（Codex 生成后叠加标题），上传后在 Steam 网页上作为第一张附加图片 |
| `docs/workshop/images/01-live.png` 等 3 张 | 1280×720 功能介绍图，在 Steam 网页上作为附加图片 |
| `tools/make_workshop_images.py` | 重新生成预览图、功能卡片，以及带标题的主视觉图：`python tools/make_workshop_images.py .`（需要 Pillow 和 Windows 自带字体） |

> 功能卡片是示意图，不是游戏截图（标记卡片里的勾选框是抽象画的）。建议发布前在游戏里补几张真实截图：两人同队时地图同步的效果、标记编辑框里的「共享给队伍」勾选框。

## 首次发布

1. **构建**（Gradle 9 需要 JDK 17 以上）：
   ```bash
   JAVA_HOME=/g/scoop/apps/temurin17-jdk/current ./gradlew buildModJar
   ```
   确认 `build/jar/` 里**只有一个 jar**。游戏要求开发模组目录中除 `preview.png` 外只能有一个 jar 文件、不能有子目录（`validateDevFolderAndReturnJar`）。`buildModJar` 每次会清掉旧文件，正常情况下满足要求。

2. **以开发模式启动游戏**（Steam 必须正在运行）：
   ```bash
   JAVA_HOME=/g/scoop/apps/temurin17-jdk/current ./gradlew runClient
   ```
   等价于 `-dev -mod "build/jar/"`。从 Steam 直接启动不会带 `-mod`，Mod 列表里就看不到它。

3. 主菜单进入 **Mod**，选中 **Team Map Sync**（显示「从开发文件夹加载」），点 **「上传到 Steam 创意工坊」**。

4. 在弹出的窗口中：
   - 选择 **「创建新项目」**。
   - 勾选「更新描述」，粘贴 `description.bbcode` 的内容。不勾选的话，游戏默认填 `mod.info` 里的英文简短描述。
   - 标签建议勾选 **New features**，可以再加 **Interface**（标记编辑框加了勾选框）。
   - 点「继续」，同意 Steam 创意工坊服务条款，确认内容是你自己创作的。

5. 看到「成功地创建/更新了模组」后，到 Steam 网页上完成其余设置（Steam 客户端 → 社区 → 创意工坊 → 你的文件）：
   - **必需物品**：无。本 Mod 不依赖其他 Mod。
   - **图片和视频**：上传 `docs/workshop/images/` 下的 `00-key-art.png`、`01-live.png`、`02-catchup.png`、`03-markers.png`，以及你的游戏截图。
   - **可见性**：确认设为「公开」（或先设为「仅好友」测试）。
   - 新上传的物品需要接受过 Steam 创意工坊法律协议，否则只有你自己能看到。

6. 记下物品 ID（网址中的 `?id=...`），补到 README 里。

## 发布更新

1. 在 `build.gradle` 中提高 `modVersion`，例如 `1.0` → `1.1`。版本号变化后，服务端和客户端的 Mod 一致性检查会要求所有玩家都更新。
2. `./gradlew buildModJar`，然后 `./gradlew runClient`。
3. Mod → Team Map Sync → 上传 → 在下拉框中**选择已有的项目**。
4. 「更新描述」：如果你在 Steam 网页上改过描述，**取消勾选**，否则会被覆盖。
5. 游戏会自动把更新说明写成 `Mod version <版本> for game version <游戏版本>`，更详细的说明可以在 Steam 网页的「更新日志」里编辑。

## 首个版本的更新日志参考

```
1.0
- Teammates share map exploration live; terrain changes they see are synced too
- The server keeps a merged map per team and fills it in when you join
- Exploration made before joining a team or installing the mod is shared as well
- "Share with team" checkbox for map markers; edits and deletions sync
- Server only relays compressed data; heavy work runs on background threads
```

## 发布前自检

- [ ] 两人同队实测（Host World 和独立服务器各一次）：实时同步、一人离线期间另一人探索后再上线、在另一层（洞穴）探索后回到地表
- [ ] 标记：勾选共享、改名改图标、删除、取消勾选；换队后旧队伍标记消失
- [ ] 没有队伍的玩家不受影响
- [ ] 独立服务器（`StartServer-nogui.bat` 或 `./gradlew runServer`）能正常加载并在 `stop` 时正常保存
- [ ] `build/jar/` 中只有一个 jar，jar 内包含 `resources/preview.png` 和 `mod.info`

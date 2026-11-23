# Fake Player Lib

独立实现的**假人（fake player）库**，给 HBNS 服务器用：不依赖 Carpet，也不改原版行为。
用无网络连接的 `ServerPlayer` 实例做区块加载、小号占位、limbo 占位等。

## 命令

```
/fakeplayerlib status
/fakeplayerlib server on|off
/fakeplayerlib distance view <2-32> | simulation <2-32> | show
/fakeplayerlib client gui on|off
/fakeplayerlib behavior list | enable <id> | disable <id>
/fakeplayerlib list | spawn <name> | remove <name>
/fakeplayerlib config save | reload
```

已知行为 id：`idle`、`look_at_players`、`anti_afk`、`chunk_loader`。

## 设置

存在存档目录下 `config/fakeplayerlib/settings.json`：

| 字段 | 含义 |
|---|---|
| `serverEnabled` | 假人功能总开关 |
| `clientGuiEnabled` | 客户端右键假人是否打开面板 |
| `viewDistance` | 假人视距（区块） |
| `simulationDistance` | 假人模拟距离（区块） |
| `behaviors` | 启用的行为 id 集合 |

改完命令即时生效，`config save` 落盘。

## 数据迁移

旧版（hibercarpet）的假人列表在 `config/hibercarpet/fakes.json`，首次加载会自动迁移到
`config/fakeplayerlib/fakes.json` 并删除旧文件。

## 构建

```
gradlew build -x test --offline
```

产物：`build/libs/fakeplayerlib-1.0.0.jar`

测试客户端（干净环境，`run/mods` 不放任何模组，本模组通过 dev modFolders 注入）：

```bat
run-test.bat                     :: 直接启动
run-test.bat build install       :: 构建并部署到 run\mods 后启动
```

## 许可

All Rights Reserved

# HEOS

HEOS 是为 HE 服务器维护的一组 Minecraft 服务端功能集合。它同时提供 Fabric 模组和 Folia 插件版本，几乎所有设置都可以在配置文件里面进行开关。

## 支持范围

- Minecraft：`1.20` 到 `26.1.2`
- 加载器：Fabric、Folia

## 功能

### 登录认证

- `/register <password> <confirmPassword>` 注册离线账号密码。
- `/login <password>` 登录账号。
- `/changepassword <oldPassword> <newPassword>` 修改密码。
- 支持正版玩家和离线玩家分离，离线玩家需要名称中包含.+-才可以进入。
- 支持登录超时、登录提醒、未登录玩家交互限制。
- 支持按用户名或 IP 记录连续登录失败，并在失败过多时临时锁定。
- 支持限制同一 IP 同时在线人数。

### 管理命令

- `/heos resetpassword <player> <newPassword>` 重置玩家密码。
- `/heos info <player>` 查看玩家注册信息、UUID、最后登录时间和账号类型。
- `/heos whitelist add <player>` 添加 HEOS 白名单。
- `/heos whitelist remove <player>` 移除 HEOS 白名单。
- `/heos whitelist list` 查看 HEOS 白名单。
- `/heos migrate <sourcePlayer> <targetPlayer>` 迁移玩家数据。
- `/heos reload` 重新加载 Folia 配置。

Fabric 端管理命令默认需要 3 级权限；Folia 端使用 `heos.admin` 权限。

### 自定义封禁

- `/ban <player> [time] [reason]` 封禁玩家。
- `/ban-ip <ip> [time] [reason]` 封禁 IP。
- `/unban <player|ip>` 解封玩家或 IP。
- `/unban-ip <ip>` 解封 IP。
- `/banlist` 查看封禁列表。

时间格式支持 `15s`、`3m`、`24h`、`7d`、`1y`，也可以使用 `-1` 表示永久。

### 白名单

HEOS 内置一套独立白名单，可以和原版白名单配合使用。开启后，玩家只要在原版白名单或 HEOS 白名单中任意一处存在，就允许进入。

### 玩家数据迁移

数据迁移用于把一个玩家的 HEOS 数据迁移到另一个玩家名下，适合离线账号改名、正版/离线账号合并等场景。迁移期间会临时封禁源账号，避免迁移过程中继续登录写入旧数据。

### TPS / MSPT 显示

HEOS 会统计服务器 TPS 和 MSPT，并在玩家列表页脚显示。显示格式兼容常见的 Carpet / MiniHUD 习惯。

### 配方查看器同步

在支持的版本上，HEOS 会同步服务端配方信息，改善配方查看器在服务端环境中的可用性。

### 日志优化

HEOS 会压缩或过滤部分刷屏日志，让登录、白名单、封禁和服务端状态信息更容易读。

### Ghost Pearl 修复

Fabric 端包含末影珍珠重复保存/重复注册相关修复，避免玩家管理的珍珠在区块和玩家数据之间产生幽灵引用。

## 配置

Fabric 配置位于 HEOS 生成的配置文件中；Folia 配置模板位于：

```text
folia/src/main/resources/config.yml
```

常用配置项包括：

- `enableAuthentication`：启用登录/注册认证。
- `allowOfflinePlayers`：允许离线玩家进入。
- `separateOnlineOfflineAccounts`：分离正版和离线账号数据。
- `enableUnprefixedCommandHijack`：拦截无命名空间的登录/注册等命令。
- `loginTimeout`：登录超时时间。
- `enableAutoLogTps`：启用 TPS 页脚显示。
- `enableRecipeViewerSync`：启用配方查看器同步。
- `enableWhitelist`：启用 HEOS 白名单。
- `enableCustomBan`：启用 HEOS 自定义封禁系统。
- `enablePlayerDataMigration`：启用玩家数据迁移。
- `maxConcurrentSessionsPerIp`：限制同一 IP 同时在线人数。

## 构建

需要 JDK 21；构建 `26.x` 目标时需要 JDK 25。

构建全部版本：

```powershell
.\gradlew.bat build
```

只构建 Folia：

```powershell
.\gradlew.bat :folia:build
```

构建指定 Fabric 版本：

```powershell
.\gradlew.bat :1.21.11:build
```

构建指定 Folia 版本：

```powershell
.\gradlew.bat :folia:1.21.11:shadowJar
```

发布用 jar 会收集到：

```text
build/release-jars
```

## 许可证

MIT

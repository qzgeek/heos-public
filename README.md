# LuoOS Client and OneBot Server

LuoOS is a comprehensive Folia (Minecraft server) plugin combining:
- **Player authentication** (login/register, AuthMe migration)
- **Account binding** (star-topology UUID remapping at Netty layer)
- **Embedded QQ bot** (OneBot v11 WebSocket — whitelist, status card, admin)
- **Unified SQLite/MySQL storage**

## Features

### Player Auth
- `/los login/register/changepassword` — server-side authentication
- AuthMe password migration with auto-detected backend
- Multi-format password verification (SHA256, BCrypt, PBKDF2)

### Account Binding
- Star topology: many bound accounts → one target
- UUID remapping at Netty packet level for seamless integration
- Chat TUI + Chest GUI for binding management

### QQ Bot (OneBot v11)
- `白名单 <ID>` — apply for whitelist (auto-binds QQ to MC account)
- `删除 <ID>` — remove own whitelist
- `查询白名单 @QQ` — admin query
- `服务器还活着吗` — server status card (image)
- `封禁/解禁 @QQ` — admin ban/unban
- `help` — command help

### Database
- SQLite (default) or MySQL
- Shared tables between plugin and bot
- AuthMe → LuoOS migration

## Build

```bash
./gradlew :folia:1.21.11:shadowJar --no-daemon
```

JDK 21 required.

## Configuration

See `src/main/resources/config.yml` for all options.

## License

MIT

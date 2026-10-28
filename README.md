# VoiceChatHandshake (ZenithProxy plugin)

Answers Simple Voice Chat's `force_voice_chat` login check so the proxy isn't kicked after `login_timeout`.

On join it sends `minecraft:register` (voicechat:secret) + `voicechat:request_secret` with the SVC
compatibility version, and confirms success when the server replies with `voicechat:secret`.
If no reply arrives it tries the next version (20 → 18 → 19) and saves the one that worked.

The proxy itself doesn't talk voice. When you play through the proxy with Simple Voice Chat installed,
your client gets the server's `voicechat:secret` and tries to open UDP to the address it connected to,
which is the proxy. The plugin rewrites that packet so voice works:

- `relay` (default): voice UDP goes through the proxy on `relayPort` (24454). Open/forward that UDP port
  on the proxy host (and in Docker: `-p 24454:24454/udp`). The server only ever sees the proxy's IP, and
  reconnecting to the proxy keeps working because the server always sees the same source address.
- `direct`: your client connects straight to the server's voice port. No port to open, but the server sees
  your real IP, and after reconnecting it can take a bit until the server drops the old voice connection.

## Build
1. Set `mc` in `gradle.properties` to your ZenithProxy MC version (check with the `status` command or your launcher config).
2. `./gradlew build` → jar in `build/libs/`
3. Put the jar in the `plugins/` folder next to the Zenith launcher, restart. Requires the `java` release channel.

## Commands
- `voiceChatHandshake on/off` (alias `svc`)
- `voiceChatHandshake version <n>`: 18 = SVC 2.5.x, 20 = SVC 2.6.x
- `voiceChatHandshake autoProbe on/off`
- `voiceChatHandshake timeout <ms>`: wait per probe (default 2000)
- `voiceChatHandshake voice off/direct/relay`
- `voiceChatHandshake relayPort <port>`: UDP port the relay listens on
- `voiceChatHandshake relayHost <host>` / `relayHost clear`: address players use for the relay if it differs
  from the one they connect to the proxy with (e.g. a different domain or IP)
- `voiceChatHandshake spectatorVoice on/off`: spectators get voice too, speaking and hearing as the proxy account.
  Only one client can hold the account's voice at a time, so a player and a spectator will fight over it.

## AI Disclosure
Most of this project is made with the help of an LLM. I read over the code fully and deemed it was fair enough and works well.  
I just needed something to works now. Whole project took maybe 30 mins.
# ZenithVoice (ZenithProxy plugin)

Simple Voice Chat through ZenithProxy: keeps the proxy from getting kicked on `force_voice_chat` servers,
and gets voice chat working for the controlling player and spectators.

## Login check

SVC's `force_voice_chat` kicks players who don't answer its handshake within `login_timeout`.

On join it sends `minecraft:register` (voicechat:secret) + `voicechat:request_secret` with the SVC
compatibility version, and confirms success when the server replies with `voicechat:secret`.
If no reply arrives it tries the next version (20 → 18 → 19) and saves the one that worked.

## Voice through the proxy

The proxy itself doesn't talk voice. When you play through the proxy with Simple Voice Chat installed,
your client gets the server's `voicechat:secret` and tries to open UDP to the address it connected to,
which is the proxy. The plugin rewrites that packet so voice works:

- `relay` (default): voice UDP goes through the proxy on `relayPort` (24454). Open/forward that UDP port
  on the proxy host (and in Docker: `-p 24454:24454/udp`). The server only ever sees the proxy's IP, and
  reconnecting to the proxy keeps working because the server always sees the same source address.
  Everyone on the proxy (player and spectators) hears voice chat. Only one of them talks: the controlling
  player, or if they aren't in voice or have voice chat disabled, the spectator who joined voice first.
  A mic mute (as opposed to disabling voice chat) never leaves the client, so a controlling player who
  only muted their mic still keeps the talking slot.
- `direct`: your client connects straight to the server's voice port. No port to open, but the server sees
  your real IP. Only one client can have voice: the controlling player, or a spectator who is alone on the
  proxy. When that changes the old one is disconnected from voice; the new one connects once the server
  drops the old connection (~10s).

## Install
Download the jar matching your ZenithProxy MC version (check with the `status` command) from the releases,
e.g. `ZenithVoice-1.0.0+26.3.0.jar`. Put it in the `plugins/` folder next to the Zenith launcher and restart.
Requires the `java` release channel. 1.21.4 builds run on Java 21, 26.x builds need Java 25.

## Build
`./gradlew build -Pmc=26.3.0` → `build/libs/ZenithVoice-<version>+<mc>.jar`. Without `-Pmc` it uses `mc` from
`gradle.properties`. Supported versions are listed in `mc_versions`; `zenith_version` picks the ZenithProxy release
to compile against (`snapshot` for the latest `<mc>-SNAPSHOT`).

## CI
GitHub Actions (`.github/workflows/build.yml`) builds a jar for every version in `mc_versions` on each push and
pull request; the jars are attached to the run. Pushing a semver tag like `1.0.0` (no `v`) also creates a draft
GitHub release with all jars, to publish by hand. The tag has to match `plugin_version` in `gradle.properties`,
otherwise the release step fails.

## Commands
- `zenithVoice on/off` (alias `zv`)
- `zenithVoice version <n>`: 18 = SVC 2.5.x, 20 = SVC 2.6.x
- `zenithVoice autoProbe on/off`
- `zenithVoice timeout <ms>`: wait per probe (default 2000)
- `zenithVoice voice off/direct/relay`
- `zenithVoice relayPort <port>`: UDP port the relay listens on
- `zenithVoice relayHost <host>` / `relayHost clear`: address players use for the relay if it differs
  from the one they connect to the proxy with (e.g. a different domain or IP)
- `zenithVoice spectatorVoice on/off` (default on): spectators get voice too, as the proxy account
  (see the modes above).
- `zenithVoice keepListening on/off` (relay, default off): spectators keep hearing while the controlling player has voice
  chat disabled; the server only sees "disabled" once everyone has it off. Off = plain SVC behaviour, the
  controlling player disabling voice cuts it for everyone, and spectators can't talk while it's disabled either.

## AI Disclosure
Most of this project is made with the help of an LLM. I read over the code fully and deemed it was fair enough and works well.  
I just needed something to works now. Whole project took maybe 30 mins.
# SongHUD (Minecraft 1.21.11, Fabric)
Synced lyrics: HUD / floating in the air / follow. Pure Java (works in Zalith/Pojav).
Spotify auto-sync (no typing song names): menu -> "Spotify auto-sync...".

Menu: press Right Shift (rebind in Controls; map it to an on-screen button in Zalith) or /lyrics menu.

Spotify setup:
1. https://developer.spotify.com/dashboard -> Create app, Web API.
2. Redirect URI exactly: http://127.0.0.1:8789/callback
3. Copy Client ID into the menu -> Connect -> open the copied link in a browser, log in.
4. If the page does not load after login, copy the address from the browser and paste it into the menu -> Finish login.

Commands: /lyrics menu | play <artist - title> | pause | resume | stop | here
 | mode hud|air|follow | seek <s> | offset <ms> | scale <x> | dist <blocks> | spotify on|off
Build: push to GitHub -> Actions -> build -> download artifact songhud-jar.

# Automated Delivery Bot (`delivery-bot`) — Minecraft 26.2 (Fabric)

A client-side **Minecraft 26.2 Fabric** automation mod with **Baritone API** integration that executes the complete server delivery workflow end-to-end:

1. **Pickup & Hardest Job Selection**: Teleports via `/home lambanh`, opens `/giaohang` (`ĐIỆN THOẠI GIAO HÀNG`), prioritizes the hardest & highest-reward job (`Siêu khó > Khó > Thường > Dễ`), and collects the delivery package from `TIỆM BÁNH`.
2. **Smart Nether & Multi-Home Routing**:
   - **Nether Mode**: Teleports to `/home nether` and navigates to `(X / 8, Z / 8)`.
   - **Multi-Home Mode**: Selects the nearest hub among `/home h1`, `/home h2`, `/home h3`, and `/home h4` using saved/calculated Nether centroids (baseline: `H1:(-416,-829)`, `H2:(954,-123)`, `H3:(-938,263)`, `H4:(475,920)`), builds & lights an Obsidian Nether Portal, and returns to the Overworld.
3. **Baritone Automatic Navigation**: Travels to the customer's exact coordinates with automatic block breaking (`allowBreak = true`), block placement (`allowPlace = true`), auto-tool selection, and sprinting, then delivers the package and loops continuously.
4. **HUD Home Automation & Movable Telemetry**:
   - Freely draggable HUD displaying live status, total `xu` earned, active runtime, average `xu/min`, and `H1..H4` coordinates.
   - Clicking `H1`, `H2`, `H3`, or `H4` in the HUD (or Control Dashboard) automatically runs `/home nether` → Baritone navigation → `/sethome h1..h4`.
   - Includes a `Calc: ON/OFF` toggle to continuously recalculate or lock saved home coordinates.
5. **Automatic Kick Recovery**: Automatically reconnects after server kicks, executes `/giaohang huy` → `/giaohang huy xacnhan`, and resumes the delivery loop.

## Default Keybindings

| Key | Action |
| :--- | :--- |
| **`R`** | Start / Stop Delivery Automation |
| **`P`** | Pause / Resume Delivery Automation |
| **`O`** | Open Control Dashboard (`DeliveryControlScreen`) |

*(Open Chat **`T`** or Inventory **`E`** to unlock the cursor and drag the HUD or click **`H1`–`H4`** / **`Calc: ON/OFF`** directly.)*

## Building the Mod

Requires **Java 25** (as required by Minecraft 26.2).

```powershell
.\gradlew.bat build
```

The compiled mod JAR is generated at `build/libs/delivery-bot-1.0.0.jar`.

## Installing into Minecraft Fabric 26.2

1. Install **Fabric Loader `>= 0.20.1`** for **Minecraft `26.2`**.
2. Place **Fabric API (`0.161.0+26.2`+)**, **Baritone (`1.19.0`+)**, and `build/libs/delivery-bot-1.0.0.jar` into your `.minecraft/mods` folder.
3. Launch Minecraft 26.2, join the server, and press **`R`** to start or **`O`** to open the Control Dashboard.
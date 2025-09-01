# Fixed Resizable Hybrid

**Fixed Resizable Hybrid** is a RuneLite plugin that reskins the "Resizable - Classic Layout" by styling it to match the aesthetics of Fixed Mode. It provides a faithful representation of the fixed mode interface, while preserving the benefits of resizable mode, such as a larger game viewport and minimizable chat.

![Example Image](docs/img/exampleuse.png)

---
## Using The Plugin
- Install and enable the plugin through the RuneLite community hub.
- Ensure your game client layout is **Resizable - Classic Layout**
  - Access this in the **Ingame Settings** > **Display Settings Tab** > **Game Client Layout**

### Features
- **Fixed Mode Aesthetic**:
  - Aligns the UI elements (e.g., minimap, inventory, orbs) to mimic the layout of Fixed Mode.
  - Replaces backgrounds and sprites to match the classic Fixed Mode design.
- **Automatic Aspect Ratio Resizing**
  - You can always just drag corner(s) of runelite to resize to whatever dimensions you'd like, but there are options for automatic scalings to specific aspect ratios (e.g. 16:9 or 21:9)
    - Useful for getting achieving specific dimensions for things like streaming or recording.
  - Automatically calculates dimensions based on the current client height or width (configurable).
---
### Recommended Settings
#### Inventory Transparency
- Ensure when you enable this plugin, you have inventory transparency in the ingame settings set to OFF. 
  - This will prevent the background on your inventory from changing intermittently and make it identical to fixed mode

#### Scaling Settings / Preventing Gaps
- If you want to increase the height of the window, _gaps_ will be created between the minimap and the inventory. There is a default, solid-color background for this gap that is customizable in the plugin's settings.
- Stretched Mode Plugin
    - Option if you want **no gaps + larger window height**
    - **Upscaling the GUI will fill the gaps**: use 100% on the scaling option in the stretched mode plugin. This prevents any gaps from separating the inventory from the minimap
        - Some people like keeping those gaps to put overlays inside, it's up to personal preference
    - To prevent **scaling artifacts** in Streched Mode: Use the **GPU plugin** with UI scaling setting set to "xBR"
        - This might not be everybody's cup of tea, but it helps mask any scaling artifacts when not in integer scaling mode
---
## Future Plans
- Improve functionality within cutscenes
  - This is a known issue. Some newer quests have cutscenes that might interfere with the plugin. It is actively being addressed, as each quest has slightly different under-the-hood triggers that haven't all been caught
---
## Known Issues
- In their default positions, the orbs around the minimap overlap with the minimap itself, and there are a few pixels in which you click both the orb (e.g. run orb) and on the minimap with one click. 
  - By default, the orbs are in the same position as seen in fixed mode. However, the "Orb Positioning" setting in this plugin's configuration will allow you to remedy this by moving the orbs slightly further away.
- Issues have been reported regarding concurrent use with the _chat resizing plugin_.
- A few resource packs/interface styles aren't applied properly
  - Most will work with this plugin, but the ones that change the dimensions of the inventory sprites have issues.
---
## Contact
Feel free to leave issues or feature requests to the [GitHub Project](https://github.com/Lapask/fixed-resizable-hybrid). Ill do my best to take a look at them.
You can also contact me via Discord (ID: Lapask#7584).
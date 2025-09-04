package com.lapask;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.lapask.config.OrbsPosition;
import com.lapask.config.ResizeBy;
import java.awt.image.BufferedImage;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.SpriteID;
import net.runelite.api.SpritePixels;
import net.runelite.api.Varbits;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import java.awt.*;
import java.util.*;
import java.util.List;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;


@Slf4j
@PluginDescriptor(
	name = "Fixed Resizable Hybrid",
	description = "Skins the \"Resizable - Classic Layout\" to match fixed mode.",
	tags = {"resize", "resizable", "classic", "fixed", "widescreen", "legacy", "hybrid"}
)
public class FixedResizableHybridPlugin extends Plugin
{

	@Inject
	private Client client;

	@Inject
	private FixedResizableHybridConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FixedResizableHybridOverlay enabledOverlays;

	private boolean resizeOnGameTick = false;
	private boolean widgetsModified = false;
	private final HashMap<Integer, WidgetState> originalStates = new HashMap<>();
	private boolean widgetWithBackgroundLoaded = false;
	private static final Set<String> onConfigChangedTriggerPlugins = Set.of("fixedresizablehybrid", "interfaceStyles", "runelite", "resourcepacks");
	private final BufferedImage defaultChatboxBufferedImage = ImageUtil.loadImageResource(getClass(), "/chatbox.png");
	private boolean cutSceneActive = false;
	private boolean transparentChatbox = false;
	private int wideChatViewportOffset = 23; //height of the buttons at the bottom of the chatbox
	private List<Integer> widgetsToFixBeforeRender = new ArrayList<Integer>();
	private static final Set<Integer> WIDGETS_WITH_BACKGROUNDS = Set.of(
		398, // Fairy ring
		416,  // Canoe interface (choose canoe)
		647,  // Canoe interface (choose destination)
		224   // Boat travelling (e.g., to Neitiznot)
	);

	//CreateNewWidget Column Headers
	private static final int C_PARENT_ID = 0;
	private static final int C_TYPE     = 1;
	private static final int C_SPRITE   = 2;
	private static final int C_X        = 3;
	private static final int C_Y        = 4;
	private static final int C_W        = 5;
	private static final int C_H        = 6;
	private static final int C_XMODE    = 7;
	private static final int C_YMODE    = 8;
	private static final int C_WMODE    = 9;
	private static final int C_HMODE    = 10;
	private static final int C_NOCLICK  = 11;
	private static final int C_TILE     = 12;

	@Provides
	FixedResizableHybridConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FixedResizableHybridConfig.class);

	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Fixed Hybrid Plugin started!");
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			queuePluginInitialization();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Fixed Resizable Hybrid Plugin stopped!");
		resetWidgets();
	}

	//Tried to avoid using onBeforeRender as much as possible, but there are some minigame widgets that get adjusted seemingly without a script
	//attached, so I used this to modify those widgets prior to render
	@Subscribe
	public void onBeforeRender(final BeforeRender event)
	{
		if (!widgetsModified)
		{
			return;
		}
		//Needs to occur every frame to ensure interface dimensions are set
		fixIngameOverlayWidgets();

		//widgetsToFixBeforeRender contains the list of ids need to be processed (see specific UI groups in onWidget(Un)load)
		//prevents widget/UI flickers when widgets are loaded and resized/centered to the viewport
		//clears list after so it's run as little as possible
		if (!widgetsToFixBeforeRender.isEmpty())
		{
			log.debug("widgetsToFixBeforeRender being processed");
			for (Integer identifier : widgetsToFixBeforeRender)
			{
				switch (identifier)
				{
					case InterfaceID.SkillGuide.UNIVERSE:
						fixStatsGuide();
						break;
					case 398: // Fairy Ring
					case 416:
					case 647:
						fixWidgetBackground();
						break;
					default:
						break;
				}
			}
			widgetsToFixBeforeRender.clear();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		String group = event.getGroup();
		String key = event.getKey();
		if (!onConfigChangedTriggerPlugins.contains(group))
		{
			return;
		}

		// If it's the "runelite" group, only handle "interfacestylesplugin" key
		if ("runelite".equals(group) && !"interfacestylesplugin".equals(key))
		{
			return;
		}
		if ("fixedresizablehybrid".equals(group))
		{
			clientThread.invoke(() ->
			{
				if ("aspectRatioResize".equals(key) && config.aspectRatioResize())
				{
					resizeByAspectRatio();
				}
				else if ("chatboxViewportCentering".equals(key) && transparentChatbox)
				{
					configManager.setConfiguration("fixedresizablehybrid", "chatboxViewportCentering", false);
				}
				else
				{
					resetWidgets();
					queuePluginInitialization();
				}
			});
		}
		else
		{
			clientThread.invoke(() ->
			{
				resetWidgets();
				queuePluginInitialization();
			});
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		int scriptId = event.getScriptId();
		switch (scriptId)
		{
			case 909: // Interface boxes recalculated (e.g., bank inventory, settings panel, etc)
				//log.debug("script 909: fixInterfaceDimensions()");
				fixInterfaceDimensions();
				break;
			case 654: // Stats guide widget opened (osb>214.0>214.1)
				widgetsToFixBeforeRender.add(InterfaceID.SkillGuide.UNIVERSE);
				break;
			case 904: // Window resized
				if (widgetsModified && config.isWideChatbox() && getGameClientLayout() == 2)
				{
					//log.debug("script 904: widenChat() for window resize");
					chatboxChanged();
					widenChat();
				}
				break;
			case 1699: // Right-aligned minimap orbs repositioned
			case 3305:
				//log.debug("script 1699/3305: fixWorldMapWikiStoreActAdvOrbs()");
				fixWorldMapWikiStoreActAdvOrbs();
				fixInterfaceDimensions();
				repositionMinimapWidgets();
				break;
			case 902: // Inventory background changed, revert it back to its old sprite and unhide inv if in cutscene
				// Also fail-safe for loading sprites
				//log.debug("script 902: fixInvBackground(), checkMinimapSprites(), unhide invWidget during cutscene");
				checkMinimapSprites();
				fixInvBackground();
				if (cutSceneActive)
				{
					Widget invWidget = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
					if (invWidget != null && invWidget.isHidden())
					{
						invWidget.setHidden(false);
					}
				}
				break;
			case 901: // Game Interface Mode changes
				//log.debug("script 901: gameClientLayoutChanged()");
				gameClientLayoutChanged();
				break;
			case 175:
			case 178:
			case ScriptID.MESSAGE_LAYER_OPEN:
			case ScriptID.MESSAGE_LAYER_CLOSE: //cases 113 and 664 removed d/t redundancy
				// Chatbox opens/closes
				if (config.isWideChatbox())
				{
					//log.debug("script {}, chatboxChanged() and widenChat()",scriptId,tickCount);
					chatboxChanged();
					widenChat();
					if (widgetWithBackgroundLoaded)
					{
						fixWidgetBackground();
					}
				}
				break;
			case 4731:
				// TOB widget fix (party orbs flicker if omitted)
				fixIngameOverlayWidgets();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		//cutscene
		if (event.getVarbitId() == 542)
		{
			if (event.getValue() == 1)
			{
				cutSceneActive = true;
			}
			else
			{
				cutSceneActive = false;
			}
			clientThread.invokeLater(() -> {
				chatboxChanged();
				widenChat();
			});
		}
		else if (event.getVarbitId() == Varbits.TRANSPARENT_CHATBOX)
		{
			if (event.getValue() == 1)
			{
				transparentChatbox = true;
				wideChatViewportOffset = 0;
				configManager.setConfiguration(
					"fixedresizablehybrid",
					"chatboxViewportCentering",
					false
				);
			}
			else if (event.getValue() == 0)
			{
				transparentChatbox = false;
				wideChatViewportOffset = 23;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (resizeOnGameTick)
		{
			//log.debug("onGameTick(): triggered for resize (AR)");
			resizeByAspectRatio();
			resizeOnGameTick = false;
		}
	}


	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int groupID = event.getGroupId();

		//log.debug("Widget loaded: {}", groupID);
		if (WIDGETS_WITH_BACKGROUNDS.contains(groupID))
		{
			widgetWithBackgroundLoaded = true;
			widgetsToFixBeforeRender.add(groupID);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		int groupID = event.getGroupId();

		if (WIDGETS_WITH_BACKGROUNDS.contains(groupID) && event.isUnload())
		{
			//log.debug("onWidgetClosed(): fairy ring closed");
			widgetWithBackgroundLoaded = false;
		}
	}

	// Will continue trying to initialize until the GameState has been stabilized as logged in (e.g. layout == 2 or 3)
	// For some reason you can't use invoke() here or else it will delete the minimap orbs when you change interface mode.
	private void queuePluginInitialization()
	{
		//log.debug("queuePluginInitialization()");
		//invokeLater will keep running until it returns true
		clientThread.invokeLater(() ->
		{
			// Uses getGameClientLayout() to determine when the game is ready to be initialized.
			int gameClientLayout = getGameClientLayout();
			if (gameClientLayout != -1)
			{
				if (gameClientLayout == 2)
				{
					initializePlugin();
				}
				return true;
			}
			return false;
		});
	}

	// Initializes the plugin by modifying necessary widgets and creating custom sprites.
	// Ensures the minimap, inventory, and viewport are properly adjusted for fixed mode.
	// Also resizes 16:9 if config option is true.
	private void initializePlugin()
	{
		//log.debug("initializePlugin()");
		widgetsModified = true;
		resizeRenderViewport();
		resizeByAspectRatio();
		overlayManager.add(enabledOverlays);

		fixInterfaceDimensions();
		repositionMinimapWidgets();
		createMinimapInvSprites();
		if (config.isWideChatbox())
		{
			widenChat();
			setupWideChatboxWidget();
		}
	}

	private void resizeByAspectRatio()
	{
		//log.debug("resizeByAspectRatio()");
		if (!config.aspectRatioResize())
		{
			return;
		}

		Dimension newDimensions = calculateAspectRatioDimensions();
		if (newDimensions != null)
		{
			resizeClient(newDimensions);
		}
	}

	private Dimension calculateAspectRatioDimensions()
	{
		//log.debug("calculateAspectRatioDimensions()");
		Widget fullCanvas = client.getWidget(InterfaceID.ToplevelOsrsStretch.GAMEFRAME);
		if (fullCanvas == null || fullCanvas.isHidden())
		{
			return null;
		}

		fullCanvas.revalidateScroll();

		Dimension stretchedDimensions = client.getStretchedDimensions();
		int currentWidth = stretchedDimensions.width;
		int currentHeight = stretchedDimensions.height;

		int aspectWidth = config.aspectRatioWidthResize();
		int aspectHeight = config.aspectRatioHeightResize();

		if (config.resizeBy() == ResizeBy.WIDTH)
		{
			int newHeight = aspectHeight * currentWidth / aspectWidth;
			return new Dimension(currentWidth, newHeight);
		}
		else
		{ // ResizeBy.HEIGHT
			int newWidth = aspectWidth * currentHeight / aspectHeight;
			return new Dimension(newWidth, currentHeight);
		}
	}

	private void resizeClient(Dimension dimension)
	{
		//log.debug("resizeClient(Dimension dimension)");
		// Validate and adjust the dimensions
		int processedWidth = Math.max(Math.min(dimension.width, 7680), Constants.GAME_FIXED_WIDTH);
		int processedHeight = Math.max(Math.min(dimension.height, 2160), Constants.GAME_FIXED_HEIGHT);
		Dimension processedGameSize = new Dimension(processedWidth, processedHeight);

		Dimension currentSize = configManager.getConfiguration("runelite", "gameSize", Dimension.class);
		if (processedGameSize.equals(currentSize))
		{
			Dimension processedGameSizePlus1 = new Dimension(processedWidth + 1, processedHeight);
			configManager.setConfiguration("runelite", "gameSize", processedGameSizePlus1);
			resizeOnGameTick = true;
		}
		else
		{
			configManager.setConfiguration("runelite", "gameSize", processedGameSize);
		}
	}

	// Saves the widget state under these conditions:
	// 1. The widget exists
	// 2. The widget has not already been saved
	//    - prevents overwriting of the vanilla state when functions are called more than once
	// The resetLast parameter is specified for the function resetWidgets() to allow for some saved widgets
	// to be reset after the others, preventing issue where parent widgets needs to be revalidated again.
	private void saveWidgetState(Widget widget)
	{
		saveWidgetState(widget, false);
	}

	private void saveWidgetState(Widget widget, boolean resetLast)
	{
		if (widget == null || getGameClientLayout() != 2)
		{
			return;
		}
		int widgetId = widget.getId();
		if (originalStates.containsKey(widgetId))
		{
			return;
		}
		WidgetState widgetState = new WidgetState(
			widget.getSpriteId(),
			widget.getOriginalX(),
			widget.getOriginalY(),
			widget.getOriginalWidth(),
			widget.getOriginalHeight(),
			widget.getXPositionMode(),
			widget.getYPositionMode(),
			widget.getWidthMode(),
			widget.getHeightMode(),
			widget.isHidden(),
			widget.isSelfHidden(),
			resetLast
		);
		originalStates.put(widgetId, widgetState);
	}

	// Determines the current game client layout mode.
	//
	// @return 1 if the layout is Fixed mode.
	// 2 if the layout is Resizable - Classic mode.
	// 3 if the layout is Resizable - Modern mode.
	// -1 if the layout cannot be determined.
	private int getGameClientLayout()
	{
		//log.debug("getGameClientLayout()");
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Widget classicResizableWidget = client.getWidget(InterfaceID.ToplevelOsrsStretch.CONTROL);
			if (classicResizableWidget != null && !classicResizableWidget.isHidden())
			{
				return 2;
			}
			Widget modernResizableWidget = client.getWidget(InterfaceID.ToplevelPreEoc.CONTROL);
			if (modernResizableWidget != null && !modernResizableWidget.isHidden())
			{
				return 3;
			}
			Widget classicFixedWidget = client.getWidget(InterfaceID.Toplevel.UNIVERSE);
			if (classicFixedWidget != null && !classicFixedWidget.isHidden())
			{
				return 1;
			}
		}
		return -1;
	}

	// Handles changes in the game client layout and triggers appropriate actions.
	//
	// This function is called after `onScriptPostFired()` for `scriptId == 901`.
	// It offers two key benefits over using `onGameStateChange()` or `client.isResizable()`:
	// 1. Prevents premature initialization by ensuring widgets are fully drawn, as
	//    `getGameClientLayout()` will return -1 if called too early.
	// 2. Provides a more specific response based on the interface layout, unlike the
	//    more general `isResizable()` method.
	//
	// If the layout changes to classic-resizable, the plugin is initialized.
	// For other layouts (fixed or modern-resizable), widgets are reset to avoid
	// interference caused by switching layouts.
	private void gameClientLayoutChanged()
	{
		//log.debug("gameClientLayoutChanged()");
		if (getGameClientLayout() == 2)
		{
			queuePluginInitialization();
		}
		else
		{
			resetWidgets();
		}
	}


	// Adjusts the positions of the World Map, Wiki, Store, and Activity Adviser orbs to match fixed mode alignment.
	//
	// This function is executed in the following cases:
	//  1. During initialization to set the positions of the four orbs.
	//  2. After `onScriptPostFired()` (scriptId == 1699 || scriptId == 3305)
	//     - Resets the positions of right-aligned minimap orbs.
	//
	// When the game layout is in classic-resizable mode (int 2), the function:
	// - Saves the current state of each orb.
	// - Sets or resets their positions to match the fixed mode layout.
	private void fixWorldMapWikiStoreActAdvOrbs()
	{
		//log.debug("fixWorldMapWikiStoreActAdvOrbs()");
		if (getGameClientLayout() == 2)
		{
			Widget worldMapOrb = client.getWidget(InterfaceID.Orbs.ORB_WORLDMAP);
			Widget wikiBanner = client.getWidget(InterfaceID.Orbs.WIKI);
			Widget storeOrb = client.getWidget(InterfaceID.Orbs.ORB_STORE);
			Widget activityAdviserOrb = client.getWidget(InterfaceID.Orbs.ORB_CONTENTRECOM);
			OrbsPosition positionMode = config.orbsPosition();
			if (worldMapOrb != null && worldMapOrb.getOriginalX() == 0)
			{
				saveWidgetState(worldMapOrb);
				setWidgetCoordinates(worldMapOrb, 23, 109);
			}
			if (wikiBanner != null && wikiBanner.getOriginalX() == 0)
			{
				saveWidgetState(wikiBanner);
				setWidgetCoordinates(wikiBanner, 21, 129);
			}
			if (storeOrb != null && storeOrb.getOriginalX() == 85)
			{
				saveWidgetState(storeOrb);
				setWidgetParameters(storeOrb, 0 + 13, 83 - 6, 34, 34, 2, 0, 0, 0);
			}
			if (activityAdviserOrb != null && activityAdviserOrb.getOriginalX() == 55)
			{
				saveWidgetState(activityAdviserOrb);
				setWidgetParameters(activityAdviserOrb, 0 + 13, 50 - 6, 34, 34, 2, 0, 0, 0);
			}
		}
	}

	// Used in volcanic mine overlay fix. There are likely other widgets this fixes too (minigame overlays)
	private void fixIngameOverlayWidgets()
	{
		int maxDepth = 4;
		if (!widgetsModified)
		{
			return;
		}
		Widget clickWindow = client.getWidget(InterfaceID.ToplevelOsrsStretch.VIEWPORT_TRACKER_BACK);
		Widget renderViewport = client.getWidget(InterfaceID.ToplevelOsrsStretch.VIEWPORT);
		if (clickWindow != null && renderViewport != null)
		{
			clickWindow.setXPositionMode(0);
			clickWindow.setYPositionMode(0);
			clickWindow.setOriginalWidth(renderViewport.getWidth());
			clickWindow.setOriginalHeight(renderViewport.getHeight());
			clickWindow.revalidateScroll();
			fixWidgetChildDimensions(clickWindow, maxDepth, 0); // Start recursive processing
		}
	}

	// Resets the bounding boxes of game interfaces (e.g. banks, deposit boxes, settings, etc).
	private void fixInterfaceDimensions()
	{
		//log.debug("fixInterfaceDimensions()");
		Widget renderViewport = client.getWidget(InterfaceID.ToplevelOsrsStretch.VIEWPORT);

		fixIngameOverlayWidgets();

		Widget oldSchoolBox = client.getWidget(InterfaceID.ToplevelOsrsStretch.HUD_CONTAINER_FRONT);
		if (oldSchoolBox != null && renderViewport != null)
		{
			Widget osbParent = oldSchoolBox.getParent();
			int parentHeight = osbParent.getOriginalHeight();
			int renderViewportHeight = renderViewport.getHeight();
			if (osbParent.getXPositionMode() == 1 || osbParent.getYPositionMode() == 1)
			{
				osbParent.setXPositionMode(0);
				osbParent.setYPositionMode(0);
				osbParent.setOriginalWidth(renderViewport.getWidth());
				osbParent.revalidateScroll();
			}
			if (!config.isWideChatbox() && parentHeight != renderViewportHeight)
			{
				osbParent.setOriginalHeight(renderViewportHeight);
				osbParent.revalidateScroll();
			}
			else if (config.isWideChatbox())
			{
				if (isChatboxOpen() && config.chatboxViewportCentering() && !transparentChatbox)
				{
					osbParent.setOriginalHeight(renderViewportHeight);
					osbParent.revalidateScroll();
					oldSchoolBox.setOriginalHeight(0);
					oldSchoolBox.revalidateScroll();
				}
				else
				{
					osbParent.setOriginalHeight(renderViewportHeight + wideChatViewportOffset);
					osbParent.revalidateScroll();
					oldSchoolBox.setOriginalHeight(165);
					oldSchoolBox.revalidateScroll();
				}
			}

			if (oldSchoolBox.getOriginalWidth() == 250)
			{
				oldSchoolBox.setOriginalWidth(0);
				oldSchoolBox.revalidateScroll();
			}
			for (Widget child : oldSchoolBox.getStaticChildren())
			{
				child.revalidateScroll();
			}
		}
	}

	private void fixWidgetBackground()
	{
		Widget widgetBackground = client.getWidget(InterfaceID.ToplevelOsrsStretch.MAINMODAL_BACKGROUNDS);
		Widget widgetInterface = client.getWidget(InterfaceID.ToplevelOsrsStretch.MAINMODAL);
		Widget mainViewport = client.getWidget(InterfaceID.ToplevelOsrsStretch.VIEWPORT);
		Widget oldSchoolBox = client.getWidget(InterfaceID.ToplevelOsrsStretch.HUD_CONTAINER_FRONT);

		// Ensure all required widgets are present
		if (widgetBackground == null || widgetInterface == null || mainViewport == null || oldSchoolBox == null)
		{
			return;
		}

		Widget[] backgroundChildren = widgetBackground.getDynamicChildren();
		if (backgroundChildren.length != 4)
		{
			return; // Exit if the expected four background sprites are not present
		}

		Widget topBackground = backgroundChildren[0];
		Widget bottomBackground = backgroundChildren[1];
		Widget leftBackground = backgroundChildren[2];
		Widget rightBackground = backgroundChildren[3];

		boolean chatIsOpen = isChatboxOpen();

		int topHeight = widgetInterface.getRelativeY();
		int leftWidth = widgetInterface.getRelativeX();
		int rightWidth = oldSchoolBox.getWidth() - widgetInterface.getWidth() - leftWidth;

		// Set widths for the left and right background widgets
		leftBackground.setOriginalWidth(leftWidth);
		rightBackground.setOriginalWidth(rightWidth);

		int bottomHeight;

		if (config.isWideChatbox())
		{
			// Wide chatbox adjustments
			if (chatIsOpen)
			{
				bottomHeight = oldSchoolBox.getHeight() - widgetInterface.getHeight() - topHeight;
				bottomBackground.setOriginalY(oldSchoolBox.getParent().getHeight() - oldSchoolBox.getHeight());
			}
			else
			{
				// Chat closed case remains the same regardless of viewport centering
				bottomHeight = oldSchoolBox.getParent().getHeight() - widgetInterface.getHeight() - topHeight - 23;
				bottomBackground.setOriginalY(23);
			}
		}
		else
		{
			bottomHeight = mainViewport.getHeight() - topHeight - widgetInterface.getHeight();
		}

		// Set final heights and Y-positions
		topBackground.setOriginalHeight(topHeight);
		bottomBackground.setOriginalHeight(bottomHeight);
		leftBackground.setOriginalY(topHeight);
		rightBackground.setOriginalY(topHeight);

		// Revalidate the widget background to apply changes
		widgetBackground.revalidateScroll();
	}

	private void fixStatsGuide()
	{
		if (!widgetsModified)
		{
			return;
		}
		Widget statsGuideWidget = client.getWidget(InterfaceID.SkillGuide.UNIVERSE);
		if (statsGuideWidget == null)
		{
			return;
		}
		statsGuideWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
		statsGuideWidget.setOriginalX(0);
		statsGuideWidget.revalidateScroll();
	}

	private void fixWidgetChildDimensions(Widget widget, int maxDepth, int currentDepth)
	{
		// Recurse until max depth is reached (unless maxDepth is 0)
		if (maxDepth != 0 && currentDepth >= maxDepth)
		{
			return;
		}
		// Process both static and nested children using the helper method
		processClickWindowChildren(widget.getStaticChildren(), maxDepth, currentDepth, true);
		processClickWindowChildren(widget.getNestedChildren(), maxDepth, currentDepth, false);
	}

	private void processClickWindowChildren(Widget[] children, int maxDepth, int currentDepth, boolean staticChildren)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			if ((child.getOriginalWidth() >= 248 && child.getOriginalWidth() <= 254) && child.getWidthMode() == 1)
			{
				child.setOriginalWidth(0);
			}
			if (child.getOriginalHeight() >= 164 && child.getOriginalHeight() <= 172 && child.getHeightMode() == 1
				&& config.isWideChatbox() && config.chatboxViewportCentering()
				&& isChatboxOpen())
			{
				child.setOriginalHeight(0);
			}
			if (staticChildren)
			{
				child.revalidateScroll();
			}
			// Recurse into both static and nested children
			fixWidgetChildDimensions(child, maxDepth, currentDepth + 1);
		}
	}

	// Runs from onScriptPostFired() for the script which fires and resets the inventory background sprite
	private void fixInvBackground()
	{
		if (widgetsModified)
		{
			//log.debug("fixInvBackground()");
			Widget invBackground = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_BACKGROUND);
			if (invBackground != null && invBackground.getSpriteId() == 897)
			{
				saveWidgetState(invBackground);
				invBackground.setSpriteId(1031);
			}
		}
	}

	// Resets all modified widgets. See removeAddedWidgets() for how all non-vanilla and plugin-created widgets are reset.
	// Runs whenever the user logs out, hops worlds, or changes the game client layout to something other than classic-resizable
	// There are a few widgets which need to be revalidated last to make sure everything resets properly, which is done last.
	private void resetWidgets()
	{
		//log.debug("resetWidgets()");
		clientThread.invoke(() -> {
			removeAddedWidgets();
			resetRenderViewport();
			resetOriginalStates(); // sets widgetModified to false too
			restoreSprites();
		});
	}

	private void resetOriginalStates()
	{
		//log.debug("resetOriginalStates()");
		List<Map.Entry<Integer, WidgetState>> resetLastEntries = new ArrayList<>();

		// Iterate through the originalStates map
		for (Map.Entry<Integer, WidgetState> entry : originalStates.entrySet())
		{
			int widgetId = entry.getKey();
			WidgetState state = entry.getValue();

			// Skip resetLast widgets for now, and adds to the list to reset last.
			if (state.isResetLast())
			{
				resetLastEntries.add(entry);
				continue;
			}

			// Retrieves the widget and resets it
			Widget widget = client.getWidget(widgetId);
			if (widget != null)
			{
				widget.setSpriteId(state.getSpriteId());
				widget.setOriginalX(state.getOriginalX());
				widget.setOriginalY(state.getOriginalY());
				widget.setOriginalWidth(state.getOriginalWidth());
				widget.setOriginalHeight(state.getOriginalHeight());
				widget.setXPositionMode(state.getXPositionMode());
				widget.setYPositionMode(state.getYPositionMode());
				widget.setWidthMode(state.getWidthMode());
				widget.setHeightMode(state.getHeightMode());
				widget.setHidden(state.isHidden() || state.isSelfHidden());
			}
		}

		// Revalidates reset widgets
		clientThread.invoke(() -> {
			for (Map.Entry<Integer, WidgetState> entry : originalStates.entrySet())
			{
				if (!entry.getValue().isResetLast())
				{
					Widget widget = client.getWidget(entry.getKey());
					if (widget != null)
					{
						widget.revalidateScroll();
					}
				}
			}
		});

		// Process widgets with isResetLast() set to true
		for (Map.Entry<Integer, WidgetState> entry : resetLastEntries)
		{
			int widgetId = entry.getKey();
			WidgetState state = entry.getValue();

			// Retrieve the widget and reset it
			Widget widget = client.getWidget(widgetId);
			if (widget != null)
			{
				widget.setSpriteId(state.getSpriteId());
				widget.setOriginalX(state.getOriginalX());
				widget.setOriginalY(state.getOriginalY());
				widget.setOriginalWidth(state.getOriginalWidth());
				widget.setOriginalHeight(state.getOriginalHeight());
				widget.setXPositionMode(state.getXPositionMode());
				widget.setYPositionMode(state.getYPositionMode());
				widget.setWidthMode(state.getWidthMode());
				widget.setHeightMode(state.getHeightMode());
				widget.setHidden(state.isHidden());
			}
		}

		// Revalidate widgets with isResetLast == true
		clientThread.invoke(() -> {
			for (Map.Entry<Integer, WidgetState> entry : resetLastEntries)
			{
				Widget widget = client.getWidget(entry.getKey());
				if (widget != null)
				{
					widget.revalidateScroll();
				}
			}
		});

		// Clear the originalStates map after resetting, and sets the boolean to reflect that
		originalStates.clear();
		widgetsModified = false;
	}

	// Removes all widgets that plugin created (sprites surrounding the minimap/inventory)
	public void removeAddedWidgets()
	{
		//log.debug("removeAddedWidgets() (inv+minimap)");
		//Deletes added minimap sprites + bottom border sprite
		Widget minimapDrawArea = client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		if (minimapDrawArea != null && minimapDrawArea.getParent() != null)
		{
			minimapDrawArea.getParent().deleteAllChildren();
		}

		// Deletes added inventory sprites
		Widget invDynamicParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
		if (invDynamicParent != null)
		{
			invDynamicParent.deleteAllChildren();
		}
		overlayManager.remove(enabledOverlays);
	}

	// Sets a widget's coordinates, overloaded to be able to accept both ComponentIDs or the widget directly
	private void setWidgetCoordinates(int componentId, int newX, int newY)
	{
		setWidgetCoordinates(client.getWidget(componentId), newX, newY);
	}

	private void setWidgetCoordinates(Widget widget, int newX, int newY)
	{
		if (widget != null)
		{
			saveWidgetState(widget);
			widget.setOriginalX(newX);
			widget.setOriginalY(newY);
			widget.revalidateScroll();
		}
	}

	// Expanded version of the setWidgetCoordinates() function to accept more parameters
	private void setWidgetParameters(Widget widget,
									 int newX,
									 int newY,
									 int newOriginalWidth,
									 int newOriginalHeight,
									 int newXPositionMode,
									 int newYPositionMode,
									 int newWidthMode,
									 int newHeightMode)
	{
		if (widget != null)
		{
			saveWidgetState(widget);
			widget.setOriginalX(newX);
			widget.setOriginalY(newY);
			widget.setOriginalWidth(newOriginalWidth);
			widget.setOriginalHeight(newOriginalHeight);
			widget.setXPositionMode(newXPositionMode);
			widget.setYPositionMode(newYPositionMode);
			widget.setWidthMode(newWidthMode);
			widget.setHeightMode(newHeightMode);
			widget.revalidateScroll();
		}
	}

	// Positions the all the minimap elements to align with fixed mode, and saves the original widget parameters for
	//     resetWidgets() later. Could definitely be cleaned up.
	private void repositionMinimapWidgets()
	{
		final Widget mapContainer  = client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER);
		final Widget maskSprite    = client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP_GRAPHIC9);
		final Widget orbsParent    = client.getWidget(InterfaceID.ToplevelOsrsStretch.ORBS);
		final Widget orbsInterface = client.getWidget(InterfaceID.Orbs.UNIVERSE);

		if (getGameClientLayout() != 2 || mapContainer == null || maskSprite == null || orbsParent == null || orbsInterface == null)
			return;

		// geometry
		final int PANEL_W   = 249;
		final int MINIMAP_H = 207;
		final int ORBS_H    = 197;

		saveWidgetState(mapContainer, true);
		saveWidgetState(maskSprite);
		saveWidgetState(orbsInterface);
		saveWidgetState(orbsParent);

		if (!maskSprite.isHidden()) maskSprite.setHidden(true);

		// Only write when changed (reduces layout churn)
		boolean changed = false;
		if (mapContainer.getOriginalWidth()  != PANEL_W)   { mapContainer.setOriginalWidth(PANEL_W);   changed = true; }
		if (mapContainer.getOriginalHeight() != MINIMAP_H) { mapContainer.setOriginalHeight(MINIMAP_H); changed = true; }
		if (changed) mapContainer.revalidateScroll();

		changed = false;
		if (orbsParent.getOriginalWidth()  != PANEL_W) { orbsParent.setOriginalWidth(PANEL_W);  changed = true; }
		if (orbsParent.getOriginalHeight() != ORBS_H)  { orbsParent.setOriginalHeight(ORBS_H); changed = true; }
		if (changed) orbsParent.revalidateScroll();

		changed = false;
		if (orbsInterface.getWidthMode()  != WidgetSizeMode.ABSOLUTE) { orbsInterface.setWidthMode(WidgetSizeMode.ABSOLUTE);  changed = true; }
		if (orbsInterface.getHeightMode() != WidgetSizeMode.ABSOLUTE) { orbsInterface.setHeightMode(WidgetSizeMode.ABSOLUTE); changed = true; }
		if (orbsInterface.getOriginalWidth()  != PANEL_W) { orbsInterface.setOriginalWidth(PANEL_W);  changed = true; }
		if (orbsInterface.getOriginalHeight() != ORBS_H)  { orbsInterface.setOriginalHeight(ORBS_H); changed = true; }
		if (changed) orbsInterface.revalidateScroll();

		// Right-anchored elements to pin at absolute coordinates
		final int[][] minimapPlacements = {
			{ InterfaceID.ToplevelOsrsStretch.MAP_NOCLICK_0,        44,   5 },
			{ InterfaceID.ToplevelOsrsStretch.MAP_NOCLICK_1,        44,  45 },
			{ InterfaceID.ToplevelOsrsStretch.MAP_NOCLICK_2,        44, 101 },
			{ InterfaceID.ToplevelOsrsStretch.MAP_NOCLICK_3,        44, 126 },
			{ InterfaceID.ToplevelOsrsStretch.MAP_NOCLICK_4,        44, 141 },
			{ InterfaceID.ToplevelOsrsStretch.MAP_NOCLICK_5,        44, 156 },
			{ InterfaceID.ToplevelOsrsStretch.MINIMAP,              50,   9 },
			{ InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP_GRAPHIC9, 44,   1 }
		};

		for (int[] p : minimapPlacements)
		{
			Widget w = client.getWidget(p[0]);
			if (w == null) continue;
			if (w.getXPositionMode() == WidgetPositionMode.ABSOLUTE_RIGHT) {
				saveWidgetState(w, true);
				w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				setWidgetCoordinates(w, p[1], p[2]); // this revalidates
			}
		}

		// Orbs cluster
		if (config.orbsPosition() == OrbsPosition.FIXED_MODE) {
			setWidgetCoordinates(InterfaceID.Orbs.ORB_RUNENERGY, 10, 97);
			setWidgetCoordinates(InterfaceID.Orbs.ORB_SPECENERGY, 32, 122);
		} else if (config.orbsPosition() == OrbsPosition.MORE_CLEARANCE) {
			setWidgetCoordinates(InterfaceID.Orbs.ORB_RUNENERGY,  2,  97);
			setWidgetCoordinates(InterfaceID.Orbs.ORB_SPECENERGY, 23, 124);
		}
		setWidgetCoordinates(InterfaceID.Orbs.XP_DROPS,   0, 11);
		setWidgetCoordinates(InterfaceID.Orbs.ORB_HEALTH, 0, 31);
		setWidgetCoordinates(InterfaceID.Orbs.ORB_PRAYER, 0, 65);

		// Compass
		setWidgetCoordinates(client.getWidget(InterfaceID.ToplevelOsrsStretch.COMPASSCLICK),         26, 1);
		setWidgetCoordinates(client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP_GRAPHIC6), 28, 3);

		fixWorldMapWikiStoreActAdvOrbs();
		mapContainer.revalidateScroll();
	}

	private void checkMinimapSprites()
	{
		if (!widgetsModified)
		{
			return;
		}

		Widget minimapSpriteContainer = client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP);
		if (minimapSpriteContainer == null)
		{
			return;
		}

		if (minimapSpriteContainer.getDynamicChildren().length < 6)
		{
			createMinimapInvSprites();
		}
	}

	private void createMinimapInvSprites()
	{
		final Widget minimapParent   = client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP);
		final Widget inventoryParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
		if (minimapParent == null || inventoryParent == null) return;

		final int minimapId   = minimapParent.getId();
		final int inventoryId = inventoryParent.getId();
		//Make sure bounds are set properly
		inventoryWidgetBoundsFix();

		// Each row: [parentMarker, type, spriteId, x, y, w, h, xPosMode, yPosMode, wMode, hMode, noClickThrough, spriteTiling]
		final int[][] sprites = {
			{minimapId,   WidgetType.GRAPHIC, 1182, 29,   4, 172, 156, WidgetPositionMode.ABSOLUTE_LEFT,   WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // centerMinimapSprite
			{minimapId,   WidgetType.GRAPHIC, 1611,  0, 160, 249,   8, WidgetPositionMode.ABSOLUTE_RIGHT,  WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // bottomMinimapSprite
			{minimapId,   WidgetType.GRAPHIC, 1037,  0,   4,  29, 156, WidgetPositionMode.ABSOLUTE_LEFT,   WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // leftMinimapSprite
			{minimapId,   WidgetType.GRAPHIC, 1038,  0,   4,  48, 156, WidgetPositionMode.ABSOLUTE_RIGHT,  WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // rightMinimapSprite
			{minimapId,   WidgetType.GRAPHIC, 1039, 48,   0, 717,   4, WidgetPositionMode.ABSOLUTE_RIGHT,  WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // topThinBarRight
			{minimapId,   WidgetType.GRAPHIC, 1441,  0,   0,  48,   4, WidgetPositionMode.ABSOLUTE_RIGHT,  WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // topThinBarLeft
			{inventoryId, WidgetType.GRAPHIC, 1035,  0,  37,  28, 261, WidgetPositionMode.ABSOLUTE_RIGHT,  WidgetPositionMode.ABSOLUTE_BOTTOM, WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // right inv column
			{inventoryId, WidgetType.GRAPHIC, 1033,  0,  38,  31, 133, WidgetPositionMode.ABSOLUTE_LEFT,   WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // left inv column top
			{inventoryId, WidgetType.GRAPHIC, 1034,  3, 171,  28, 128, WidgetPositionMode.ABSOLUTE_LEFT,   WidgetPositionMode.ABSOLUTE_TOP,    WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}, // left inv column bottom
			{inventoryId, WidgetType.GRAPHIC, 1033,  0,   0,   3, 170, WidgetPositionMode.ABSOLUTE_LEFT,   WidgetPositionMode.ABSOLUTE_BOTTOM, WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE, 0, 0}  // tiny left strip
		};

		for (int[] row : sprites) {
			createNewSpriteWidget(row);
		}
	}


	private void createNewSpriteWidget(int[] cfg)
	{
		Widget parent = client.getWidget(cfg[C_PARENT_ID]);
		if (parent == null) return;

		Widget w = parent.createChild(cfg[C_TYPE]);
		w.setSpriteId(cfg[C_SPRITE]);
		w.setOriginalX(cfg[C_X]);
		w.setOriginalY(cfg[C_Y]);
		w.setOriginalWidth(cfg[C_W]);
		w.setOriginalHeight(cfg[C_H]);
		w.setXPositionMode(cfg[C_XMODE]);
		w.setYPositionMode(cfg[C_YMODE]);
		w.setWidthMode(cfg[C_WMODE]);
		w.setHeightMode(cfg[C_HMODE]);
		w.setNoClickThrough(cfg[C_NOCLICK] != 0);
		w.setSpriteTiling(cfg[C_TILE] != 0);

		parent.revalidateScroll();
	}



	// Sets up the coordinates and bounds on the inventory panel widget prior to creating the fixed background sprites
	// and prior to modifying the existing inventory sprites.
	private void inventoryWidgetBoundsFix()
	{
		//log.debug("inventoryWidgetBoundsFix()");
		Widget invParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
		if (invParent != null)
		{
			saveWidgetState(invParent, true);
			invParent.setOriginalWidth(249);
			invParent.setOriginalHeight(336);
			invParent.revalidate();
		}

		Widget invBackground = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_BACKGROUND);
		if (invBackground != null)
		{
			saveWidgetState(invBackground);
			invBackground.setOriginalX(28);
			invBackground.setOriginalY(37);
			invBackground.setOriginalWidth(190);
			invBackground.setOriginalHeight(261);
			invBackground.setSpriteId(1031);
			invBackground.revalidate();
		}

		Widget invLeftColumn = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU_GRAPHIC1);
		if (invLeftColumn != null)
		{
			saveWidgetState(invLeftColumn);
			invLeftColumn.setHidden(true);
			invLeftColumn.revalidate();
		}
		Widget invRightColumn = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU_GRAPHIC2);
		if (invRightColumn != null)
		{
			saveWidgetState(invRightColumn);
			invRightColumn.setHidden(true);
			invRightColumn.revalidate();
		}

		Widget invBottomBarSprite = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU_GRAPHIC3);
		if (invBottomBarSprite != null)
		{
			saveWidgetState(invBottomBarSprite);
			invBottomBarSprite.setOriginalWidth(246);
			invBottomBarSprite.setOriginalHeight(37);
			invBottomBarSprite.setSpriteId(1032);
			invBottomBarSprite.revalidate();
		}

		Widget invBottomTabsParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_BOTTOM);
		if (invBottomTabsParent != null)
		{
			saveWidgetState(invBottomTabsParent, true);
			invBottomTabsParent.setOriginalX(2);
			invBottomTabsParent.revalidate();
		}

		Widget invTopBarSprite = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU_GRAPHIC5);
		if (invTopBarSprite != null)
		{
			saveWidgetState(invTopBarSprite);
			invTopBarSprite.setOriginalY(298);
			invTopBarSprite.setOriginalWidth(249);
			invTopBarSprite.setOriginalHeight(38);
			invTopBarSprite.setSpriteId(1036);
			invTopBarSprite.revalidate();
		}

		Widget invTopTabsParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_TOP);
		if (invTopTabsParent != null)
		{
			saveWidgetState(invTopTabsParent, true);
			invTopTabsParent.setOriginalX(2);
			invTopTabsParent.revalidate();
		}

		Widget invViewportInterfaceController = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER);
		if (invViewportInterfaceController != null)
		{
			saveWidgetState(invViewportInterfaceController);
			invViewportInterfaceController.setOriginalX(26 + 2);
			invViewportInterfaceController.revalidate();
		}
	}

	// Resizes the main viewport of the game so that no rendering occurs underneath the minimap/inventory.
	// This also consequently centers the camera properly, one of my main annoyances with the original resizable mode
	private void resizeRenderViewport()
	{
		//log.debug("resizeRenderViewport()");
		Widget mainViewport = client.getWidget(InterfaceID.ToplevelOsrsStretch.VIEWPORT);
		if (mainViewport != null)
		{
			// Width is set to the width of the inventory and minimap widgets because widthMode = 1 (subtracts
			//     that value from the parent widget's dimensions).
			mainViewport.setOriginalWidth(249);
			if (config.isWideChatbox())
			{
				chatboxChanged();
			}
			// Configures height of viewport if wide chatbox is enabled
			mainViewport.revalidateScroll();
		}
	}

	// Reset's the plugin's changes on the render viewport back to the original fullscreen resizable mode.
	// Called during the resetWidgets() function.
	private void resetRenderViewport()
	{
		//log.debug("resetRenderViewport()");
		Widget mainViewport = client.getWidget(InterfaceID.ToplevelOsrsStretch.VIEWPORT);
		if (mainViewport != null)
		{
			clientThread.invoke(() -> {
				mainViewport.setOriginalWidth(0);
				mainViewport.setOriginalHeight(0);
				mainViewport.setYPositionMode(1);
				mainViewport.revalidateScroll();
			});
		}
	}

	//Runs after onPostScript when opening or closing of the chatbox. Handles recentering the viewport for Wide chat mode only.
	private void chatboxChanged()
	{
		if (!config.isWideChatbox() || getGameClientLayout() != 2)
		{
			return;
		}

		Widget mainViewport = client.getWidget(InterfaceID.ToplevelOsrsStretch.VIEWPORT);
		Widget chatboxFrame = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		Widget chatboxParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
		if (mainViewport == null || chatboxFrame == null || chatboxParent == null)
		{
			return;
		}

		int baseHeight = (isChatboxOpen() && config.chatboxViewportCentering()) ? 165 : wideChatViewportOffset;
		mainViewport.setOriginalHeight(baseHeight + chatboxParent.getOriginalY());
		mainViewport.setYPositionMode(0);
		mainViewport.revalidateScroll();

		Widget chatboxBackgroundParent = client.getWidget(InterfaceID.Chatbox.CHAT_BACKGROUND);
		if (chatboxBackgroundParent != null)
		{
			int childrenCount = chatboxBackgroundParent.getDynamicChildren().length;
			if (childrenCount == 1)
			{
				setupWideChatboxWidget();
			}
			else if (childrenCount == 4)
			{
				Widget middleChatBackground = chatboxBackgroundParent.getDynamicChildren()[1];
				int newWidth = (int) Math.ceil((579.0 / 519.0) * (client.getCanvasWidth() - 249) - 60);
				middleChatBackground.setOriginalWidth(newWidth);
			}
		}

		fixInterfaceDimensions();
	}


	private void widenChat()
	{
		//log.debug("Started widenChat() -> positionChatboxButtons -> *logChatWidgets()*");
		if (!config.isWideChatbox() || !widgetsModified || getGameClientLayout() != 2)
		{
			return;
		}
		Widget canvas = client.getWidget(InterfaceID.ToplevelOsrsStretch.CONTROL);
		if (canvas == null)
		{
			return;
		}
		int wideChatboxWidth = canvas.getWidth() - 249;
		Widget chatParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);//161.96
		if (chatParent != null)
		{
			saveWidgetState(chatParent);
			chatParent.setOriginalWidth(wideChatboxWidth);
			chatParent.setOriginalX(0);
			chatParent.setXPositionMode(0);
			chatParent.revalidateScroll();
		}
		Widget chatFrame = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chatFrame != null)
		{
			saveWidgetState(chatFrame);
			chatFrame.setOriginalWidth(wideChatboxWidth);
			chatFrame.revalidateScroll();
		}
		Widget dialogueOptions = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (dialogueOptions != null)
		{
			saveWidgetState(dialogueOptions);
			dialogueOptions.setOriginalX(0);
			dialogueOptions.setXPositionMode(1);
			Widget dialogueOptionsParent = dialogueOptions.getParent();
			if (dialogueOptionsParent != null)
			{
				dialogueOptionsParent.revalidateScroll();
			}
		}
		Widget reportAbuseDialogueSprite = client.getWidget(InterfaceID.Reportabuse.UNIVERSE_GRAPHIC0);
		if (reportAbuseDialogueSprite != null)
		{
			saveWidgetState(reportAbuseDialogueSprite);
			reportAbuseDialogueSprite.setHidden(true);
		}
		// Cooking/fletching background removal
		Widget skillingDialogBackgroundSprite = client.getWidget(InterfaceID.Skillmulti.UNIVERSE_GRAPHIC0);
		if (skillingDialogBackgroundSprite != null)
		{
			saveWidgetState(skillingDialogBackgroundSprite);
			skillingDialogBackgroundSprite.setHidden(true);
		}
		//Center chat buttons on viewport
		positionChatboxButtons();
	}

	private void positionChatboxButtons()
	{
		Widget chatButtonsParent = client.getWidget(InterfaceID.Chatbox.CONTROLS);
		if (chatButtonsParent == null)
		{
			return;
		}
		saveWidgetState(chatButtonsParent);
		chatButtonsParent.setOriginalWidth(0);
		chatButtonsParent.setWidthMode(WidgetSizeMode.MINUS);
		chatButtonsParent.revalidateScroll();
		//Fix for chatbuttons disappearing during cutscene and causing render bugs
		Widget chatParent = client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
		if (cutSceneActive
			&& chatButtonsParent.isSelfHidden()
			&& chatParent != null
			&& chatParent.getOriginalY() == 0
		)
		{
			chatButtonsParent.setHidden(false);
		}
		Widget[] chatButtonsWidgets = chatButtonsParent.getStaticChildren();
		Widget reportButton = client.getWidget(InterfaceID.Chatbox.REPORTABUSE);
		int DEFAULT_CHAT_WIDTH = 519;
		int chatWidth = chatButtonsParent.getWidth();

		for (int i = 0; i < chatButtonsWidgets.length; i++)
		{
			Widget widget = chatButtonsWidgets[i];
			if (widget == null)
			{
				continue;
			}

			// Index 0 is the parent widget for the sprite behind the chatbox buttons, while the rest are the actual buttons
			// Because it's not a button, it has special logic to widen it the entire width.
			if (i == 0)
			{
				Widget[] children = widget.getStaticChildren();
				if (children.length > 0 && children[0] != null)
				{
					Widget chatButtonsBackground = children[0];
					saveWidgetState(chatButtonsBackground);
					chatButtonsBackground.setOriginalWidth(0);
					chatButtonsBackground.setWidthMode(WidgetSizeMode.MINUS);
					chatButtonsBackground.revalidate();
				}
			}
			else
			{
				//Logic for processing the actual button widgets
				saveWidgetState(widget);
				int originalX = originalStates.get(widget.getId()).getOriginalX();
				int originalWidth = originalStates.get(widget.getId()).getOriginalWidth();
				// Center align buttons with no stretching
				if (config.centerChatboxButtons())
				{
					int newButtonX = ((chatWidth - DEFAULT_CHAT_WIDTH) / 2) + originalX;
					widget.setOriginalX(newButtonX);
					widget.setOriginalWidth(originalWidth);

					Widget[] children = widget.getStaticChildren();
					if (children.length > 0 && children[0] != null && reportButton != null && widget != reportButton)
					{
						children[0].setOriginalWidth(originalWidth);
					}
				}
				else
				{ // Stretch chatbox buttons
					int newButtonX = chatWidth * originalX / DEFAULT_CHAT_WIDTH;
					int newButtonWidth = chatWidth * originalWidth / DEFAULT_CHAT_WIDTH;
					widget.setOriginalX(newButtonX);
					widget.setOriginalWidth(newButtonWidth);

					Widget[] children = widget.getStaticChildren();
					if (children.length > 0 && children[0] != null && reportButton != null && widget != reportButton)
					{
						// Adjust the sprite under the button
						children[0].setOriginalWidth(newButtonWidth);
					}
				}
				widget.revalidateScroll();
			}
		}
	}

	private SpritePixels getBufferedImageSpritePixels(BufferedImage image)
	{
		try
		{
			return ImageUtil.getImageSpritePixels(image, client);
		}
		catch (RuntimeException ex)
		{
			log.debug("Unable to process buffered image: ", ex);
		}
		return null;
	}

	private void restoreSprites()
	{
		client.getWidgetSpriteCache().reset();
		client.getSpriteOverrides().remove(-8001);
		client.getSpriteOverrides().remove(-8002);
		client.getSpriteOverrides().remove(-8003);
	}

	private void setupChatboxSprites()
	{
		SpritePixels overrideSprite = client.getSpriteOverrides().get(SpriteID.CHATBOX);
		BufferedImage chatboxImage = (overrideSprite != null) ? overrideSprite.toBufferedImage() : defaultChatboxBufferedImage;

		// Crop left/right borders and convert images to sprites
		int edgeWidth = 30;
		int height = chatboxImage.getHeight();
		int width = chatboxImage.getWidth();
		SpritePixels leftSpritePixels = getBufferedImageSpritePixels(chatboxImage.getSubimage(0, 0, edgeWidth, height));
		SpritePixels rightSpritePixels = getBufferedImageSpritePixels(chatboxImage.getSubimage(width - edgeWidth, 0, edgeWidth, height));
		SpritePixels fullSpritePixels = getBufferedImageSpritePixels(chatboxImage);

		// Reset and override sprites
		restoreSprites();
		client.getSpriteOverrides().put(-8001, fullSpritePixels);
		client.getSpriteOverrides().put(-8002, leftSpritePixels);
		client.getSpriteOverrides().put(-8003, rightSpritePixels);
		client.getWidgetSpriteCache().reset();
	}

	private void createNewChatboxSprites()
	{
		if (!config.isWideChatbox())
		{
			return;
		}

		// Retrieve required widgets.
		Widget canvas = client.getWidget(InterfaceID.ToplevelOsrsStretch.CONTROL); // Provides the viewport width.
		Widget chatboxFrame = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		Widget chatboxButtons = client.getWidget(InterfaceID.Chatbox.CONTROLS);
		Widget chatboxBackgroundParent = client.getWidget(InterfaceID.Chatbox.CHAT_BACKGROUND);

		// Ensure all widgets exist.
		if (canvas == null || chatboxFrame == null || chatboxButtons == null || chatboxBackgroundParent == null)
		{
			return;
		}

		// Calculate dimensions.
		final int WIDTH_OFFSET = 249;
		final int EDGE_WIDTH = 30;
		int totalWidth = canvas.getWidth() - WIDTH_OFFSET;

		Widget[] chatBackgroundChildren = chatboxBackgroundParent.getDynamicChildren();
		if (chatboxFrame.isHidden() || chatBackgroundChildren.length != 1 || chatBackgroundChildren[0] == null)
		{
			return;
		}

		// Calculate middle width using the provided formula.
		final double MULTIPLIER = 579.0 / 519.0;
		final int MIDDLE_OFFSET = 60;
		int middleWidth = (int) Math.ceil(MULTIPLIER * totalWidth - MIDDLE_OFFSET);

		// Create middle sprite.
		// [widget, type, spriteId, originalX, originalY, originalWidth, originalHeight,
		//  xPositionMode, yPositionMode, widthMode, heightMode, noclickthrough, spriteTiling]
		int[] middleChatBackgroundParentSettings = {
			chatboxBackgroundParent.getId(), WidgetType.GRAPHIC, -8001,
			0, 0, middleWidth, 0,
			WidgetPositionMode.ABSOLUTE_CENTER, WidgetPositionMode.ABSOLUTE_TOP,
			WidgetSizeMode.ABSOLUTE, WidgetSizeMode.MINUS,
			0, 0
		};
		createNewSpriteWidget(middleChatBackgroundParentSettings);

		// Create left sprite.
		int[] leftChatBackgroundParentSettings = {
			chatboxBackgroundParent.getId(), WidgetType.GRAPHIC, -8002,
			0, 0, EDGE_WIDTH, 0,
			WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP,
			WidgetSizeMode.ABSOLUTE, WidgetSizeMode.MINUS,
			0, 0
		};
		createNewSpriteWidget(leftChatBackgroundParentSettings);

		// Create right sprite.
		int[] rightChatBackgroundParentSettings = {
			chatboxBackgroundParent.getId(), WidgetType.GRAPHIC, -8003,
			0, 0, EDGE_WIDTH, 0,
			WidgetPositionMode.ABSOLUTE_RIGHT, WidgetPositionMode.ABSOLUTE_TOP,
			WidgetSizeMode.ABSOLUTE, WidgetSizeMode.MINUS,
			0, 0
		};
		createNewSpriteWidget(rightChatBackgroundParentSettings);
	}

	private void setupWideChatboxWidget()
	{
		if (config.isWideChatbox())
		{
			setupChatboxSprites();
			createNewChatboxSprites();
		}
	}

	private boolean isChatboxOpen()
	{
		Widget chatboxFrame = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chatboxFrame == null)
		{
			return false;
		}

		if (cutSceneActive)
		{
			Widget chatboxTransparentBackground = client.getWidget(InterfaceID.Chatbox.CHAT_BACKGROUND);
			return chatboxTransparentBackground != null
				&& chatboxTransparentBackground.getDynamicChildren().length > 0
				&& !chatboxFrame.isHidden();
		}
		return !chatboxFrame.isHidden();
	}
}
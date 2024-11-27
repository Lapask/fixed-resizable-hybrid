package com.lapask;
import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.events.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import java.awt.*;
import java.util.*;
import java.util.List;

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
	private KeyManager keyManager;

	private boolean resizeOnGameTick = false;
	boolean widgetsModified = false;
	private final HashMap<Integer, WidgetState> originalStates = new HashMap<>();
	private static final int classicResizableGroupId = InterfaceID.RESIZABLE_VIEWPORT;
	private static final int oldSchoolBoxId = ComponentID.RESIZABLE_VIEWPORT_RESIZABLE_VIEWPORT_OLD_SCHOOL_BOX;

	@Provides
	FixedResizableHybridConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FixedResizableHybridConfig.class);
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		if (resizeOnGameTick){
			Dimension configDimensions = calcSixteenByNineDimensions();
			resizeClient(configDimensions);
			resizeOnGameTick = false;
		}
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Fixed Hybrid Plugin started!");
		if (client.getGameState() == GameState.LOGGED_IN) {
			queuePluginInitialization();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Fixed Resizable Hybrid Plugin stopped!");
		resetWidgets();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("fixedresizablehybrid"))
		{
			return;
		}
		//Resize client if config option is enabled
		if (event.getKey().equals("useSixteenByNine") && config.useSixteenByNine()){
			clientThread.invoke(this::resizeSixteenByNine);
		} else if (event.getKey().equals("fillGapBorders")){
			resetWidgets();
			queuePluginInitialization();
		}
	}


	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		int scriptId = event.getScriptId();
		// Fires when interface boxes are recalculated (e.g. bank inventory, settings panel, etc)
		if (scriptId == 909) {
			fixNestedInterfaceDimensions();
		}
		// Fires when right aligned minimap orbs are repositioned
		if (scriptId == 1699 || scriptId == 3305) {
			fixWorldMapWikiStoreActAdvOrbs();
		}
		// Fires when the inventory background is changed, reverting it back to its old sprite
		if (scriptId == 902) {
			fixInvBackground();
		}
		// Fires when Game Interface Mode changes
		if (scriptId == 901){
			gameClientLayoutChanged();
		}
	}
	// Calculates 16:9 dimensions based on the client height
	// Used in when config.useSixteenByNine() is enabled
	private Dimension calcSixteenByNineDimensions(){
		Dimension stretchedDimensions = client.getStretchedDimensions();
		Widget mainViewport = client.getWidget(classicResizableGroupId,34);
		if (mainViewport!=null && !mainViewport.isHidden()) {
			mainViewport.revalidateScroll();
			int currentHeight = stretchedDimensions.height;
			int calcWidth = 16 * currentHeight / 9;
			return new Dimension(calcWidth, currentHeight);
		}
		return null;
	}
	private void setResizableScaling(int resizableScalingPercent) {
		configManager.setConfiguration("stretchedmode", "scalingFactor", resizableScalingPercent);
	}

	// Will continue trying to initialize until the GameState has been stabilized as logged in (e.g. layout == 2 or 3)
	// For some reason you can't use invoke() here or else it will delete the minimap orbs when you change interface mode.
	private void queuePluginInitialization()
	{
		//invokeLater will keep running until it returns true
		clientThread.invokeLater(() ->
		{
			// Uses getGameClientLayout() to determine when the game is ready to be initialized.
			int gameClientLayout = getGameClientLayout();
			if (gameClientLayout!=-1) {
                if (gameClientLayout == 2) {
                    initializePlugin();
                }
                return true;
			}
			return false;
		});
	}
	private void resizeSixteenByNine(){
		if (config.useSixteenByNine()){
			Dimension newDimension = calcSixteenByNineDimensions();
			if (newDimension != null) {
				resizeClient(newDimension);
			}
		}
	}

	// Initializes the plugin by modifying necessary widgets and creating custom sprites.
	// Ensures the minimap, inventory, and viewport are properly adjusted for fixed mode.
	// Also resizes 16:9 if config option is true.
	private void initializePlugin()
	{
		widgetsModified = true;
		repositionMinimapWidgets();
		createFixedSprites();
		resizeRenderViewport();
		resizeSixteenByNine();
		createGapWidgets();
	}

	// Saves the widget state under these conditions:
	// 1. The widget exists
	// 2. The widget has not already been saved
	//    - prevents overwriting of the vanilla state when functions are called more than once
	// The resetLast parameter is specified for the function resetWidgets() to allow for some saved widgets
	// to be reset after the others, preventing issue where parent widgets needs to be revalidated again.
	private void saveWidgetState(Widget widget, boolean resetLast) {
		if (widget == null) {
			return;
		}
		int widgetId = widget.getId();
		if (originalStates.containsKey(widgetId)) {
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
	// Overloaded function to allow for resetLast to be omitted.
	private void saveWidgetState(Widget widget) {
		saveWidgetState(widget,false);
	}

	// Determines the current game client layout mode.
	//
	// @return 1 if the layout is Fixed mode.
	// 2 if the layout is Resizable - Classic mode.
	// 3 if the layout is Resizable - Modern mode.
	// -1 if the layout cannot be determined.
	private int getGameClientLayout(){
		if (client.getGameState() == GameState.LOGGED_IN){
			Widget classicResizableWidget = client.getWidget(InterfaceID.RESIZABLE_VIEWPORT,0);
			if (classicResizableWidget != null && !classicResizableWidget.isHidden()){
				return 2;
			}
			Widget modernResizableWidget = client.getWidget(InterfaceID.RESIZABLE_VIEWPORT_BOTTOM_LINE,0);
			if (modernResizableWidget!=null && !modernResizableWidget.isHidden()){
				return 3;
			}
			Widget classicFixedWidget = client.getWidget(InterfaceID.FIXED_VIEWPORT,0);
			if (classicFixedWidget != null && !classicFixedWidget.isHidden()){
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
	 private void gameClientLayoutChanged() {
		 if (getGameClientLayout() == 2) {
			 queuePluginInitialization();
		 } else {
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
	private void fixWorldMapWikiStoreActAdvOrbs(){
		if (getGameClientLayout() == 2) {
			Widget worldMapOrb = client.getWidget(ComponentID.MINIMAP_WORLDMAP_ORB);
			Widget wikiBanner = client.getWidget(ComponentID.MINIMAP_WIKI_BANNER_PARENT);
			Widget storeOrb = client.getWidget(160, 42);
			Widget activityAdviserOrb = client.getWidget(160, 47);

			if (worldMapOrb != null && worldMapOrb.getOriginalX() == 0) {
				saveWidgetState(worldMapOrb);
				setWidgetCoordinates(worldMapOrb, 23, 109);
			}
			if (wikiBanner != null && wikiBanner.getOriginalX() == 0) {
				saveWidgetState(wikiBanner);
				setWidgetCoordinates(wikiBanner, 21, 129);
			}
			if (storeOrb != null && storeOrb.getOriginalX() == 85) {
				saveWidgetState(storeOrb);
				setWidgetParameters(storeOrb, 0+13, 83-6, 34, 34, 2, 0, 0, 0);
			}
			if (activityAdviserOrb != null && activityAdviserOrb.getOriginalX() == 55) {
				saveWidgetState(activityAdviserOrb);
				setWidgetParameters(activityAdviserOrb, 0+13, 50-6, 34, 34, 2, 0, 0, 0);
			}
		}
	}

	// Runs from onScriptPostFired() for the scriptId == 909 which resets the bounding boxes of game interfaces (e.g.
	// banks, deposit boxes, settings, etc). This function sets those back to their modified states.
	private void fixNestedInterfaceDimensions() {
		if (!widgetsModified) return;

		Widget clickWindow = client.getWidget(classicResizableGroupId, 92);
		if (clickWindow != null) {
			clickWindow.setXPositionMode(0);
			clickWindow.revalidate();
			for (Widget child : clickWindow.getStaticChildren()) {
				if (child.getOriginalWidth() == 250) {
					child.setOriginalWidth(0);
				}
				child.revalidateScroll();
			}
		}

		Widget oldSchoolBox = client.getWidget(oldSchoolBoxId);
		if (oldSchoolBox != null) {
			Widget parent = oldSchoolBox.getParent();
			if (parent != null && parent.getXPositionMode() == 1) {
				parent.setXPositionMode(0);
				parent.revalidate();
			}
			if (oldSchoolBox.getOriginalWidth() == 250) {
				oldSchoolBox.setOriginalWidth(0);
				oldSchoolBox.revalidate();
			}
			for (Widget child : oldSchoolBox.getStaticChildren()) {
				child.revalidateScroll();
			}
		}
	}
	// Runs from onScriptPostFired() for the script which fires and resets the inventory background sprite
	private void fixInvBackground() {
		if (widgetsModified && getGameClientLayout() == 2) {
			Widget invBackground = client.getWidget(classicResizableGroupId, 38);
			if (invBackground != null && invBackground.getSpriteId() == 897) {
				saveWidgetState(invBackground);
				invBackground.setSpriteId(1031);
			}
		}
	}

	// Resets all modified widgets. See removeAddedWidgets() for how all non-vanilla and plugin-created widgets are reset.
	// Runs whenever the user logs out, hops worlds, or changes the game client layout to something other than classic-resizable
	// There are a few widgets which need to be revalidated last to make sure everything resets properly, which is done last.
	private void resetWidgets() {
		clientThread.invoke(()->{
			removeAddedWidgets();
			resetRenderViewport();
			resetOriginalStates(); // sets widgetModified to false too
		});
	}
	private void resetOriginalStates(){
		List<Map.Entry<Integer, WidgetState>> resetLastEntries = new ArrayList<>();

		// Iterate through the originalStates map
		for (Map.Entry<Integer, WidgetState> entry : originalStates.entrySet()) {
			int widgetId = entry.getKey();
			WidgetState state = entry.getValue();

			// Skip resetLast widgets for now, and adds to the list to reset last.
			if (state.isResetLast()) {
				resetLastEntries.add(entry);
				continue;
			}

			// Retrieves the widget and resets it
			Widget widget = client.getWidget(widgetId);
			if (widget != null) {
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

		// Revalidates reset widgets
		clientThread.invoke(() -> {
			for (Map.Entry<Integer, WidgetState> entry : originalStates.entrySet()) {
				if (!entry.getValue().isResetLast()) {
					Widget widget = client.getWidget(entry.getKey());
					if (widget != null) {
						widget.revalidateScroll();
					}
				}
			}
		});

		// Process widgets with isResetLast() set to true
		for (Map.Entry<Integer, WidgetState> entry : resetLastEntries) {
			int widgetId = entry.getKey();
			WidgetState state = entry.getValue();

			// Retrieve the widget and reset it
			Widget widget = client.getWidget(widgetId);
			if (widget != null) {
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
			for (Map.Entry<Integer, WidgetState> entry : resetLastEntries) {
				Widget widget = client.getWidget(entry.getKey());
				if (widget != null) {
					widget.revalidateScroll();
				}
			}
		});

		// Clear the originalStates map after resetting, and sets the boolean to reflect that
		originalStates.clear();
		widgetsModified = false;
	}

	// Removes all widgets that plugin created (sprites surrounding the minimap/inventory)
	public void removeAddedWidgets(){
		//Deletes added minimap sprites + bottom border sprite
		Widget minimapDrawArea = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
		if (minimapDrawArea != null && minimapDrawArea.getParent() != null) {
			minimapDrawArea.getParent().deleteAllChildren();
		}

		// Deletes added inventory sprites
		Widget invDynamicParent = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_INVENTORY_PARENT);
		if (invDynamicParent!=null){
			invDynamicParent.deleteAllChildren();
		}

		// Deletes added gap sprites
		Widget gapWidgetParent = client.getWidget(classicResizableGroupId,0);
		if (gapWidgetParent!=null){
			gapWidgetParent.deleteAllChildren();
		}
	}

	// Resizes the client to the specified dimension. For some reason, the runelite client doesn't update the config
	//   when the client is resized via dragging edges. To work around this, it's first set 1 pixel wider than the
	//   dimensions passed, and then resized again after the client's next game tick.
	//
	//   Repurposed from the Client Resizer Plugin
	private void resizeClient(Dimension dimension) {
		// Validate and adjust the dimensions
		int processedWidth = Math.max(Math.min(dimension.width, 7680), Constants.GAME_FIXED_WIDTH);
		int processedHeight = Math.max(Math.min(dimension.height, 2160), Constants.GAME_FIXED_HEIGHT);
		Dimension processedGameSize = new Dimension(processedWidth, processedHeight);
		Dimension currentSize = configManager.getConfiguration("runelite", "gameSize", Dimension.class);
		if (processedGameSize.equals(currentSize)){
			Dimension processedGameSizePlus1 = new Dimension(processedWidth + 1, processedHeight);
			//log.info("Resized to {} x {}", processedWidth+1, processedHeight);
			configManager.setConfiguration("runelite", "gameSize", processedGameSizePlus1);
			resizeOnGameTick = true;
		}
		if (!processedGameSize.equals(currentSize)){
			log.info("Resized to {} x {}", processedWidth,processedHeight);
			configManager.setConfiguration("runelite", "gameSize", processedGameSize);
			resizeOnGameTick = false;
		}

	}
	// Sets a widget's coordinates, overloaded to be able to accept both ComponentIDs or the widget directly
	private void setWidgetCoordinates(int componentId, int newX, int newY) {
		setWidgetCoordinates(client.getWidget(componentId),newX,newY);
	}
	private void setWidgetCoordinates(Widget widget, int newX, int newY) {
		if (widget != null){
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
									 int newHeightMode) {
		if (widget != null) {
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
	private void repositionMinimapWidgets(){
		Widget minimapWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP);
		Widget minimapSprite = client.getWidget(classicResizableGroupId, 32);
		Widget minimapWidgetOrbsParent = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_ORB_HOLDER);
		Widget minimapWidgetOrbsInterface = client.getWidget(ComponentID.MINIMAP_CONTAINER);
		if (client.isResized() &&
				minimapWidget != null &&
				minimapSprite != null &&
				minimapWidgetOrbsParent != null &&
				minimapWidgetOrbsInterface != null)
		{
			saveWidgetState(minimapWidget,true);
			saveWidgetState(minimapSprite);
			saveWidgetState(minimapWidgetOrbsInterface);
			saveWidgetState(minimapWidgetOrbsParent);

			minimapSprite.setHidden(true);

			minimapWidget.setOriginalWidth(249);
			minimapWidget.setOriginalHeight(207);
			minimapWidget.revalidate();

			minimapWidgetOrbsParent.setOriginalWidth(249);
			minimapWidgetOrbsParent.setOriginalHeight(197);
			minimapWidgetOrbsParent.revalidate();

			minimapWidgetOrbsInterface.setOriginalWidth(249);
			minimapWidgetOrbsInterface.setOriginalHeight(197);
			minimapWidgetOrbsInterface.setWidthMode(0);
			minimapWidgetOrbsInterface.setHeightMode(0);
			minimapWidgetOrbsInterface.revalidateScroll();

			int[][] minimapViewportAdjustment = {
					{23, 44, 5},
					{24, 44, 45},
					{25, 44, 101},
					{26, 44, 126},
					{27, 44, 141},
					{28, 44, 156},
					{30, 50, 9},
					{32, 44, 1}
			};
			for (int[] widgetAdjustment : minimapViewportAdjustment) {
				int childId = widgetAdjustment[0];
				int newX = widgetAdjustment[1];
				int newY = widgetAdjustment[2];
				Widget wdgToAdj = (widgetAdjustment[0] == 30)
						? client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA)
						: client.getWidget(classicResizableGroupId, childId);
				if (wdgToAdj != null && wdgToAdj.getXPositionMode() == 2) {
					saveWidgetState(wdgToAdj,true);
					// Set the position mode to absolute
					wdgToAdj.setXPositionMode(0);

					// Set the absolute coordinates using setWidgetCoordinates
					setWidgetCoordinates(wdgToAdj, newX, newY);
					wdgToAdj.revalidate();
				}
			}

			//xp button
			setWidgetCoordinates(ComponentID.MINIMAP_XP_ORB,0,11);
			//health orb
			setWidgetCoordinates(ComponentID.MINIMAP_HEALTH_ORB, 0, 31);
			//prayer orb
			setWidgetCoordinates(ComponentID.MINIMAP_PRAYER_ORB, 0, 65);
			//run orb
			setWidgetCoordinates(ComponentID.MINIMAP_RUN_ORB, 10, 97);
			//spec orb
			setWidgetCoordinates(ComponentID.MINIMAP_SPEC_ORB, 32, 122);
			//compass orb clickbox, doesn't have have componentID
			setWidgetCoordinates(client.getWidget(classicResizableGroupId, 31), 26, 1);
			//compass orb viewbox, doesn't have have componentID
			setWidgetCoordinates(client.getWidget(classicResizableGroupId, 29), 28, 3);
			//world map orb, wiki banner, store orb, and activity adviser orb all handled under this function
			fixWorldMapWikiStoreActAdvOrbs();
		}
	}

	// Creates new widgets (defined by newSpriteConfigs) that weren't originally loaded in classic-resizable
	private void createFixedSprites() {
		// Get the parent widget the sprites should be under
		Widget minimapDrawArea = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
		Widget inventoryParentWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_INVENTORY_PARENT);
		// Define the configurations for all the sprites to be created.
		// Each row represents a sprite with the following columns:
		// [widget, type, spriteId, originalX, originalY, originalWidth, originalHeight, xPositionMode, yPositionMode, widthMode, heightMode, noclickthrough]
		if (minimapDrawArea != null && minimapDrawArea.getParent() != null && inventoryParentWidget != null) {
			int[][] newSpriteConfigs = {
					{1, 5, 1182, 29, 4, 172, 156, 0, 0, 0, 0, 0},  // centerMinimapSprite
					{1, 5, 1611, 0, 160, 249, 8, 1, 0, 0, 0, 0},   // bottomMinimapSprite
					{1, 5, 1037, 0, 4, 29, 156, 0, 0, 0, 0, 0},    // leftMinimapSprite
					{1, 5, 1038, 0, 4, 48, 156, 2, 0, 0, 0, 0},    // rightMinimapSprite
					{1, 5, 1039, 48, 0, 717, 4, 2, 0, 0, 0, 0},    // topThinBarRight
					{1, 5, 1441, 0, 0, 48, 4, 2, 0, 0, 0, 0},      // topThinBarLeft
					{2, 5, 1035, 0, 37, 28, 261, 2, 2, 0, 0, 0}, // right inv column
					{2, 5, 1033, 0, 38, 31, 133, 0, 0, 0, 0, 0}, // left inv column top half
					{2, 5, 1034, 3, 171, 28, 128, 0, 0, 0, 0, 0},  // left inv column bottom half
					{2, 5, 1033, 0, 0, 3, 170, 0, 2, 0, 0, 0} // left tiny strip to the left of bottom half
			};
			//Ensure the bounds on the parent container(s) are properly prepared.
			inventoryWidgetBoundsFix();

			// Create widgets using the configurations
			Widget minimapParentWidget = minimapDrawArea.getParent(); // same as client.getWidget(161,22); but uses ComponentID reference
			for (int[] newSpriteConfig : newSpriteConfigs) {
				Widget parentWidget = null;
				if (newSpriteConfig[0]==1){
					parentWidget = minimapParentWidget;
				} else if (newSpriteConfig[0]==2){
					parentWidget = inventoryParentWidget;
				}
				// extra null check here in case we add new added widgets later, should never be null given current newSpriteConfigs
				if (parentWidget!=null) {
					Widget minimapSprite = parentWidget.createChild(newSpriteConfig[1]);
					minimapSprite.setSpriteId(newSpriteConfig[2]);
					minimapSprite.setOriginalX(newSpriteConfig[3]);
					minimapSprite.setOriginalY(newSpriteConfig[4]);
					minimapSprite.setOriginalWidth(newSpriteConfig[5]);
					minimapSprite.setOriginalHeight(newSpriteConfig[6]);
					minimapSprite.setXPositionMode(newSpriteConfig[7]);
					minimapSprite.setYPositionMode(newSpriteConfig[8]);
					minimapSprite.setWidthMode(newSpriteConfig[9]);
					minimapSprite.setHeightMode(newSpriteConfig[10]);
					if (newSpriteConfig[11] == 1) {
						minimapSprite.setNoClickThrough(true);
					}
					minimapSprite.getParent().revalidate();
					minimapSprite.revalidate();
				}
			}
		}
	}
	// Fills in the gap between the inventory and the minimap to prevent render persistence due to there not being any widgets
	// in those locations.
	private void createGapWidgets(){
		Widget gapBackdropParent = client.getWidget(classicResizableGroupId,0);
		if (gapBackdropParent!=null) {
			Widget gapBackdrop = gapBackdropParent.createChild(5);
			gapBackdrop.setHeightMode(1);
			gapBackdrop.setOriginalWidth(249); //249
			gapBackdrop.setSpriteId(897); //297 897
			gapBackdrop.setXPositionMode(2);
			gapBackdrop.setSpriteTiling(true);
			gapBackdrop.revalidateScroll();
		}

		if (config.fillGapBorders()) {
			Widget invTopBorderParent = client.getWidget(classicResizableGroupId, 0);
			if (invTopBorderParent != null) {
				Widget invTopBorder = invTopBorderParent.createChild(5);
				invTopBorder.setXPositionMode(2);
				invTopBorder.setYPositionMode(2);
				invTopBorder.setOriginalY(336);
				invTopBorder.setOriginalWidth(249);
				invTopBorder.setOriginalHeight(21);
				invTopBorder.setSpriteId(173);// //297 //314/173
				invTopBorder.setSpriteTiling(true);
				invTopBorder.revalidateScroll();
			}
			// Bottom border for the minimap widget (only visible with gap);
			Widget minimapDrawArea = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
			if (minimapDrawArea!=null && minimapDrawArea.getParent() != null) {
				Widget minimapBottomBorder = minimapDrawArea.getParent().createChild(5); // same as client.getWidget(161,22).createChild(5) but uses ComponentID reference
				minimapBottomBorder.setOriginalY(153);
				minimapBottomBorder.setOriginalWidth(249);
				minimapBottomBorder.setOriginalHeight(21);
				minimapBottomBorder.setSpriteId(314);
				minimapBottomBorder.setSpriteTiling(true);
				minimapBottomBorder.setOriginalHeight(21);
				minimapBottomBorder.revalidateScroll();
			}
		}
	}
	// Sets up the coordinates and bounds on the inventory panel widget prior to creating the fixed background sprites
	// and prior to modifying the existing inventory sprites.
	private void inventoryWidgetBoundsFix() {
		Widget invParent = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_INVENTORY_PARENT);
		if (invParent != null) {
			saveWidgetState(invParent,true);
			invParent.setOriginalWidth(249);
			invParent.setOriginalHeight(336);
			invParent.revalidate();
		}

		Widget invBackground = client.getWidget(classicResizableGroupId, 38);
		if (invBackground != null) {
			saveWidgetState(invBackground);
			invBackground.setOriginalX(28);
			invBackground.setOriginalY(37);
			invBackground.setOriginalWidth(190);
			invBackground.setOriginalHeight(261);
			invBackground.setSpriteId(1031);
			invBackground.revalidate();
		}

		Widget invLeftColumn = client.getWidget(classicResizableGroupId, 39);
		if (invLeftColumn != null) {
			saveWidgetState(invLeftColumn);
			invLeftColumn.setHidden(true);
			invLeftColumn.revalidate();
		}
		Widget invRightColumn = client.getWidget(classicResizableGroupId, 40);
		if (invRightColumn != null) {
			saveWidgetState(invRightColumn);
			invRightColumn.setHidden(true);
			invRightColumn.revalidate();
		}

		Widget invBottomBarSprite = client.getWidget(classicResizableGroupId, 41);
		if (invBottomBarSprite != null) {
			saveWidgetState(invBottomBarSprite);
			invBottomBarSprite.setOriginalWidth(246);
			invBottomBarSprite.setOriginalHeight(37);
			invBottomBarSprite.setSpriteId(1032);
			invBottomBarSprite.revalidate();
		}

		Widget invBottomTabsParent = client.getWidget(classicResizableGroupId, 42);
		if (invBottomTabsParent != null) {
			saveWidgetState(invBottomTabsParent,true);
			invBottomTabsParent.setOriginalX(2);
			invBottomTabsParent.revalidate();
		}

		Widget invTopBarSprite = client.getWidget(classicResizableGroupId, 57);
		if (invTopBarSprite != null) {
			saveWidgetState(invTopBarSprite);
			invTopBarSprite.setOriginalY(298);
			invTopBarSprite.setOriginalWidth(249);
			invTopBarSprite.setOriginalHeight(38);
			invTopBarSprite.setSpriteId(1036);
			invTopBarSprite.revalidate();
		}

		Widget invTopTabsParent = client.getWidget(classicResizableGroupId, 58);
		if (invTopTabsParent != null) {
			saveWidgetState(invTopTabsParent,true);
			invTopTabsParent.setOriginalX(2);
			invTopTabsParent.revalidate();
		}

		Widget invViewportInterfaceController = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_INTERFACE_CONTAINER);
		if (invViewportInterfaceController != null) {
			saveWidgetState(invViewportInterfaceController);
			invViewportInterfaceController.setOriginalX(26 + 2);
			invViewportInterfaceController.revalidate();
		}
	}

	// Resizes the main viewport of the game so that no rendering occurs underneath the minimap/inventory.
	// This also consequently centers the camera properly, one of my main annoyances with the original resizable mode
	private void resizeRenderViewport(){
		Widget mainViewport = client.getWidget(classicResizableGroupId,91);
		if (mainViewport != null){
			saveWidgetState(mainViewport);
			// Width is set to the width of the inventory and minimap widgets because widthMode = 1 (subtracts
			//     that value from the parent widget's dimensions).
			mainViewport.setOriginalWidth(249);
			mainViewport.revalidate();
		}
	}

	// Reset's the plugin's changes on the render viewport back to the original fullscreen resizable mode.
	// Called during the resetWidgets() function.
	private void resetRenderViewport(){
		Widget mainViewport = client.getWidget(classicResizableGroupId,91);
		if (mainViewport != null){
			clientThread.invoke(()-> {
				mainViewport.setOriginalWidth(0);
				mainViewport.revalidateScroll();
			});
		}
	}
}
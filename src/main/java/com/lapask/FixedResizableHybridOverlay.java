package com.lapask;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import com.lapask.config.BackgroundMode;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

@Slf4j
public class FixedResizableHybridOverlay extends Overlay
{
	private static final int OVERLAY_WIDTH = 249;

	private final Client client;
	private final FixedResizableHybridConfig config;

	private static final Image GAP_BORDER =
		ImageUtil.loadImageResource(FixedResizableHybridPlugin.class, "/border15px.png");
	private static final Image TRANSPARENCY_WARNING =
		ImageUtil.loadImageResource(FixedResizableHybridPlugin.class, "/transparencyWarning.png");
	private static final BufferedImage TILABLE_BACKGROUND =
		ImageUtil.loadImageResource(FixedResizableHybridPlugin.class, "/tilable_background.png");

	private volatile BufferedImage customImage;
	private volatile String lastCustomImagePath;

	// Cache for the rendered background to avoid re-drawing/tiling each frame
	private BufferedImage backgroundCache;
	// Cache validation fields
	private int lastClientHeight = -1;
	private BackgroundMode lastBackgroundMode;
	private Color lastBackgroundColor;

	@Inject
	public FixedResizableHybridOverlay(Client client, FixedResizableHybridConfig config, FixedResizableHybridPlugin plugin)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS); // above background, below game widgets
		updateCustomImage(config.customImagePath());
	}

	public void updateCustomImage(String path)
	{
		if (path == null || path.isEmpty())
		{
			customImage = null;
			lastCustomImagePath = null;
			invalidateCache();
			return;
		}

		if (path.equals(lastCustomImagePath))
		{
			return;
		}

		try
		{
			File imageFile = new File(path);
			if (imageFile.exists())
			{
				customImage = ImageIO.read(imageFile);
				lastCustomImagePath = path;
				invalidateCache();
			}
			else
			{
				customImage = null;
				lastCustomImagePath = null;
				invalidateCache();
				log.warn("Custom background image file not found at path: {}", path);
			}
		}
		catch (IOException e)
		{
			log.error("Failed to load custom background image", e);
			customImage = null;
			lastCustomImagePath = null;
			invalidateCache();
		}
	}


	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Column geometry
		Dimension clientDimensions = client.getRealDimensions();
		int clientWidth = (int) clientDimensions.getWidth();
		int clientHeight = (int) clientDimensions.getHeight();
		Rectangle overlayBounds = new Rectangle(clientWidth - OVERLAY_WIDTH, 0, OVERLAY_WIDTH, clientHeight);

		// Cache widgets we reference more than once
		Widget inventoryWidget = client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
		Widget minimapWidget = client.getWidget(InterfaceID.Orbs.UNIVERSE);

		// 1) Background
		updateBackgroundCache(overlayBounds);
		if (backgroundCache != null)
		{
			// Draw the pre-rendered background cache. This is much faster than re-tiling every frame.
			graphics.drawImage(backgroundCache, overlayBounds.x, overlayBounds.y, null);
		}


		// 2) Gap borders (optional)
		if (config.useGapBorders())
		{
			if (inventoryWidget != null)
			{
				int borderX = inventoryWidget.getCanvasLocation().getX();
				int borderY = inventoryWidget.getCanvasLocation().getY() - 15;
				graphics.drawImage(GAP_BORDER, borderX, borderY, null);
			}
			if (minimapWidget != null)
			{
				int borderX = minimapWidget.getCanvasLocation().getX();
				int borderY = minimapWidget.getCanvasLocation().getY() + 158;
				graphics.drawImage(GAP_BORDER, borderX, borderY, null);
			}
		}

		// 3) Inventory transparency warning (independent of gap borders)
		if (config.invBackgroundWarning() && TRANSPARENCY_WARNING != null && inventoryWidget != null && !inventoryWidget.isHidden())
		{
			int invX = inventoryWidget.getCanvasLocation().getX();
			int invY = inventoryWidget.getCanvasLocation().getY();
			int invWidth = inventoryWidget.getWidth();
			int invHeight = inventoryWidget.getHeight();

			Rectangle inventoryBounds = new Rectangle(invX, invY, invWidth, invHeight);
			Rectangle paintBounds = inventoryBounds.intersection(overlayBounds);
			if (!paintBounds.isEmpty())
			{
				Shape oldClip = graphics.getClip();
				graphics.setClip(paintBounds);
				graphics.drawImage(TRANSPARENCY_WARNING, invX, invY, null);
				graphics.setClip(oldClip);
			}
		}

		// 4) Global tint over the column
		Color tint = config.gapBackgroundTint();
		if (tint.getAlpha() > 0)
		{
			Composite oldComposite = graphics.getComposite();
			graphics.setComposite(AlphaComposite.SrcAtop);
			graphics.setColor(tint);
			graphics.fillRect(overlayBounds.x, overlayBounds.y, overlayBounds.width, overlayBounds.height);
			graphics.setComposite(oldComposite);
		}

		return overlayBounds.getSize();
	}

	private void updateBackgroundCache(Rectangle overlayBounds)
	{
		final BackgroundMode currentMode = config.backgroundMode();
		final Color currentBgColor = config.backgroundColor();
		final int currentHeight = overlayBounds.height;

		// Check if cache is still valid
		if (backgroundCache != null && currentHeight == lastClientHeight && currentMode == lastBackgroundMode && currentBgColor.equals(lastBackgroundColor))
		{
			return;
		}

		// Invalidate and redraw the cache
		lastClientHeight = currentHeight;
		lastBackgroundMode = currentMode;
		lastBackgroundColor = currentBgColor;

		// Create a new cache image with the correct dimensions.
		backgroundCache = new BufferedImage(overlayBounds.width, overlayBounds.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = backgroundCache.createGraphics();

		try
		{
			BufferedImage imageToTile = null;
			if (currentMode == BackgroundMode.TILED_CUSTOM_IMAGE && customImage != null)
			{
				imageToTile = customImage;
			}
			else if (currentMode == BackgroundMode.TILED_STONE && TILABLE_BACKGROUND != null)
			{
				imageToTile = TILABLE_BACKGROUND;
			}

			if (imageToTile != null)
			{
				drawTiledImage(g, overlayBounds.width, overlayBounds.height, imageToTile);
			}
			else // Fallback to solid color
			{
				g.setColor(currentBgColor);
				g.fillRect(0, 0, overlayBounds.width, overlayBounds.height);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	private void drawTiledImage(Graphics2D g, int width, int height, BufferedImage image)
	{
		int imageH = image.getHeight();
		if (imageH <= 0) return;

		// Tile the image vertically down the graphics context
		for (int y = 0; y < height; y += imageH)
		{
			g.drawImage(image, 0, y, width, imageH, null);
		}
	}

	private void invalidateCache()
	{
		lastClientHeight = -1; // Force a cache redraw on the next frame
	}
}

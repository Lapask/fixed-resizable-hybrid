package com.lapask;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;

import com.lapask.config.BackgroundMode;
import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

public class FixedResizableHybridOverlay extends Overlay
{
	private static final int OVERLAY_WIDTH = 249;

	private final Client client;
	private final FixedResizableHybridConfig config;

	private static final Image GAP_BORDER =
		ImageUtil.loadImageResource(FixedResizableHybridPlugin.class, "/border15px.png");

	private static final BufferedImage TILABLE_BACKGROUND =
		ImageUtil.loadImageResource(FixedResizableHybridPlugin.class, "/tilable_background.png");

	@Inject
	public FixedResizableHybridOverlay(Client client, FixedResizableHybridConfig config, FixedResizableHybridPlugin plugin)
	{
		this.client = client;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Dimension clientDimensions = client.getRealDimensions();
		int clientWidth = (int) clientDimensions.getWidth();
		int clientHeight = (int) clientDimensions.getHeight();
		Rectangle overlayBounds = new Rectangle(clientWidth - OVERLAY_WIDTH, 0, OVERLAY_WIDTH, clientHeight);

		// 1) Background: solid or tiled, clipped to overlayBounds
		BackgroundMode mode = config.backgroundMode();
		if (mode == BackgroundMode.TILED_STONE && TILABLE_BACKGROUND != null)
		{
			// Clip so nothing can paint outside the 249px right column
			Shape oldClip = graphics.getClip();
			graphics.setClip(overlayBounds);

			// Anchor the tile to the overlay's top-left so one full 249px column is shown
			TexturePaint paint = new TexturePaint(
				TILABLE_BACKGROUND,
				new Rectangle2D.Double(
					overlayBounds.x, overlayBounds.y,
					TILABLE_BACKGROUND.getWidth(), TILABLE_BACKGROUND.getHeight()
				)
			);
			Paint oldPaint = graphics.getPaint();
			graphics.setPaint(paint);
			graphics.fillRect(overlayBounds.x, overlayBounds.y, overlayBounds.width, overlayBounds.height);
			graphics.setPaint(oldPaint);

			// Restore clip
			graphics.setClip(oldClip);
		}
		else
		{
			graphics.setColor(config.backgroundColor());
			graphics.fill(overlayBounds);
		}

		// 2) Borders: draw images (no per-border tint; global tint will apply below)
		if (config.useGapBorders())
		{
			// Inventory gap border
			Widget inventoryParent = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_INVENTORY_PARENT);
			if (inventoryParent != null)
			{
				int imageX = inventoryParent.getCanvasLocation().getX();
				int imageY = inventoryParent.getCanvasLocation().getY() - 15;
				graphics.drawImage(GAP_BORDER, imageX, imageY, null);
			}

			// Minimap gap border
			Widget minimapContainer = client.getWidget(ComponentID.MINIMAP_CONTAINER);
			if (minimapContainer != null)
			{
				int imageX = minimapContainer.getCanvasLocation().getX();
				int imageY = minimapContainer.getCanvasLocation().getY() + 158;
				graphics.drawImage(GAP_BORDER, imageX, imageY, null);
			}
		}

		// 3) Single global tint over everything in the overlay column
		Color tint = config.gapBackgroundTint(); // one tint source for background + borders
		if (tint.getAlpha() > 0)
		{
			Composite old = graphics.getComposite();
			graphics.setComposite(AlphaComposite.SrcAtop);
			graphics.setColor(tint);
			graphics.fillRect(overlayBounds.x, overlayBounds.y, overlayBounds.width, overlayBounds.height);
			graphics.setComposite(old);
		}

		return overlayBounds.getSize();
	}
}

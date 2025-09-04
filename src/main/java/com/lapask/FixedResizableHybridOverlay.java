package com.lapask;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;

import com.lapask.config.BackgroundMode;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
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
	private static final Image TRANSPARENCY_WARNING =
		ImageUtil.loadImageResource(FixedResizableHybridPlugin.class, "/transparencyWarning.png");
	private static final BufferedImage TILABLE_BACKGROUND =
		ImageUtil.loadImageResource(FixedResizableHybridPlugin.class, "/tilable_background.png");

	@Inject
	public FixedResizableHybridOverlay(Client client, FixedResizableHybridConfig config, FixedResizableHybridPlugin plugin)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS); // above background, below game widgets
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
		if (config.backgroundMode() == BackgroundMode.TILED_STONE && TILABLE_BACKGROUND != null)
		{
			Shape oldClip = graphics.getClip();
			graphics.setClip(overlayBounds);
			Paint oldPaint = graphics.getPaint();
			TexturePaint texturePaint = new TexturePaint(
				TILABLE_BACKGROUND,
				new Rectangle2D.Double(
					overlayBounds.x, overlayBounds.y,
					TILABLE_BACKGROUND.getWidth(), TILABLE_BACKGROUND.getHeight()));
			graphics.setPaint(texturePaint);
			graphics.fillRect(overlayBounds.x, overlayBounds.y, overlayBounds.width, overlayBounds.height);
			graphics.setPaint(oldPaint);
			graphics.setClip(oldClip);
		}
		else
		{
			graphics.setColor(config.backgroundColor());
			graphics.fill(overlayBounds);
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

				if (TRANSPARENCY_WARNING.getWidth(null) == invWidth &&
					TRANSPARENCY_WARNING.getHeight(null) == invHeight)
				{
					graphics.drawImage(TRANSPARENCY_WARNING, invX, invY, null);
				}
				else
				{
					graphics.drawImage(
						TRANSPARENCY_WARNING,
						invX, invY, invX + invWidth, invY + invHeight,
						0, 0,
						TRANSPARENCY_WARNING.getWidth(null), TRANSPARENCY_WARNING.getHeight(null),
						null
					);
				}
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
}

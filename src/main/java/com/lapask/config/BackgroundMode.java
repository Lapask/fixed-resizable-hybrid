package com.lapask.config;

import lombok.Getter;

@Getter
public enum BackgroundMode
{
	SOLID_COLOR("Solid Color"),
	TILED_STONE("Tiled Stone"),
	TILED_CUSTOM_IMAGE("Tiled Custom Image");

	private final String displayName;

	BackgroundMode(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
package com.lapask;

import com.google.common.collect.ImmutableSet;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

public class BankExpandConstants
{
	// Bank container that needs to be expanded
	private static final Map.Entry<Integer, Integer> BANK_CONTAINER = new AbstractMap.SimpleEntry<>(
		ComponentID.BANK_CONTAINER,
		0
	);

	// Seed vault container
	private static final Map.Entry<Integer, Integer> SEED_VAULT_CONTAINER = new AbstractMap.SimpleEntry<>(
		InterfaceID.SEED_VAULT,
		1
	);

	// Group Ironman shared storage
	private static final Map.Entry<Integer, Integer> GROUP_STORAGE_CONTAINER = new AbstractMap.SimpleEntry<>(
		InterfaceID.GROUP_STORAGE,
		5
	);

	// List of all containers that should expand when chat is hidden
	static final Set<Map.Entry<Integer, Integer>> EXPANDABLE_CONTAINERS = ImmutableSet
		.<Map.Entry<Integer, Integer>>builder()
		.add(BANK_CONTAINER)
		.add(SEED_VAULT_CONTAINER)
		.add(GROUP_STORAGE_CONTAINER)
		.build();
}

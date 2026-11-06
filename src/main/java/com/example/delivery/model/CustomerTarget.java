package com.example.delivery.model;

import net.minecraft.world.phys.Vec3;

/**
 * Represents the dynamically extracted customer destination for the active delivery order.
 *
 * <p>Example from gameplay video chat:</p>
 * {@code [🧭] Khách Chị Tư đang chờ ở X -7272 Y 153 Z 1944 (world). Cầm gói hàng bấm vào khách. La bàn đã chỉ về đó.}
 */
public record CustomerTarget(
	String customerName,
	int x,
	int y,
	int z,
	String worldName
) {
	/**
	 * Calculates the recipient's Nether X coordinate by dividing their Overworld X coordinate by 8.
	 */
	public int netherX() {
		return this.x / 8;
	}

	/**
	 * Calculates the recipient's Nether Z coordinate by dividing their Overworld Z coordinate by 8.
	 */
	public int netherZ() {
		return this.z / 8;
	}

	/**
	 * Computes the horizontal (XZ-plane) distance in the Nether between the player and {@code (netherX, netherZ)}.
	 */
	public double horizontalDistanceInNetherTo(double playerX, double playerZ) {
		double dx = playerX - (netherX() + 0.5);
		double dz = playerZ - (netherZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Computes the horizontal (XZ-plane) distance between the given player position and the customer:
	 * {@code sqrt((playerX - customerX)^2 + (playerZ - customerZ)^2)}.
	 */
	public double horizontalDistanceTo(double playerX, double playerZ) {
		double dx = playerX - (this.x + 0.5);
		double dz = playerZ - (this.z + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Computes the 3D Euclidean distance from the player position to the customer's block center.
	 */
	public double distance3DTo(Vec3 playerPos) {
		if (playerPos == null) {
			return Double.MAX_VALUE;
		}
		return playerPos.distanceTo(centerPos());
	}

	public Vec3 centerPos() {
		return new Vec3(this.x + 0.5, this.y, this.z + 0.5);
	}

	public Vec3 netherCenterPos(double y) {
		return new Vec3(netherX() + 0.5, y, netherZ() + 0.5);
	}

	public String formattedCoordinates() {
		String worldSuffix = (worldName != null && !worldName.isBlank()) ? " (" + worldName + ")" : "";
		return String.format("X: %d, Y: %d, Z: %d%s", x, y, z, worldSuffix);
	}

	public String formattedNetherCoordinates() {
		return String.format("X: %d, Z: %d (Nether)", netherX(), netherZ());
	}
}
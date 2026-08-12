package me.snowmii.dlss.render

import org.joml.Matrix4f

/**
 * Applies one frame's sub-pixel jitter to the world projection.
 *
 * DLSS accumulates detail by having each frame sample a slightly different point inside the
 * same pixel, which means the camera itself must move by that offset. The offset is a *shift
 * of the image*, not a rotation or a translation of the eye: it has to move every pixel by the
 * same amount regardless of how far away the geometry is, and it must not disturb depth.
 *
 * Pre-multiplying a translation onto the projection does exactly that. A clip-space
 * translation adds `offset * clip.w` to `clip.x`, and the perspective divide turns that back
 * into a constant `offset` in normalized device coordinates - the same shift at every depth -
 * while `clip.z` and `clip.w` are untouched, so reversed-Z depth still means what it meant.
 *
 * [DlssJitterOffset] owns the coordinate conversion. Minecraft's Vulkan viewport already handles
 * framebuffer orientation, so projection and DLSS keep the same signed offset on both axes.
 */
object DlssProjectionJitter {
	/**
	 * Writes the jittered projection into [dest] and returns it.
	 *
	 * [dest] is caller-owned so the render loop can reuse one matrix per frame rather than
	 * allocating; its previous contents are overwritten.
	 */
	@JvmStatic
	fun apply(projection: Matrix4f, offset: DlssJitterOffset, dest: Matrix4f): Matrix4f =
		dest.translation(offset.clipOffsetX, offset.clipOffsetY, 0f).mul(projection)
}

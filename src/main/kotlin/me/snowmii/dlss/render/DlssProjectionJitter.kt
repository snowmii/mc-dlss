package me.snowmii.dlss.render

import org.joml.Matrix4f

object DlssProjectionJitter {
	@JvmStatic
	fun apply(projection: Matrix4f, offset: DlssJitterOffset, dest: Matrix4f): Matrix4f =
		dest.translation(offset.clipOffsetX, offset.clipOffsetY, 0f).mul(projection)
}

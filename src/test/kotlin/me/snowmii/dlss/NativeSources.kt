package me.snowmii.dlss

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads one native translation unit as text, for the assertions that can only be made about the
 * source.
 *
 * Some of what the bridge guarantees is not observable through the ABI at all - that the DLSS
 * feature is created on the evaluation's own command buffer rather than at configure time, that
 * the preset is written before the creation that reads it, that teardown releases the feature
 * before the parameters it belongs to. Calling the ABI cannot distinguish those from their
 * broken forms; only the source can.
 *
 * Each caller names the file it means rather than searching a concatenation of all of them. A
 * range between two anchors therefore cannot silently span a file boundary, and each test says
 * where the behavior it asserts actually lives.
 *
 * Newlines are normalized because the assertions match the source text literally, and a Windows
 * checkout hands the same file back with CRLF.
 */
fun readNativeSource(relativePath: String): String =
	Files.readString(Path.of("streamline", "native", *relativePath.split("/").toTypedArray())).replace("\r\n", "\n")

package maestro.test

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.orchestra.ElementSelector
import maestro.test.drivers.FakeLayoutElement
import maestro.orchestra.AssertScreenshotCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.test.drivers.FakeDriver
import maestro.utils.FileAccessScope
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class ScreenshotAssertionArtifactsTest {
    @TempDir lateinit var root: Path

    private fun verifyComparison(change: String, threshold: String, succeeds: Boolean, cropped: Boolean = false) {
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val output = Files.createDirectory(root.resolve("artifacts"))
        Maestro(FakeDriver().apply {
            setLayout(FakeLayoutElement(id = "field", bounds = FakeLayoutElement.Bounds(10, 10, 110, 60)))
            open()
        }).use { maestro ->
            val captured = Buffer().also { runBlocking { maestro.takeScreenshot(it, false) } }.readByteArray()
            val fullImage = ImageIO.read(captured.inputStream())
            // FakeDriver uses a 2x pixel-to-grid scale.
            val image = if (cropped) fullImage.getSubimage(20, 20, 200, 100) else fullImage
            val reference = when (change) {
                "size" -> BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                "pixels" -> image.apply {
                    createGraphics().apply {
                        color = Color.WHITE
                        fillRect(0, 0, image.width / 2, image.height / 2)
                        dispose()
                    }
                }
                else -> image
            }
            val baseline = workspace.resolve("baseline.png")
            ImageIO.write(reference, "png", baseline.toFile())
            val baselineBytes = Files.readAllBytes(baseline)
            val command = MaestroCommand(assertScreenshotCommand = AssertScreenshotCommand(
                path = "baseline.png", thresholdPercentage = threshold, flowPath = workspace,
                cropOn = if (cropped) ElementSelector(idRegex = "field") else null,
            ))
            val orchestra = Orchestra(maestro, artifactsDir = output, scope = FileAccessScope.under(workspace))
            // Executing the same command twice verifies per-execution files and metadata.
            if (succeeds) {
                runBlocking { orchestra.runFlow(listOf(command, command)) }
            } else {
                assertThrows<MaestroException.AssertionFailure> {
                    runBlocking { orchestra.runFlow(listOf(command)) }
                }
            }
            val commands = jacksonObjectMapper().readTree(output.resolve("commands.json").toFile())
            assertThat(commands.size()).isEqualTo(if (succeeds) 2 else 1)
            val actualPaths = commands.map { entry ->
                val metadata = entry["metadata"]
                assertThat(metadata["status"].asText()).isEqualTo(if (succeeds) "COMPLETED" else "FAILED")
                val comparison = metadata["screenshotComparison"]
                assertThat(comparison["thresholdPercentage"].asDouble()).isEqualTo(threshold.toDouble())
                if (change == "size") {
                    assertThat(comparison.has("matchPercentage")).isFalse()
                } else {
                    val percentage = comparison["matchPercentage"].asDouble()
                    if (change == "none") assertThat(percentage).isEqualTo(100.0)
                    else assertThat(percentage).isLessThan(100.0)
                    assertThat(percentage >= threshold.toDouble()).isEqualTo(succeeds)
                }
                val actualPath = comparison["actualPath"].asText()
                if (cropped) {
                    val actual = ImageIO.read(output.resolve(actualPath).toFile())
                    assertThat(actual.width).isEqualTo(200)
                    assertThat(actual.height).isEqualTo(100)
                } else {
                    assertThat(Files.readAllBytes(output.resolve(actualPath))).isEqualTo(captured)
                }
                assertThat(metadata["artifacts"].map { it["path"].asText() }).contains(actualPath)
                actualPath
            }
            assertThat(actualPaths.toSet().size).isEqualTo(actualPaths.size)
            assertThat(Files.readAllBytes(baseline)).isEqualTo(baselineBytes)
            assertThat(Files.list(workspace).use { it.count() }).isEqualTo(1L)
            val manifest = jacksonObjectMapper().readTree(output.resolve("manifest.json").toFile())
            assertThat(manifest["entries"].any { it["relativePath"].asText() == "screenshots" }).isTrue()
        }
    }

    @Test fun `cropped assertion saves the cropped comparison image`() = verifyComparison("none", "100", true, cropped = true)
    @Test fun `exact match saves actual image and measured percentage`() = verifyComparison("none", "100", true)
    @Test fun `passing threshold reports measured percentage rather than threshold`() = verifyComparison("pixels", "0", true)
    @Test fun `mismatch retains actual image and measured percentage`() = verifyComparison("pixels", "100", false)
    @Test fun `size mismatch retains actual image without inventing a percentage`() = verifyComparison("size", "100", false)
}

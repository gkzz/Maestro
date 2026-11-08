package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

class EnvUtilsVersionTest {
    @Test
    fun `distribution version remains available for feature compatibility`() {
        val properties = Properties().apply {
            EnvUtils::class.java.classLoader.getResourceAsStream("version.properties").use { load(it) }
        }
        val distributionVersion = properties.getProperty("version")
        val version = EnvUtils.getCLIVersion()
        assertThat(version).isNotNull()
        assertThat(distributionVersion).startsWith(version.toString())
    }
}

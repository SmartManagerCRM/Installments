package com.smartmanager.installments

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.core.licensing.LicenseStatus
import com.smartmanager.installments.core.licensing.PublicKeyRing
import com.smartmanager.installments.data.repo.InMemorySecretStore
import com.smartmanager.installments.ui.AppRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Renders the REAL app (AppRoot with navigation, theme, strings, Room) on the JVM in Arabic/RTL, French and tablet size.
 * They prove: no crashes while composing every main screen with realistic data, RTL mirroring, localisation resources load,
 * and they save screenshots to app/build/screenshots for visual review.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class UiBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var env: Env
    protected lateinit var container: AppContainer

    protected fun boot(width: Int, height: Int, activate: Boolean = true, seed: Boolean = true) {
        env = Env()
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val ring = PublicKeyRing.fromPems(listOf("-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.public.encoded) + "\n-----END PUBLIC KEY-----"))
        container = AppContainer(env.context, env.db, env.clock, InMemorySecretStore(), ring)
        runBlocking {
            container.license.refresh()
            if (activate) {
                val now = env.clock.now / 1000
                val e = Base64.getUrlEncoder().withoutPadding()
                val payload = "{\"customerName\":\"Test Shop\",\"customerRef\":null,\"deviceId\":\"${container.license.deviceId}\",\"features\":[\"core\"],\"issuedAt\":${now - 100},\"licenseId\":\"L-UI\",\"product\":\"smartmanager-installments\",\"revision\":1,\"startAt\":${now - 100},\"type\":\"lifetime\",\"tz\":\"UTC\",\"v\":1}".toByteArray()
                val sig = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(payload); sign() }
                val file = "{\"alg\":\"ES256\",\"fmt\":\"SMI-LIC\",\"kid\":\"${PublicKeyRing.keyIdOf(kp.public.encoded)}\",\"payload\":\"${e.encodeToString(payload)}\",\"sig\":\"${e.encodeToString(sig)}\",\"v\":1}"
                check(container.license.importLicense(file).status == LicenseStatus.VALID)
            }
            container.settings.put("business.name", "متجر النور")
            if (seed && activate) seedData()
        }
        val size = WindowSizeClass.calculateFromSize(DpSize(width.dp, height.dp))
        rule.setContent { AppRoot(container, size) }
        rule.waitForIdle()
    }

    private suspend fun seedData() {
        val l = container.ledger; val c = container.customers
        val late = c.save(com.smartmanager.installments.data.repo.CustomerInput(null, "أحمد بن علي", "20111222", null, "تونس", null, ""))
        val ok = c.save(com.smartmanager.installments.data.repo.CustomerInput(null, "Élodie Martin", "55666777", null, "", null, ""))
        suspend fun contract(cust: String, first: java.time.LocalDate) = l.createContract(
            com.smartmanager.installments.data.repo.NewContract(cust, first.minusMonths(1), "TND", listOf(com.smartmanager.installments.data.repo.NewItem(null, "ثلاجة Samsung", 1, 2_400_000)), 0, 400_000,
                com.smartmanager.installments.core.schedule.Frequency.MONTHLY, com.smartmanager.installments.core.schedule.ScheduleGenerator.generate(2_000_000, 10, com.smartmanager.installments.core.schedule.Frequency.MONTHLY, first, 1000), "", "", true),
        )
        val today = env.clock.today()
        contract(late, today.minusDays(12)); val c2 = contract(ok, today.plusDays(5))
        l.recordPayment(com.smartmanager.installments.data.repo.PaymentRequest("ui-1", c2, 50_000, "CASH"))
    }

    /** Best-effort screenshot (visual review); never fails a test. */
    protected fun shot(name: String) {
        runCatching { rule.onRoot().captureRoboImage("build/screenshots/$name.png") }
    }

    protected fun str(id: Int) = env.context.getString(id)

    @After fun tearDown() { env.db.close() }
}

@Config(sdk = [34], qualifiers = "ar-rTN-w411dp-h891dp-xxhdpi")
class UiArabicPhoneTests : UiBase() {
    @Test fun dashboardRendersArabicRtlWithRealFigures() {
        boot(411, 891)
        rule.onNodeWithText(str(R.string.total_outstanding)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.recent_overdue_customers)).assertIsDisplayed()
        // the figures come from the database: 12 days late customer is listed
        rule.onAllNodesWithText("أحمد بن علي")[0].assertIsDisplayed()
        // RTL: "Home" (first tab) must be to the RIGHT of "Reports" (last tab)
        val home = rule.onNodeWithText(str(R.string.nav_home)).getBoundsInRoot()
        val reports = rule.onNodeWithText(str(R.string.nav_reports)).getBoundsInRoot()
        assertTrue("bottom navigation must be mirrored in RTL (home=$home reports=$reports)", home.left > reports.left)
        shot("dashboard-ar")
    }

    @Test fun walkThroughMainTabs() {
        boot(411, 891)
        for (tab in listOf(R.string.nav_customers, R.string.nav_payments, R.string.nav_contracts, R.string.nav_reports, R.string.nav_home)) {
            rule.onNodeWithText(str(tab)).performClick(); rule.waitForIdle()
        }
        rule.onNodeWithText(str(R.string.nav_customers)).performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("أحمد بن علي")[0].assertIsDisplayed()
        shot("customers-ar")
        rule.onAllNodesWithText("أحمد بن علي")[0].performClick(); rule.waitForIdle()
        rule.onNodeWithText(str(R.string.outstanding_balance)).assertIsDisplayed()
        shot("customer-detail-ar")
    }

    @Test fun notActivatedShowsActivationWithDeviceId() {
        boot(411, 891, activate = false)
        rule.onNodeWithText(str(R.string.activation_title)).assertIsDisplayed()
        rule.onNodeWithText(container.license.deviceId, substring = true).assertExists()
        rule.onNodeWithText(str(R.string.continue_read_only)).assertExists()
        shot("activation-ar")
        // read-only mode opens the app but hides write actions
        rule.onNodeWithText(str(R.string.continue_read_only)).performScrollTo().performClick(); rule.waitForIdle()
        val title = str(R.string.total_outstanding)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() } // Room flows load asynchronously
        rule.onNodeWithText(title).assertIsDisplayed()
    }
}

@Config(sdk = [34], qualifiers = "fr-rFR-w411dp-h891dp-xxhdpi")
class UiFrenchPhoneTests : UiBase() {
    @Test fun dashboardAndSettingsInFrench() {
        boot(411, 891)
        rule.onNodeWithText("Total restant dû").assertIsDisplayed()
        rule.onNodeWithText("Nouveau contrat").assertExists()
        // LTR: Home is left of Reports
        assertTrue(rule.onNodeWithText("Accueil").getBoundsInRoot().left < rule.onNodeWithText("Rapports").getBoundsInRoot().left)
        shot("dashboard-fr")
        rule.onNodeWithText("Contrats").performClick(); rule.waitForIdle()
        shot("contracts-fr")
    }

    @Test fun newContractWizardStepsRender() {
        boot(411, 891)
        rule.onNodeWithText("Nouveau contrat").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Étape 1 sur 6", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Élodie Martin").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Suivant").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Étape 2 sur 6", substring = true).assertIsDisplayed()
        shot("wizard-step2-fr")
    }
}

@Config(sdk = [34], qualifiers = "en-rUS-w1000dp-h700dp-mdpi")
class UiTabletTests : UiBase() {
    @Test fun tabletUsesNavigationRailAndTwoPaneCustomers() {
        boot(1000, 700)
        // navigation rail: items are stacked vertically on the start edge instead of a bottom bar
        val home = rule.onNodeWithText("Home").getBoundsInRoot(); val customers = rule.onNodeWithText("Customers").getBoundsInRoot()
        assertTrue("rail items are stacked vertically", customers.top > home.top && kotlin.math.abs(customers.left.value - home.left.value) < 40f)
        shot("dashboard-tablet")
        rule.onNodeWithText("Customers").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Select a customer to see the details").assertIsDisplayed() // two-pane placeholder
        shot("customers-tablet")
        rule.onNodeWithText("Élodie Martin").assertIsDisplayed()
    }
}

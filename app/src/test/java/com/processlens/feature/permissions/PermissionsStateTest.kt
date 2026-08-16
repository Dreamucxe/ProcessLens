package com.processlens.feature.permissions

import com.processlens.domain.model.PermissionGrant
import com.processlens.domain.model.PermissionGroup
import com.processlens.domain.model.PermissionInfo
import com.processlens.testing.assertContains
import com.processlens.testing.assertOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission inspector's derived state (Sections 21, 30, 42, 56).
 *
 * Everything asserted here is pure: [PermissionGroup.fromPermission] is a mapping, and
 * the ViewModel's nested state types derive their answers from a scan result without
 * touching Android. The scan itself needs a `PackageManager` and is exercised on-device;
 * what is verified here is the reasoning applied to whatever the scan found.
 *
 * Two things this class is really guarding:
 *
 *  - **Section 21's grouping.** Each of the nine groups the specification names has to be
 *    reachable from the canonical permission that belongs to it, both through the
 *    platform's own group string and — because OEM permissions frequently carry no group
 *    at all — through name inspection.
 *  - **Section 42's honesty about coverage.** Packages that could not be read are
 *    counted, and a group with no holders is omitted rather than displayed as "0 apps",
 *    which would leave a reader unable to tell "nothing holds this" from "the scan
 *    failed".
 */
class PermissionsStateTest {

    private fun permission(
        name: String,
        group: PermissionGroup = PermissionGroup.OTHER,
        grant: PermissionGrant = PermissionGrant.GRANTED,
        dangerous: Boolean = true,
        label: String? = null,
    ) = PermissionInfo(
        name = name,
        group = group,
        grant = grant,
        isDangerous = dangerous,
        label = label,
        description = null,
        isSignatureLevel = false,
    )

    private fun holder(
        packageName: String,
        label: String = packageName,
        isSystemApp: Boolean = false,
        held: List<PermissionInfo> = listOf(permission("android.permission.CAMERA")),
    ) = PermissionsViewModel.Holder(
        packageName = packageName,
        label = label,
        isSystemApp = isSystemApp,
        held = held,
    )

    private fun scan(vararg groups: PermissionsViewModel.GroupSummary) =
        PermissionsViewModel.Scan(
            groups = groups.toList(),
            packagesRead = 40,
            packagesUnreadable = 0,
            includedSystemApps = false,
        )

    // ----------------------------------------------------------------- the grouping

    @Test
    fun `each group Section 21 names is reachable from its platform group`() {
        // The spec lists these nine by name. Mapped through the platform's own group
        // string, which is what PackageManager reports where a permission has one.
        val expected = mapOf(
            "android.permission-group.LOCATION" to PermissionGroup.LOCATION,
            "android.permission-group.CAMERA" to PermissionGroup.CAMERA,
            "android.permission-group.MICROPHONE" to PermissionGroup.MICROPHONE,
            "android.permission-group.STORAGE" to PermissionGroup.STORAGE,
            "android.permission-group.NOTIFICATIONS" to PermissionGroup.NOTIFICATIONS,
            "android.permission-group.SENSORS" to PermissionGroup.SENSORS,
            "android.permission-group.PHONE" to PermissionGroup.PHONE,
            "android.permission-group.NEARBY_DEVICES" to PermissionGroup.NEARBY_DEVICES,
        )

        for ((platformGroup, group) in expected) {
            assertEquals(
                platformGroup,
                group,
                PermissionGroup.fromPermission("android.permission.SOMETHING", platformGroup),
            )
        }

        // Network has no platform permission group at all — it is derived from the name,
        // which is why the fallback below is load-bearing rather than a nicety.
        assertEquals(
            PermissionGroup.NETWORK,
            PermissionGroup.fromPermission("android.permission.ACCESS_NETWORK_STATE", null),
        )
    }

    @Test
    fun `platform groups that Android folds together are folded the same way`() {
        // Android splits media reads by type and files call log and SMS separately. The
        // inspector shows the group a person thinks in, so those collapse.
        assertEquals(
            PermissionGroup.STORAGE,
            PermissionGroup.fromPermission("android.permission.READ_MEDIA_IMAGES", "android.permission-group.READ_MEDIA_VISUAL"),
        )
        assertEquals(
            PermissionGroup.STORAGE,
            PermissionGroup.fromPermission("android.permission.READ_MEDIA_AUDIO", "android.permission-group.READ_MEDIA_AURAL"),
        )
        assertEquals(
            PermissionGroup.PHONE,
            PermissionGroup.fromPermission("android.permission.READ_CALL_LOG", "android.permission-group.CALL_LOG"),
        )
        assertEquals(
            PermissionGroup.PHONE,
            PermissionGroup.fromPermission("android.permission.RECEIVE_SMS", "android.permission-group.SMS"),
        )
        assertEquals(
            PermissionGroup.SENSORS,
            PermissionGroup.fromPermission(
                "android.permission.ACTIVITY_RECOGNITION",
                "android.permission-group.ACTIVITY_RECOGNITION",
            ),
        )
    }

    @Test
    fun `a permission with no platform group is grouped by inspecting its name`() {
        // The OEM case. A vendor permission carries no group, and filing every one of
        // them under "Other" would bury exactly the entries an investigation wants.
        val expected = mapOf(
            "android.permission.ACCESS_FINE_LOCATION" to PermissionGroup.LOCATION,
            "android.permission.CAMERA" to PermissionGroup.CAMERA,
            "android.permission.RECORD_AUDIO" to PermissionGroup.MICROPHONE,
            "android.permission.READ_EXTERNAL_STORAGE" to PermissionGroup.STORAGE,
            "android.permission.POST_NOTIFICATIONS" to PermissionGroup.NOTIFICATIONS,
            "android.permission.BODY_SENSORS" to PermissionGroup.SENSORS,
            "android.permission.READ_PHONE_STATE" to PermissionGroup.PHONE,
            "android.permission.READ_CONTACTS" to PermissionGroup.CONTACTS,
            "android.permission.READ_CALENDAR" to PermissionGroup.CALENDAR,
            "android.permission.BLUETOOTH_SCAN" to PermissionGroup.NEARBY_DEVICES,
            "android.permission.CHANGE_WIFI_STATE" to PermissionGroup.NETWORK,
        )

        for ((name, group) in expected) {
            assertEquals(name, group, PermissionGroup.fromPermission(name, null))
        }
    }

    @Test
    fun `an unrecognised platform group still falls through to the name`() {
        // A vendor group string must not short-circuit the mapping into "Other".
        assertEquals(
            PermissionGroup.MICROPHONE,
            PermissionGroup.fromPermission(
                "android.permission.RECORD_AUDIO",
                "com.oem.permission-group.VOICE_THINGS",
            ),
        )
    }

    @Test
    fun `a vendor permission that matches nothing is Other rather than mislabelled`() {
        assertEquals(
            PermissionGroup.OTHER,
            PermissionGroup.fromPermission("com.oem.permission.FINGERPRINT_UNLOCK_TUNING", null),
        )
    }

    @Test
    fun `the grouping is total`() {
        // Whatever a device reports, it lands somewhere: the screen cannot silently drop
        // a permission an app actually holds.
        val odd = listOf(
            "",
            "NOGROUPNODOTS",
            "com.oem..",
            "android.permission.WRITE_SECURE_SETTINGS",
        )
        for (name in odd) {
            // Just has to return a group rather than throw.
            PermissionGroup.fromPermission(name, null)
        }
        assertEquals(
            PermissionGroup.SYSTEM,
            PermissionGroup.fromPermission("android.permission.WRITE_SECURE_SETTINGS", null),
        )
    }

    // -------------------------------------------------------------------- one holder

    @Test
    fun `only dangerous permissions are counted as revocable`() {
        // Section 21: the app must not imply a grant can be turned off when Android will
        // not offer that. Install-time permissions are held and are not revocable.
        val subject = holder(
            "com.example",
            held = listOf(
                permission("android.permission.CAMERA", dangerous = true),
                permission("android.permission.INTERNET", dangerous = false),
                permission("android.permission.RECORD_AUDIO", dangerous = true),
            ),
        )

        assertEquals(2, subject.revocable.size)
        assertTrue(subject.revocable.none { it.name.endsWith("INTERNET") })
    }

    @Test
    fun `a holder of only install-time permissions has nothing revocable`() {
        val subject = holder(
            "com.example",
            held = listOf(permission("android.permission.INTERNET", dangerous = false)),
        )

        assertTrue(subject.revocable.isEmpty())
        // Still a holder: it has the capability, which is what the screen reports.
        assertEquals(1, subject.held.size)
    }

    @Test
    fun `a restricted grant is flagged`() {
        // Granted but appop-revoked. Reported, with the caveat, because a future OS or
        // appop change can make it live and hiding it would understate the app's reach.
        val restricted = holder(
            "com.example",
            held = listOf(
                permission("android.permission.READ_EXTERNAL_STORAGE", grant = PermissionGrant.RESTRICTED),
            ),
        )
        val plain = holder(
            "com.other",
            held = listOf(permission("android.permission.CAMERA", grant = PermissionGrant.GRANTED)),
        )

        assertTrue(restricted.hasRestricted)
        assertFalse(plain.hasRestricted)
    }

    @Test
    fun `the summary names the grants rather than counting them`() {
        // "2 permissions" is useless in a forensic tool; which two is the whole answer.
        val subject = holder(
            "com.example",
            held = listOf(
                permission("android.permission.CAMERA", label = "Camera"),
                permission("android.permission.RECORD_AUDIO", label = "Record audio"),
            ),
        )

        assertEquals("Camera, Record audio", subject.heldSummary)
    }

    @Test
    fun `an unlabelled permission falls back to its short name`() {
        // OEM permissions routinely have no label. The bare name is still informative;
        // an empty string would not be.
        val subject = holder(
            "com.example",
            held = listOf(
                permission("com.oem.permission.SILENT_INSTALL", label = null),
                permission("com.oem.permission.OTHER_THING", label = "  "),
            ),
        )

        assertEquals("SILENT_INSTALL, OTHER_THING", subject.heldSummary)
    }

    // ------------------------------------------------------------------ group summary

    @Test
    fun `group counts distinguish holders from revocable holders`() {
        val summary = PermissionsViewModel.GroupSummary(
            group = PermissionGroup.CAMERA,
            holders = listOf(
                holder("com.a", held = listOf(permission("android.permission.CAMERA", dangerous = true))),
                holder("com.b", held = listOf(permission("android.permission.CAMERA", dangerous = true))),
                holder("com.c", held = listOf(permission("com.oem.permission.CAMERA_HAL", dangerous = false))),
            ),
        )

        assertEquals(3, summary.holderCount)
        // Three apps can use the camera; the user can only take it away from two.
        assertEquals(2, summary.revocableHolderCount)
        assertEquals(0, summary.restrictedHolderCount)
    }

    @Test
    fun `restricted holders are counted separately`() {
        val summary = PermissionsViewModel.GroupSummary(
            group = PermissionGroup.STORAGE,
            holders = listOf(
                holder(
                    "com.a",
                    held = listOf(
                        permission("android.permission.READ_EXTERNAL_STORAGE", grant = PermissionGrant.RESTRICTED),
                    ),
                ),
                holder("com.b", held = listOf(permission("android.permission.READ_EXTERNAL_STORAGE"))),
            ),
        )

        assertEquals(1, summary.restrictedHolderCount)
    }

    // -------------------------------------------------------------- scan bookkeeping

    @Test
    fun `packages that could not be read are counted rather than dropped`() {
        // Section 42: from API 30 package visibility can hide an installed package, and a
        // total that quietly omitted it would understate the answer to "who holds this".
        val result = PermissionsViewModel.Scan(
            groups = emptyList(),
            packagesRead = 38,
            packagesUnreadable = 4,
            includedSystemApps = false,
        )

        assertEquals(42, result.totalPackages)
        assertTrue(result.totalPackages > result.packagesRead)
    }

    @Test
    fun `progress reports a fraction and never divides by zero`() {
        assertEquals(0.5f, PermissionsViewModel.Progress(done = 20, total = 40).fraction, 0.0001f)
        assertEquals(1f, PermissionsViewModel.Progress(done = 40, total = 40).fraction, 0.0001f)
        // An empty device would otherwise produce NaN and an indeterminate-looking bar.
        assertEquals(0f, PermissionsViewModel.Progress(done = 0, total = 0).fraction, 0.0001f)
    }

    // ------------------------------------------------------------------ visible state

    @Test
    fun `before the first scan there is nothing to show and nothing to imply`() {
        val state = PermissionsViewModel.State()

        assertTrue(state.isFirstLoad)
        assertFalse(state.isScanning)
        assertTrue(state.visible.isEmpty())
        assertTrue(state.availableGroups.isEmpty())
    }

    @Test
    fun `a running scan is distinguishable from a finished one`() {
        val scanning = PermissionsViewModel.State(progress = PermissionsViewModel.Progress(3, 40))
        assertTrue(scanning.isScanning)

        val finished = PermissionsViewModel.State(scan = scan(), progress = null)
        assertFalse(finished.isScanning)
        assertFalse(finished.isFirstLoad)
    }

    @Test
    fun `with no filter every scanned group is visible in scan order`() {
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(PermissionGroup.LOCATION, listOf(holder("com.a"))),
                PermissionsViewModel.GroupSummary(PermissionGroup.CAMERA, listOf(holder("com.b"))),
            ),
        )

        assertOrder(
            listOf("Location", "Camera"),
            state.visible.map { it.group.label },
        )
        assertOrder(
            listOf("Location", "Camera"),
            state.availableGroups.map { it.label },
        )
    }

    @Test
    fun `a group filter shows only that group`() {
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(PermissionGroup.LOCATION, listOf(holder("com.a"))),
                PermissionsViewModel.GroupSummary(PermissionGroup.CAMERA, listOf(holder("com.b"))),
            ),
            groupFilter = PermissionGroup.CAMERA,
        )

        assertEquals(PermissionGroup.CAMERA, state.visible.single().group)
        // The chips still offer every scanned group, so the filter can be changed.
        assertEquals(2, state.availableGroups.size)
    }

    @Test
    fun `a query filters the holders within each group`() {
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.CAMERA,
                    listOf(
                        holder("com.example.chrome", label = "Chrome"),
                        holder("com.example.maps", label = "Maps"),
                    ),
                ),
            ),
            query = "chrome",
        )

        assertEquals(1, state.visible.single().holders.size)
        assertEquals("Chrome", state.visible.single().holders.single().label)
    }

    @Test
    fun `a group whose holders all fail the query is omitted entirely`() {
        // Not shown as "Camera — 0 apps". An empty section reads as a failed scan.
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.CAMERA,
                    listOf(holder("com.example.maps", label = "Maps")),
                ),
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.MICROPHONE,
                    listOf(holder("com.example.chrome", label = "Chrome")),
                ),
            ),
            query = "chrome",
        )

        assertEquals(PermissionGroup.MICROPHONE, state.visible.single().group)
    }

    @Test
    fun `a query matches the package name as well as the label`() {
        // Investigations start from a package name as often as from an app name.
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.CAMERA,
                    listOf(holder("com.vendor.tracker", label = "Helpful Assistant")),
                ),
            ),
            query = "vendor",
        )

        assertEquals("Helpful Assistant", state.visible.single().holders.single().label)
    }

    @Test
    fun `a better match is listed first`() {
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.CAMERA,
                    listOf(
                        holder("com.example.b", label = "Anchor"),
                        holder("com.example.a", label = "Chrome"),
                    ),
                ),
            ),
            query = "chr",
        )

        // "Chrome" starts with the query; "Anchor" merely contains it.
        assertOrder(
            listOf("Chrome", "Anchor"),
            state.visible.single().holders.map { it.label },
        )
    }

    @Test
    fun `equally good matches are ordered alphabetically`() {
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.CAMERA,
                    listOf(
                        holder("com.one", label = "Dogs App"),
                        holder("com.two", label = "Cats App"),
                    ),
                ),
            ),
            query = "app",
        )

        assertOrder(
            listOf("Cats App", "Dogs App"),
            state.visible.single().holders.map { it.label },
        )
    }

    @Test
    fun `a blank query leaves the groups untouched`() {
        val camera = PermissionsViewModel.GroupSummary(
            PermissionGroup.CAMERA,
            listOf(holder("com.a"), holder("com.b")),
        )
        val state = PermissionsViewModel.State(scan = scan(camera), query = "   ")

        assertEquals(camera, state.visible.single())
    }

    @Test
    fun `a group filter and a query apply together`() {
        val state = PermissionsViewModel.State(
            scan = scan(
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.LOCATION,
                    listOf(holder("com.example.chrome", label = "Chrome")),
                ),
                PermissionsViewModel.GroupSummary(
                    PermissionGroup.CAMERA,
                    listOf(
                        holder("com.example.chrome", label = "Chrome"),
                        holder("com.example.maps", label = "Maps"),
                    ),
                ),
            ),
            groupFilter = PermissionGroup.CAMERA,
            query = "maps",
        )

        val visible = state.visible.single()
        assertEquals(PermissionGroup.CAMERA, visible.group)
        assertEquals("Maps", visible.holders.single().label)
    }

    @Test
    fun `an error is carried without discarding the scan already shown`() {
        // A failed rescan must not blank the screen: the previous answer is still the
        // best information available, and the banner says the refresh failed.
        val previous = scan(
            PermissionsViewModel.GroupSummary(PermissionGroup.CAMERA, listOf(holder("com.a"))),
        )
        val state = PermissionsViewModel.State(scan = previous, error = "Package query failed")

        assertEquals(1, state.visible.size)
        assertContains(state.error!!, "failed")
    }
}

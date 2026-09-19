# Use of the broad package (App) visibility (QUERY_ALL_PACKAGES) permission

**Confirmed page title:** "Use of the broad package (App) visibility (QUERY_ALL_PACKAGES) permission"
**Source URL:** https://support.google.com/googleplay/android-developer/answer/10158779?hl=en
**Fetched via:** https://r.jina.ai/https%3A%2F%2Fsupport.google.com%2Fgoogleplay%2Fandroid-developer%2Fanswer%2F10158779%3Fhl%3Den (HTTP 200)
**Fetch date:** 2026-09-19
**Parent topic:** Use of app permissions and APIs - https://support.google.com/googleplay/android-developer/topic/12798286?hl=en&ref_topic=2364761 (article 4 of 12)
**Notes:** Nav, footer, language selector, "Was this helpful?" widget and topic list stripped. All article prose below is verbatim; markdown link syntax and surrounding whitespace normalized only. The article contains NO "Alternatives" heading and no named replacement APIs (no `<queries>`, `ACTION_PICK_IMAGES`, or `ACTION_OPEN_DOCUMENT` text) - the only alternative-API guidance is a "Note" link to Package visibility filtering on Android.

---

## Use of the broad package (App) visibility (QUERY_ALL_PACKAGES) permission

Google Play restricts the use of [high-risk or sensitive permissions](https://play.google.com/about/privacy-security-deception/permissions/), including the `QUERY_ALL_PACKAGES` permission, which gives visibility into the inventory of installed apps on a given device. Play regards the inventory of installed apps queried from a user's device as personal and sensitive information, and the use of the permission is only permitted when your app's core user-facing functionality or purpose requires broad visibility into installed apps on the user's device.

If your app does not meet the requirements for acceptable use below, you must remove it from your app's manifest in order to comply with Play policy. Suggestions for policy-compliant alternative implementations are also detailed below.

If your app meets the policy requirements for the acceptable use of the `QUERY_ALL_PACKAGES` permission, you will be required to [declare this and any other high-risk permissions](https://support.google.com/googleplay/android-developer/answer/9214102) using the [Permissions Declaration Form](https://play.google.com/console/developers/app/app-content/permission-declarations) in Play Console.

Apps that fail to meet the policy requirements or do not submit the Permissions Declaration Form may be removed from Google Play.

**Important:** If you change how your app uses these restricted permissions, you must revise your declaration with updated and accurate information. Deceptive and undeclared uses of these permissions may result in a suspension of your app and/or termination of your developer account.

## When should you request the QUERY_ALL_PACKAGES permission?

The `QUERY_ALL_PACKAGES` permission only takes effect when your app targets Android API level 30 or later on devices running Android 11 or later.

To use this permission, your app must fall within permitted uses below, and have a core purpose to search for all apps on the device. You must be able to adequately justify why a less intrusive method of app visibility will not sufficiently enable your app's policy-compliant user-facing core functionality.

Core functionality is defined as the main purpose of the app. Without this core ability to search for all apps on the device, the app is "broken" or becomes unusable. The core functionality, as well as any core features that comprise this core functionality, must all be prominently documented and promoted in the app's description.

[Collapse All / Expand All]

### Permitted uses of the QUERY_ALL_PACKAGES permission

Permitted uses involve apps that must discover any and all installed apps on the device, for awareness or interoperability purposes may have eligibility for the permission. Permitted uses include device search, antivirus apps, file managers, and browsers.

Apps granted access to this permission must comply with the [User Data policies](https://support.google.com/googleplay/android-developer/answer/10144311), including [Prominent Disclosure and Consent Requirement](https://support.google.com/googleplay/android-developer/answer/10144311), and may not extend its use to undisclosed or invalid purposes.

### Exceptions

Google Play may provide a temporary exception to the following apps that do not qualify as permitted uses designated above.

* Real-money gambling apps where the core purpose of the app is real-money gambling and where the app requires broad package visibility in order to comply with technical standards mandated by applicable geofencing regulations.
* Apps that have a verifiable core purpose facilitating financial-transactions involving financially regulated instruments (for example, dedicated banking, dedicated digital wallets) may obtain broad visibility into installed apps solely for security-based purposes.

In accordance with our [Personal loans policy](https://support.google.com/googleplay/android-developer/answer/9876821#personal-loans), use for the purpose of any personal loan, credit, or reason facilitating access to personal loans is not eligible for this exception.

Developers are encouraged to proactively implement alternative security or fraud prevention solutions that do not rely on the `QUERY_ALL_PACKAGES` permission.

### Invalid uses

Below is a list of use cases that won't be allowed to request the `QUERY_ALL_PACKAGES` permission:

* Where the use of the permission is not directly related to the core purpose of the app.
  * This includes Peer-to-Peer (P2P) sharing. P2P must be the core purpose of the app in order to qualify as a permitted use.
* When the data is acquired for the purpose of sale.
* When app inventory data queried from Play-distributed apps are intended to be sold or shared for analytics or ads monetization purposes.
* When the required task can be done with a less broad app-visibility method.

**Note:** This list is not exhaustive. For in-depth guidance on alternative options and best practices, see [Package visibility filtering on Android](https://developer.android.com/training/package-visibility).

---

## Absence log (terms checked in the fetched article text)

The following terms requested in the research brief do NOT appear anywhere in article 10158779 as fetched (checked against the full r.jina.ai rendering):

| Term requested | Present? |
| --- | --- |
| "device search" | YES - in permitted uses |
| "antivirus apps" | YES - in permitted uses ("antivirus apps") |
| "file managers" | YES - in permitted uses (plural form only; the article does not elaborate on file managers) |
| "browsers" | YES - in permitted uses |
| "app inventory" | YES - in the definition ("visibility into the inventory of installed apps") and in invalid use 3 ("app inventory data queried from Play-distributed apps") |
| "peer-to-peer sharing" | YES - invalid uses, P2P bullet |
| "companion device" | NO - not mentioned in this article |
| "task automation" | NO - not mentioned in this article |
| "financial apps" | PARTIAL - the Exceptions section covers "financially regulated instruments (for example, dedicated banking, dedicated digital wallets)" but the phrase "financial apps" does not appear |
| `<queries>` element | NO - only the link to developer.android.com/training/package-visibility |
| "package visibility filtering" | PARTIAL - appears only in the linked page title "Package visibility filtering on Android" |
| `ACTION_PICK_IMAGES` | NO |
| `ACTION_OPEN_DOCUMENT` | NO |
| "listing other apps" as a core purpose | NO explicit statement - the article permits apps whose core purpose is to "search for all apps on the device" / "discover any and all installed apps ... for awareness or interoperability purposes"; a generic "list installed apps" core purpose is NOT named as a permitted use |

No "Alternatives" heading exists in this article. The only remediation guidance is: (a) remove the permission from the manifest, and (b) the closing Note linking to Package visibility filtering on Android.

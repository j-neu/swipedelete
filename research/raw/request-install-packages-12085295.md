# Use of the REQUEST_INSTALL_PACKAGES permission

**Confirmed page title:** "Use of the REQUEST_INSTALL_PACKAGES permission"
**Source URL:** https://support.google.com/googleplay/android-developer/answer/12085295?hl=en
**Fetched via:** https://r.jina.ai/https://support.google.com/googleplay/android-developer/answer/12085295?hl=en (HTTP 200)
**Fetch date:** 2026-09-19
**Parent topic:** Use of app permissions and APIs - https://support.google.com/googleplay/android-developer/topic/12798286?hl=en&ref_topic=2364761 (article 5 of 12)
**Notes:** Nav, footer, language selector, "Was this helpful?" widget and topic list stripped. All article prose below is verbatim; markdown link syntax and surrounding whitespace normalized only. The Play Console Permissions Declaration Form link in the source renders with the fetching account's developer/app IDs embedded; the canonical public form landing page is https://support.google.com/googleplay/android-developer/answer/9214102. The article contains NO "Alternatives" heading and no explicit "install unknown apps" / "sideloading" / "app store" / "APK installer" wording - see the absence log.

---

## Use of the REQUEST_INSTALL_PACKAGES permission

Google Play restricts the use of [high risk or sensitive permissions](https://play.google.com/about/privacy-security-deception/permissions/), including the `REQUEST_INSTALL_PACKAGES` permission, which allows an application to request installing packages. Apps targeting API level 26 or newer must hold this permission in order to use [Intent.ACTION_INSTALL_PACKAGE](https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE) or the [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller) API. To use this permission, your app's core functionality must include:

1. Sending or receiving app packages, AND
2. Enabling user-initiated installation of app packages.

If your app does not meet the requirements for acceptable use below, you must remove it from your app's manifest in order to comply with Google Play policy. Suggestions for policy-compliant alternative implementations are also detailed below.

If your app meets the policy requirements for the acceptable use of the `REQUEST_INSTALL_PACKAGES` permission, you will be required to [declare this and any other high-risk permissions](https://support.google.com/googleplay/android-developer/answer/9214102) using the [Permissions Declaration Form](https://play.google.com/console/u/0/developers/8612337217832066595/app/4974640535802978484/app-content/permission-declarations) in Play Console.

Apps that fail to meet the policy requirements or do not submit the Permissions Declaration Form may be removed from Google Play.

Important: If you change how your app uses these restricted permissions, you must revise your declaration with updated and accurate information. Deceptive and undeclared uses of these permissions may result in a suspension of your app and/or termination of your developer account.

## When should you request the REQUEST_INSTALL_PACKAGES permission?

The `REQUEST_INSTALL_PACKAGES` permission only takes effect when your app targets Android API level 26 or later on devices running Android 8 or later.

To use this permission, your app must fall within permitted uses below, and have a core purpose to enable installation of packages. Core functionality is defined as the main purpose of the app. Without this core ability to install additional applications on the device, the app is "broken" or becomes unusable. The core functionality, and any core features that comprise this core functionality, must all be prominently documented and promoted in the app's description.

[Collapse All / Expand All]

### Permitted uses of the REQUEST_INSTALL_PACKAGES permission

Apps that must enable installation of app packages for interoperability purposes may be eligible for this permission. Permitted uses include:

* Web browsing or search; OR
* Communication services that support attachments; OR
* File sharing, transfer or management; OR
* Enterprise device management.
* Backup & restore
* Device Migration / Phone Transfer

Apps granted access to this permission must comply with the [User Data policies](https://support.google.com/googleplay/android-developer/answer/10144311), including [Prominent Disclosure and Consent Requirement](https://support.google.com/googleplay/android-developer/answer/10144311), and may not extend its use to undisclosed or invalid purposes.

### Invalid uses

Below is a list of use cases that won't be allowed to request the REQUEST_INSTALL_PACKAGES permission:

* Where the use of the permission is not directly related to the core purpose of the app.
  * This includes Peer-to-Peer (P2P) sharing. P2P must be the core purpose of the app in order to qualify as a permitted use.

* When the required task can be done with a less intrusive method.

Note: This list is not exhaustive.

## Frequently asked questions

### Why does Google want to introduce this policy?

`REQUEST_INSTALL_PACKAGES` permission provides apps with the ability to install new packages on a user's device. We are committed to preventing abuse on the Android platform and protecting users from apps that self update using any method other than Google Play's update mechanism or download harmful APKs. To this end, we are introducing the Request Install Package policy to explain which permitted functionalities and actions are allowed for the permission.

### How do I know whether I have a permitted use case for this permission or not?

We encourage you to review the policy carefully for permitted use cases and make any required changes to keep your app compliant. If your app does not meet the criteria for a permitted use case, you need to remove this permission from your app before the policy effective date in order to keep your app compliant.

### What are the timelines to ensure compliance?

The new policy will be enforced starting July 11, 2022.

### Will this new clarification impact app review timelines?

There is no expected delay on the app reviewing timelines with regards to this update.

### What if I need additional time to comply with the requirements?

The new policy will become effective on July 11, 2022. We encourage you to review the policy carefully for permitted use cases in a timely manner and make any required changes to keep your app compliant. If your app does not meet the criteria for a permitted use case, you need to remove this permission from your app before the policy effective date in order to keep your app compliant.

### My app has been removed from Google Play due to the violation of this policy. I believe this was in error, what should I do?

Please review guidelines on this [Help Center page](https://support.google.com/googleplay/android-developer/answer/2477981) for options.

---

## Absence log (terms checked in the fetched article text)

| Term requested | Present? |
| --- | --- |
| "app stores" | NO - not mentioned |
| "file managers" | PARTIAL - the permitted-use bullet reads "File sharing, transfer or management" (no noun "file managers") |
| "APK installers" | NO - not mentioned |
| "sideloading" | NO - not mentioned |
| "device management" | YES - "Enterprise device management." |
| "backup/restore" | YES - "Backup & restore" |
| "peer-to-peer sharing" | YES - Invalid uses, P2P bullet |
| "install unknown apps" | NO - not mentioned (that setting is Android platform behavior, not policy text here) |
| Self-updating outside Google Play | YES, in the FAQ rationale: "protecting users from apps that self update using any method other than Google Play's update mechanism or download harmful APKs." |
| Installing apps unattended / without user action | NOT EXPLICIT - the requirement is framed positively as "Enabling user-initiated installation of app packages" (core-functionality rule 2). There is no sentence banning unattended/silent installation. |
| User consent | PARTIAL - only via the incorporated User Data policies Prominent Disclosure and Consent Requirement link; no standalone consent mechanics in this article. |
| "Alternatives" section | NO heading exists. The intro only says "Suggestions for policy-compliant alternative implementations are also detailed below", and the only concrete alternative given is the Invalid uses bullet "When the required task can be done with a less intrusive method." |

**Enforcement dates stated in the article:** policy enforced starting July 11, 2022 (the only date given).

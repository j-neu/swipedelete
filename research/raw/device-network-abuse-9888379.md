# Device and Network Abuse - Play Console Help

- **Page title (confirmed via reader proxy):** `Device and Network Abuse - Play Console Help`
- **Source URL:** https://support.google.com/googleplay/android-developer/answer/9888379?hl=en
- **Retrieved via:** https://r.jina.ai/https://support.google.com/googleplay/android-developer/answer/9888379?hl=en (HTTP 200)
- **Retrieved:** 2026-09-19 (reader-proxy text rendering of the live article)
- **Notes:** Repeated nav/footer boilerplate and image placeholder tokens (e.g. `![Image 1](...)`) stripped. Body text is otherwise verbatim, including inline hyperlinks. Punctuation was normalized to ASCII (straight apostrophes/dashes) when writing this file; wording is unchanged. This single article contains FIVE policy sections: the core Device and Network Abuse policy, plus Permissions for Foreground Services (FGS), User-Initiated Data Transfer Jobs, Flag Secure Requirements, and Apps that Run On-device Android Containers.

---

_**Disclaimer:** Policy summaries and Key Considerations are overviews only; always refer to the full policy for compliance. The full policy takes precedence in case of conflict._

**Policy Summary**

Google Play prohibits your app (or any third-party SDKs in your app) from unauthorized access or interference with the user's device, other devices, network, API, or service, other apps on the device, any Google service, or an authorized carriers network. This encompasses a range of harmful, high risk, or disruptive behaviors, such as performing self-updates outside the Play Store, downloading unauthorized executable code, exploiting security vulnerabilities, facilitating hacking, or creating game cheats that affect other apps. Protecting the integrity of the user's device and the broader ecosystem is paramount. Please review the full policy to ensure compliance.

**Full Policy**

We dont allow apps that interfere with, disrupt, damage, or access in an unauthorized manner the users device, other devices or computers, servers, networks, application programming interfaces (APIs), or services, including but not limited to other apps on the device, any Google service, or an authorized carriers network.

Apps on Google Play must comply with the default Android system optimization requirements documented in the [Core App Quality guidelines for Google Play](http://developer.android.com/distribute/essentials/quality/core.html#listing).

An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism. Likewise, an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser).

Apps or third-party code, like SDKs, with interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time (for example, not packaged with the app) must not allow potential violations of Google Play policies.

We dont allow code that introduces or exploits security vulnerabilities. Check out the [App Security Improvement Program](https://developer.android.com/google/play/asi.html#campaigns) to find out about the most recent security issues flagged to developers.

#### Examples of common Device and Network Abuse violations:

*   Apps that block or interfere with another app displaying ads.
*   Game cheating apps that affect the gameplay of other apps.
*   Apps that facilitate or provide instructions on how to hack services, software or hardware, or circumvent security protections.
*   Apps that access or use a service or API in a manner that violates its terms of service.
*   Apps that are not [eligible for allowlisting](https://developer.android.com/training/monitoring-device-state/doze-standby.html#whitelisting-cases) and attempt to bypass [system power management](https://developer.android.com/training/monitoring-device-state/doze-standby.html).
*   Apps that facilitate proxy services to third parties may only do so in apps where that is the primary, user-facing core purpose of the app.
*   Apps or third party code (for example, SDKs) that download executable code, such as dex files or native code, from a source other than Google Play.
*   Apps that install other apps on a device without the user's prior consent.
*   Apps that link to or facilitate the distribution or installation of malicious software.
*   Apps or third party code (for example, SDKs) containing a webview with added JavaScript Interface that loads untrusted web content (for example, http:// URL) or unverified URLs obtained from untrusted sources (for example, URLs obtained with untrusted Intents).
*   Apps that use the [full-screen intent permission](https://developer.android.com/about/versions/14/behavior-changes-14?hl=en#secure-fsi) to force user interaction with disruptive ads or notifications.
*   Apps that circumvent [Android sandbox protections](https://source.android.com/docs/security/app-sandbox) in order to derive user activity or user identity from other apps.

**Key Considerations**

**Do** / **Don't**

- Ensure your app and any integrated SDKs comply with Android system optimization requirements in the [Core App Quality guidelines](https://developer.android.com/docs/quality-guidelines/core-app-quality#listing). / Don't install other apps on a device without explicit user consent.
- Respect the [`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) setting, and on-device containers must respect [`REQUIRE_SECURE_ENV`](https://developer.android.com/training/basics/intents/limit-play-loading). / Don't facilitate proxy services to third-parties unless it's the primary, user-facing core purpose of the app.
- Use [User-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt) only for user-initiated network data transfers that run only as long as needed. / Don't use third-party SDKs in your app that download executable code (like dex or .so files) from outside Google Play (except in VMs/interpreters).
- Check out the [App Security Improvement Program](https://developer.android.com/google/play/asi.html#campaigns) to find out about the most recent security issues flagged to developers. / Don't bypass [System power management](https://developer.android.com/training/monitoring-device-state/doze-standby.html) unless eligible.
- Comply with [Foreground Services](https://support.google.com/googleplay/android-developer/answer/9888379#fgs) policy. / Don't block or interfere with another app displaying ads.
- (no Do item) / Don't use the [`FULL-SCREEN INTENT`](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi) permission to force interaction with disruptive ads and notifications.

* * *

## Permissions for Foreground Services (FGS)

**Policy Summary**

The Foreground Service permission policy ensures user transparency, privacy, and optimal device performance. For apps targeting Android 14+ you must declare valid Foreground Service (FGS) types in the manifest and Play Console, providing descriptions, user impact, and a demo video justifying their use based on user-initiated, perceptible actions. Please review the full policy to ensure compliance.

**Full Policy**

The Foreground Service permission ensures the appropriate use of user-facing foreground services. For apps targeting Android 14 and above, you must specify a valid foreground service type for each foreground service used in your app, and declare the [foreground service permission](https://support.google.com/googleplay/android-developer/answer/13392821) that is appropriate for that type. For example, if your apps use case requires map geolocation, you must declare the [FOREGROUND_SERVICE_LOCATION](https://developer.android.com/reference/android/Manifest.permission#FOREGROUND_SERVICE_LOCATION) permission in your apps manifest.

Apps are only allowed to declare a foreground service permission if the use:

*   provides a feature that is beneficial to the user and relevant to the core functionality of the app
*   is initiated by the user or is user perceptible (for example, audio from playing a song, cast media to another device, accurate and clear user notification, user request to upload a photo to the cloud)
*   can be terminated or stopped by the user
*   cant be interrupted or deferred by the system without causing a negative user experience or causing the user anticipated feature to not work as intended (for example, a phone call needs to start immediately and cant be deferred by the system)
*   runs only for as long as necessary to complete the task

The following foreground service use cases are exempt from the above criteria:

*   foreground service types [systemExempted](https://developer.android.com/about/versions/14/changes/fgs-types-required#system-exempted) or [shortService](https://developer.android.com/about/versions/14/changes/fgs-types-required#short-service);
*   foreground service type dataSync only when using [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery) features

The use of foreground service is further explained [here](https://support.google.com/googleplay/android-developer/answer/13392821).

**Key Considerations**

**Do** / **Don't**

- Run FGS only for as long as necessary to complete the task. / Don't use FGS if system management of your task doesnt break the user experience in your app. Consider alternatives like WorkManager.
- Ensure FGS provides a user-beneficial core app feature, is initiated by the user, is visible in notifications or is user perceptible (for example, audio from playing a song). / Don't declare invalid or inaccurate FGS types in your apps manifest.
- Submit a [declaration form](https://goo.gle/play-permission-decl-form) in your Play Console if targeting Android 14+ and describe the use case for each [Foreground Services (FGS)](https://support.google.com/googleplay/android-developer/answer/9888379?sjid=1631932611128229759-NC&visit_id=638822556734167291-4052248586&rd=1#foreground_service) permission used. Ensure the appropriate FGS type is selected.

* * *

## User-Initiated Data Transfer Jobs

**Policy Summary**

To maintain user control and prevent prolonged background activity Google Play provides strict guidelines for Apps using the user-initiated data transfer jobs API. Data transfers must be directly prompted by the user, ensuring that the app executes a command rather than initiating transfers independently. These transfers are exclusively for network data transfer tasks and must only operate for the duration required to complete the requested action. Please review the full policy to ensure compliance.

**Full Policy**

Apps are only allowed to use the [user-initiated data transfer jobs](https://developer.android.com/partners/android-14/changes/user-initiated-data-transfers) API if the use is:

*   initiated by the user
*   for network data transfer tasks
*   runs only for as long as necessary to complete the data transfer

The usage of the user-initiated data transfer jobs API is further explained [here](https://developer.android.com/partners/android-14/changes/user-initiated-data-transfers).

**Key Considerations**

**Do** / **Don't**

- Start transfers with user action. / Don't initiate transfers automatically.
- Use for network data transfer tasks only. / Don't use the API for non-network tasks.
- Stop when the transfer is finished. / Don't run longer than needed.

* * *

## Flag Secure Requirements

**Policy Summary**

FLAG_SECURE is an app-declared display flag indicating sensitive data in the UI should be limited to secure surfaces, preventing screenshots and non-secure display viewing and capturing. Developers use this when content shouldn't be broadcast or viewed outside the app/device. Google Play requires all apps to respect other apps' FLAG_SECURE declarations and not bypass them for security and privacy. Please review the full policy to ensure compliance.

**Full Policy**

[FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) is a display flag declared in an apps code to indicate that its UI contains sensitive data intended to be limited to a secure surface while using the app. This flag is designed to prevent the data from appearing in screenshots or from being viewed on non-secure displays. Developers declare this flag when the apps content should not be broadcast, viewed, or otherwise transmitted outside of the app or users device.

For security and privacy purposes, all apps distributed on Google Play are required to respect the FLAG_SECURE declaration of other apps. Meaning, apps must not facilitate or create workarounds to bypass the FLAG_SECURE settings in other apps.

Apps that qualify as an [Accessibility Tool](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en) are exempt from this requirement, as long as they do not transmit, save, or cache FLAG_SECURE protected content for access outside of the user's device.

**Key Considerations**

**Do** / **Don't**

- [Declare](https://goo.gle/play-permission-decl-form) `FLAG_SECURE` for sensitive data in the UI that needs protection from capture. / Don't bypass or create workarounds for [`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) settings in other apps.
- Respect other apps' `FLAG_SECURE` declarations for security and privacy. / Don't transmit, save, or cache `FLAG_SECURE` protected content outside the device, even if an [Accessibility Tool](https://support.google.com/googleplay/android-developer/answer/10964491).

* * *

## Apps that Run On-device Android Containers

**Policy Summary**

To prevent security and privacy concerns, developers can use a `REQUIRE_SECURE_ENV` flag in their app manifest when on-device Android container apps lack the full security features of Android OS. The flag indicates the app should not run in a simulated environment. Apps providing these containers are required to respect this flag by not loading apps that declare it and are prohibited from bypassing this security measure. Please review the full policy to ensure compliance.

**Full Policy**

On-device Android container apps provide environments that simulate whole or portions of an underlying Android OS. The experience within these environments may not reflect the full suite of [Android security features](https://source.android.com/docs/security/features), which is why developers can choose to add a secure environment manifest flag to communicate to on-device Android containers that they must not operate in their simulated Android environment.

**Secure Environment Manifest Flag**

[REQUIRE_SECURE_ENV](https://d.android.com/training/basics/intents/limit-play-loading) is a flag that can be declared in an apps manifest to indicate that this app must not run in on-device Android container apps. For security and privacy purposes, apps that provide on-device Android containers must respect all apps that declare this flag and:

*   Review the manifests of apps they intend to load in their on-device Android container for this flag.
*   Not load the apps that declared this flag into their on-device Android container.
*   Not function as a proxy by intercepting or calling APIs on the device so that they appear to be installed in the container.
*   Not facilitate, or create workarounds to bypass the flag (such as, loading an older version of an app to bypass the current apps REQUIRE_SECURE_ENV flag).

Learn more about this policy in our [Help Center](https://support.google.com/googleplay/android-developer/answer/13609005).

**Key Considerations**

**Do** / **Don't**

- Apps that provide on-device containers must check for the `REQUIRE_SECURE_ENV` flag in other apps' manifests and not load them. / Don't ignore the flag. You cannot load an app into your container if it declares the `REQUIRE_SECURE_ENV` flag.
- Avoid workarounds. You are prohibited from bypassing the flag, such as by loading older versions of an app. / Don't bypass security measures. Do not create workarounds to override an app's security preference.
- Avoid proxying APIs. Do not function as a proxy by intercepting or calling APIs outside the container. / Don't make it appear that apps are running in a secure environment when they are not.
- Review the policy requirements for [on-device Android containers](https://support.google.com/googleplay/android-developer/answer/13609005).

---

## Heading structure of this article (for reporting)

1. (core) Device and Network Abuse - Policy Summary / Full Policy / "Examples of common Device and Network Abuse violations:" (H4) / Key Considerations
2. Permissions for Foreground Services (FGS) - Policy Summary / Full Policy / Key Considerations
3. User-Initiated Data Transfer Jobs - Policy Summary / Full Policy / Key Considerations
4. Flag Secure Requirements - Policy Summary / Full Policy / Key Considerations
5. Apps that Run On-device Android Containers - Policy Summary / Full Policy / "Secure Environment Manifest Flag" / Key Considerations


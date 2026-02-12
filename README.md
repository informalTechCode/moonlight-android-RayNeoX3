# Moonlight Android (RayNeo X3 Pro Port)

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

This repository is a RayNeo X3 Pro-focused port/fork with headset-specific UI and rendering adjustments to make Moonlight usable on AR glasses.

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## RayNeo X3 Pro Port Notes
This fork includes platform-specific changes for RayNeo X3 Pro compatibility:
- Stereo UI mirroring for dual-eye rendering in app menus and in-stream overlays.
- Fixed headset-friendly UI layout constraints (including constrained eye render dimensions) to avoid broken/asymmetric presentation.
- Custom in-app dialogs, confirmation prompts, loading spinners, and toasts for consistent readability/usability in-headset.
- Updated interaction flows and overlays to avoid system UI paths that are hard to use in AR.

These changes are focused on making day-to-day navigation and streaming workflows practical on RayNeo X3 Pro.

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## License And Compliance
- Project license: `LICENSE.txt` (GNU GPLv3)
- Binary/source compliance notes: `GPL_COMPLIANCE.md`
- Third-party attribution and notices: `THIRD_PARTY_NOTICES.md`

## Support This Port
If this RayNeo X3 Pro port has been useful to you, you can support continued maintenance:

<a href="https://www.buymeacoffee.com/informaltech" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).

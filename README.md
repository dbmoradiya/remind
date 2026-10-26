# Remind Fresh

Android reminders, renewal tracking, daily habits, water reminders and memories, with a home-screen widget and White, Dark and Black themes.

- Application ID: `com.remind.fresh`
- Version: `1.0.0` (version code 1)
- Minimum Android: 8.0 (API 26)
- Compile/target SDK: 34

This is a separate app, not an in-place update of `com.remind.app`. Installing it does not migrate or change another app's data. Two installed reminder apps can notify independently.

## Build

Use JDK 17 and an Android SDK containing platform 34 and build-tools 34.0.0. Set `ANDROID_HOME` or create an untracked `local.properties` with your SDK location.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The release task produces an unsigned APK. Keep release keystores and passwords outside version control. Updating an installed build requires the same signing key and application ID.

## Tests

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:recordPaparazziDebug
```

Tests cover reminder engines, database migrations, backup/import behavior, UI flows, widget receiver instantiation, widget actions and native RemoteViews inflation/drawing. Screen tests use synthetic sample data. The packaging check validates that manifest components exist in the actual APK:

```sh
python3 -m pip install androguard loguru
python3 verification/check_packaged_components.py path/to/app.apk
```

Local rendering tests do not verify physical launcher behavior, notification delivery, alarms or Google account roundtrips. Check those on a device before relying on a build.

## Google backup

Google sign-in, Drive backup and photo sync need an appropriately configured Google Cloud OAuth project. Register the application ID and signing certificate for your build, enable the required APIs and configure the consent screen/test users. Credentials and personal account configuration are not included here.

## Widget

`RemindWidgetReceiver` is declared in its own source file and registered in the manifest. The widget shows habits, water progress and upcoming reminders. Memories remain in the app rather than the widget.

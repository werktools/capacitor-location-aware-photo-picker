# capacitor-location-aware-photo-picker

An Android-only Capacitor plugin that picks photos from the gallery and preserves their
**original GPS EXIF data** - something neither the Android system Photo Picker nor
`@capacitor/camera`'s `chooseFromGallery` can do, by design.

This plugin uses `ACTION_GET_CONTENT` (via AndroidX's `GetMultipleContents` contract), following
[warting/exif_picker_lab](https://github.com/warting/exif_picker_lab)'s empirical findings on which
Android pickers actually preserve GPS EXIF - see "Why this exists" below.

## Why this exists

Since Android 10 (API 29), the system strips GPS/location EXIF from any photo an app reads through
`MediaStore` the normal way, as a deliberate privacy protection. The documented escape hatch is the
`ACCESS_MEDIA_LOCATION` permission plus
[`MediaStore.setRequireOriginal`](https://developer.android.com/reference/android/provider/MediaStore#setRequireOriginal(android.net.Uri)).

That escape hatch does **not** work at all against the Android system Photo Picker
(`content://media/picker/...`) - a separate, more restrictive content provider that unconditionally
rejects `setRequireOriginal`, regardless of permission. This is confirmed, intentional behavior on
Google's side, not a bug:

- [issuetracker.google.com/issues/243294058](https://issuetracker.google.com/issues/243294058) - developers raising this directly with Google
- [flutter/flutter#117053](https://github.com/flutter/flutter/issues/117053)
- [expo/expo#24652](https://github.com/expo/expo/issues/24652)

`@capacitor/camera`'s `chooseFromGallery` uses the Photo Picker by default on Android 11+, so it
inherits this limitation, and the upstream project has not implemented a fix - see
[ionic-team/capacitor-plugins#1074](https://github.com/ionic-team/capacitor-plugins/issues/1074),
[#2118](https://github.com/ionic-team/capacitor-plugins/issues/2118), and
[#2147](https://github.com/ionic-team/capacitor-plugins/issues/2147), all closed without a fix.

This plugin takes a different, deliberate trade-off: it uses `ACTION_GET_CONTENT` (through
AndroidX's `GetMultipleContents` activity result contract) instead of the Photo Picker,
specifically so GPS EXIF can be recovered. This picker choice is backed by empirical testing in
[warting/exif_picker_lab](https://github.com/warting/exif_picker_lab) - a reference app built
specifically to measure which picker/EXIF-read-method combinations preserve GPS on real devices,
since (per its own README) "the documentation on this is scattered and the failure modes are
silent." Their findings, summarized:

| Picker | Preserves GPS EXIF? | Needs `setRequireOriginal`? |
| --- | --- | --- |
| `PickVisualMedia` (system Photo Picker) | **No** - stripped unconditionally, no recovery path | N/A - throws `UnsupportedOperationException` |
| `OpenDocument` (Storage Access Framework) | Yes | Preserved without it; `setRequireOriginal` throws `SecurityException` if attempted, but isn't needed |
| `GetContent` / `GetMultipleContents` (this plugin) | Yes | Preserved without it; `setRequireOriginal` also succeeds if attempted (used here as defense in depth) |
| `TakePicture` (camera capture) | Depends on the camera app's own "save location" setting, independent of this plugin | N/A |

### An important caveat about that table

exif_picker_lab's own README is explicit that these are **empirical, per-device measurements**,
not a documented Android platform contract - unlike `ACCESS_MEDIA_LOCATION` + `setRequireOriginal`
against a classic MediaStore URI, which *is* documented, guaranteed behavior. GPS preservation via
`ACTION_GET_CONTENT` could plausibly differ across OEMs or Android versions from what their test
device showed. This plugin doesn't just assume the table holds: `recoverGPSIfPossible` still
attempts `setRequireOriginal`-based recovery as a fallback regardless of whether the initial read
already had GPS, so behavior stays robust even where the empirical finding doesn't hold. See "How
it works" below.

### The trade-off, stated plainly

Moving away from the Photo Picker means moving away from Google's own recommended, most polished,
most consistent picking UI, in favor of the older `ACTION_GET_CONTENT` picker chooser. Its exact
appearance varies more across devices and OEMs than the modern Photo Picker's. **Use this plugin
only where recovering original GPS data genuinely matters for your app** (e.g. geotagging, mapping,
provenance tools) - for general-purpose photo picking, `@capacitor/camera` and the system Photo
Picker remain the better default.

## Platforms

| Platform | Support |
| --- | --- |
| Android | Yes - this is the whole point |
| iOS | Not implemented. iOS's `PHPicker` already preserves EXIF (including GPS) when the right permission is granted, so use `@capacitor/camera`'s `chooseFromGallery` there instead. |
| Web | Not implemented. Use a standard `<input type="file">`. |

Calling `chooseFromGallery` on iOS or web rejects with an `unimplemented` error.

## Install

```bash
npm install capacitor-location-aware-photo-picker
npx cap sync android
```

## Permissions

This plugin needs only one permission, declared in its own `AndroidManifest.xml` (merged into your
app automatically - no manual setup needed):

```xml
<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION"/>
```

`ACTION_GET_CONTENT` (like the Photo Picker and SAF) grants read access to the picked item directly
through the system picker - **no storage or photos permission is required to pick photos at all.**

`ACCESS_MEDIA_LOCATION` is used only for the GPS recovery step, requested automatically (and
non-blockingly - it never delays or fails picking) the first time you call `chooseFromGallery` with
`includeMetadata: true`.

### When GPS recovery actually works

| Condition | GPS recoverable? |
| --- | --- |
| API < 29 (Android 9 and below) | N/A - Android never redacted GPS before API 29, so nothing to recover |
| API 29+, per exif_picker_lab's findings on their test device | **Yes**, and without even needing `ACCESS_MEDIA_LOCATION` - the initial plain file copy already preserves it |
| API 29+, if some device/OS version doesn't match that finding, `ACCESS_MEDIA_LOCATION` granted | **Yes**, via the `setRequireOriginal` fallback (see "How it works") |
| API 29+, permission denied and the initial copy didn't have GPS either | No - fails open, photo still returned without GPS |
| Photo genuinely has no GPS to begin with | No - nothing to recover, not an error |
| Original GPS is exactly `(0, 0)` ("Null Island") | No - treated as a placeholder, not real data (see below), not an error |

In every "No" case, `chooseFromGallery` still succeeds and returns the photo normally - it simply
won't have GPS EXIF. This plugin never fails a selection over missing location data.

### Why `(0, 0)` is filtered out

Some cameras and apps write exactly `(0, 0)` - a point in the Atlantic Ocean known as "Null Island"
- as a placeholder for "no GPS fix was available" rather than omitting the GPS tags entirely.
Returning that as if it were a real location would be misleading, so `recoverGPSIfPossible` checks
for it explicitly and skips recovery in that case, exactly as if the original had no GPS data at
all. This is an exact-equality check, not a small-radius one: a genuine fix landing on exactly
`0.0, 0.0` is vanishingly unlikely, so there's no real risk of discarding real (if coincidentally
nearby) coordinates.

## Usage

```typescript
import { LocationAwarePhotoPicker } from 'capacitor-location-aware-photo-picker';

const { results } = await LocationAwarePhotoPicker.chooseFromGallery({
  limit: 5,
  includeMetadata: true,
});

for (const photo of results) {
  console.log(photo.uri, photo.webPath);
  console.log(photo.metadata?.exif?.GPSLatitude, photo.metadata?.exif?.GPSLongitude);
}
```

## API

<docgen-index>

* [`chooseFromGallery(...)`](#choosefromgallery)
* [Interfaces](#interfaces)

</docgen-index>

### `chooseFromGallery(...)`

```typescript
chooseFromGallery(options?: ChooseFromGalleryOptions) => Promise<MediaResults>
```

Opens the Android photo picker (`ACTION_GET_CONTENT`, via `GetMultipleContents`) to choose one or
more photos from the device's gallery, preserving their original GPS EXIF data where possible.

Android only. Rejects with `unimplemented` on iOS and web.

| Param | Type |
| --- | --- |
| **`options`** | <code><a href="#choosefromgalleryoptions">ChooseFromGalleryOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#mediaresults">MediaResults</a>&gt;</code>

### Interfaces

#### MediaResults

| Prop | Type |
| --- | --- |
| **`results`** | <code>MediaResult[]</code> |

#### MediaResult

| Prop | Type | Description |
| --- | --- | --- |
| **`type`** | <code>'picture'</code> | Always `'picture'`. Kept as a field for shape-compatibility with `@capacitor/camera`'s `MediaResult`. |
| **`uri`** | <code>string</code> | A `file://` URI pointing to the returned photo, copied into this app's own private storage. |
| **`webPath`** | <code>string</code> | A `capacitor://`-scheme path suitable for use directly as an `<img src>` in a WebView. |
| **`metadata`** | <code><a href="#mediametadata">MediaMetadata</a></code> | Present only when `includeMetadata` was set to `true`. |

#### MediaMetadata

| Prop | Type | Description |
| --- | --- | --- |
| **`resolution`** | <code>string</code> | `<width>x<height>` format, e.g. `'1920x1080'`. |
| **`size`** | <code>string</code> | File size in bytes, as a string. |
| **`format`** | <code>string</code> | Always `'jpeg'` - see "How it works" below. |
| **`exif`** | <code>{ [key: string]: string \| null }</code> | Keyed by EXIF tag name (e.g. `"GPSLatitude"`), values are ExifInterface's raw string form (un-parsed DMS rationals for GPS, etc.). GPS keys are populated only when recovery succeeded. |

#### ChooseFromGalleryOptions

| Prop | Type | Description | Default |
| --- | --- | --- | --- |
| **`allowMultipleSelection`** | <code>boolean</code> | Not applicable - `GetMultipleContents` always presents a multi-select-capable picker (the user can still choose just one). Kept in the type for forward compatibility. Use `limit` to constrain results either way. | <code>false</code> |
| **`limit`** | <code>number</code> | Maximum number of photos to return. `0` means no limit. | <code>0</code> |
| **`quality`** | <code>number</code> | JPEG quality, 0-100. | <code>100</code> |
| **`targetWidth`** | <code>number</code> | Max width in pixels, aspect ratio preserved. `0` means no constraint. | <code>0</code> |
| **`targetHeight`** | <code>number</code> | Max height in pixels, aspect ratio preserved. `0` means no constraint. | <code>0</code> |
| **`correctOrientation`** | <code>boolean</code> | Rotate the image so it displays right-side-up, per its EXIF orientation tag. | <code>true</code> |
| **`includeMetadata`** | <code>boolean</code> | Include `resolution`/`size`/`format`/`exif` (with GPS recovery attempted) in the result. | <code>false</code> |

## How it works

1. `ActivityResultContracts.GetMultipleContents()`, which wraps `Intent.ACTION_GET_CONTENT` with
   `CATEGORY_OPENABLE` and `EXTRA_ALLOW_MULTIPLE = true` - the mechanism
   [exif_picker_lab](https://github.com/warting/exif_picker_lab) found preserves EXIF GPS. The
   contract is marked `@Deprecated` in AndroidX in favor of `PickMultipleVisualMedia` (the modern
   Photo Picker's multi-select contract) - that deprecation is precisely *why* this plugin exists:
   `PickMultipleVisualMedia` routes through the Photo Picker, which strips GPS with no recovery
   path at all. The deprecation warning is suppressed deliberately in the source, with a comment
   explaining not to "fix" it by switching to the suggested replacement.
2. Each picked URI is copied into this app's own cache directory as a plain file (a normal
   `openInputStream` read - per exif_picker_lab's findings, this already preserves GPS EXIF on
   their tested device, no special handling needed), so quality/resize processing and EXIF
   reading/writing can happen against an ordinary local file.
3. If `quality < 100`, or `targetWidth`/`targetHeight`/`correctOrientation` apply, the copy is
   decoded to a `Bitmap`, resized/rotated as needed, and re-compressed as JPEG - which strips all
   EXIF, so the original (non-GPS) EXIF is copied back onto the result immediately afterward.
4. If `includeMetadata` was set, GPS recovery is attempted (see below) and copied onto the result
   file's EXIF directly - so the returned *file* is correctly self-describing on disk, not just
   the JSON your JS code receives.

### GPS recovery, specifically

Unlike a Storage Access Framework-based approach - which would need to convert its document URI to
a classic MediaStore URI before `setRequireOriginal` can be used, and only on API 31+ - the URI
`ACTION_GET_CONTENT` returns is already treated as MediaStore-equivalent, per exif_picker_lab's
testing: `setRequireOriginal` can be called on it directly, from API 29, no conversion step needed.

`recoverGPSIfPossible` always attempts this, regardless of whether the initial plain copy in step 2
already preserved GPS - deliberately redundant on the configuration exif_picker_lab tested (GPS
should already be present by then), but cheap, and it's what keeps GPS recovery robust on any
device/OS version where the empirical finding in step 2 doesn't hold. It requires
`ACCESS_MEDIA_LOCATION`, and copies the *entire* GPS EXIF dictionary (altitude, speed, track,
timestamps, destination coordinates - not just latitude/longitude) via `ExifWrapper.copyGpsExif`
onto the result file.

## Acknowledgements

This plugin's entire design rests on work done elsewhere, and wouldn't exist in its current form
without it:

- **[warting/exif_picker_lab](https://github.com/warting/exif_picker_lab)** is the direct basis for
  this plugin's picker choice and GPS-recovery strategy. It's a reference Android app built
  specifically to empirically measure which picker/EXIF-read-method combinations preserve GPS on
  real devices, because (in its own words) "the documentation on this is scattered and the failure
  modes are silent." Its findings - summarized in the table under "Why this exists" above, and
  linked in full in the code comments on `recoverGPSIfPossible` - are the reason this plugin uses
  `ACTION_GET_CONTENT`/`GetMultipleContents` rather than the system Photo Picker, and the reason
  `setRequireOriginal` is attempted the way it is. Its README's "When to use each picker in
  production" guidance (`ACTION_GET_CONTENT` when original EXIF matters, Photo Picker otherwise) is
  exactly the trade-off this plugin exists to take on an app's behalf. If you're debugging GPS EXIF
  behavior on a specific device, run their guided test app directly rather than trusting this
  README's claims alone - that's precisely what it's for.

- **[observ-ing/capacitor-original-photo-picker](https://github.com/observ-ing/capacitor-original-photo-picker)**
  independently identified and validated the same underlying `setRequireOriginal` mechanism (via
  classic `ACTION_PICK` rather than `ACTION_GET_CONTENT`) and was this plugin's original reference
  before `exif_picker_lab`'s more thorough, multi-picker comparison led to the current
  `ACTION_GET_CONTENT`-based approach.

## Testing

### Unit tests

`android/src/test/` has two test files:

- **`GpsUtilsTest.kt`** - plain JUnit, no Android dependency at all. Covers the "Null Island"
  `(0, 0)` placeholder-filtering logic in isolation.
- **`ExifWrapperTest.kt`** - uses [Robolectric](https://robolectric.org/) (an Android framework
  simulator that runs on a plain JVM, no emulator or device needed) to exercise
  `ExifWrapper.copyGpsExif`/`toJson` against real `ExifInterface` file I/O with a small embedded
  JPEG fixture. Covers: the whole GPS dictionary is copied (not just lat/long), non-GPS tags on the
  destination are left untouched, and a source with no GPS leaves the destination unchanged.

**Run them locally:**

```bash
# One-time: generate a Gradle wrapper pinned to a version compatible with this project's AGP
# version (needs a system-installed Gradle; see https://gradle.org/install/ or your platform's
# package manager - e.g. `brew install gradle`, `sdk install gradle`, or download directly from
# gradle.org).
cd android
gradle wrapper --gradle-version 8.13.2

# Verify the wrapper actually picked up 8.13.2 (not whatever your system Gradle happened to be):
./gradlew -v

# From then on, always use the wrapper - not a bare `gradle` command (see the troubleshooting
# note below for why this distinction matters):
./gradlew test
```

This runs entirely on the JVM (Robolectric simulates the Android framework) - no Android SDK,
emulator, or connected device required, and should take a few seconds once dependencies are
cached.

**Don't skip the wrapper and just run a bare `gradle test`** unless you've confirmed your
system-installed Gradle is 8.13 or newer (`gradle -v`) - a bare `gradle` command uses whatever
version happens to be on your `PATH`, which silently causes exactly the failure described in
"Troubleshooting" below if it's older than this project's AGP version requires. `npm run
test:android` (from the repo root) is a thin wrapper around `cd android && gradle test`, so the
same caveat applies to it.

### Troubleshooting: "Could not generate a decorated class for type LibraryPlugin ... `BuildFeatures`"

```
FAILURE: Build failed with an exception.
> Failed to apply plugin 'com.android.internal.library'.
   > Could not create plugin of type 'LibraryPlugin'.
      > Could not generate a decorated class for type LibraryPlugin.
         > org/gradle/api/configuration/BuildFeatures
```

This means the Gradle version actually running the build is **older** than what this project's
pinned Android Gradle Plugin version (`8.13.0`, in `android/build.gradle`'s `buildscript` block)
requires - AGP 8.13.x requires **Gradle 8.13 or newer** as a hard minimum. `BuildFeatures` is a
Gradle API class that doesn't exist in older Gradle releases, so AGP fails to even initialize.

Fix: confirm which Gradle is actually being used (`./gradlew -v` if you generated a wrapper,
`gradle -v` if you're calling Gradle directly), and make sure it's 8.13 or newer. If you generated
a wrapper with the exact command above, `./gradlew -v` should report `8.13.2` - if it reports
something else, delete `android/gradle/wrapper/` and `android/gradlew*` and regenerate. If you're
using a bare `gradle` command instead, either upgrade your system Gradle install or switch to the
wrapper-based approach above, which pins the version regardless of what else is on your `PATH`.

Test reports land at `android/build/reports/tests/testDebugUnitTest/index.html` (open in a
browser) and raw results at `android/build/test-results/`.

### Continuous integration

`.github/workflows/test.yml` runs the same unit tests on every push/PR via GitHub Actions, using
[`gradle/actions/setup-gradle`](https://github.com/gradle/actions/blob/main/setup-gradle/README.md)
to provision Gradle directly (no wrapper needed - see the comment in `android/settings.gradle` for
why this repo doesn't commit one). Adapt the `gradle-version` input there to match whatever
version you generate a local wrapper with, so local and CI results stay consistent. Test reports
are uploaded as a workflow artifact on every run, pass or fail.

If you're using a different CI provider, the equivalent steps are: install a JDK (21, matching
`sourceCompatibility` in `android/build.gradle`), install Gradle (or use that provider's Gradle
setup action/cache if one exists), then run `gradle test` from the `android/` directory.

### Manual, on-device verification

Unit tests cover the pure logic (null-island filtering) and the EXIF-copying mechanics
(`ExifWrapper`), but **cannot** verify the actual claim this whole plugin rests on: that
`ACTION_GET_CONTENT` preserves GPS EXIF on a given real device. Robolectric simulates the Android
framework; it doesn't replicate real `MediaStore`/`ContentResolver`/picker-app behavior closely
enough to trust for that specific question. To verify on your actual target devices:

1. **Preferred: run [warting/exif_picker_lab](https://github.com/warting/exif_picker_lab)'s own
   guided test app** directly on the device(s) you care about. It's built exactly for this - "does
   this picker preserve EXIF on this device" - and its guided mode walks through all 5 pickers with
   a clear pass/fail summary, not just this plugin's one chosen mechanism.
2. **Or, exercise this plugin directly**: build a small test harness app (or add a temporary button
   to your own app) that calls `chooseFromGallery({ includeMetadata: true })`, pick a photo you
   know has GPS EXIF (check first with a desktop tool, or take one with your phone's camera with
   location tagging on), and confirm `metadata.exif.GPSLatitude`/`GPSLongitude` come back populated
   in the JS result.
3. Also worth checking manually: the "0 results" path (cancel the picker), and behavior with
   `includeMetadata: false` (no `ACCESS_MEDIA_LOCATION` prompt should appear at all).

## Known limitations

- GPS recovery via the `setRequireOriginal` fallback needs API 29+; the initial-copy recovery path
  (per exif_picker_lab's findings) has no such floor, so in practice recovery should work from
  API 29 either way. Picking itself (without GPS recovery) works on any API level this plugin
  supports.
- Behavior is based on empirical findings from one test device/OS combination
  (exif_picker_lab's), not a documented Android guarantee for this specific picker - see the
  caveat under "Why this exists" above. The `setRequireOriginal` fallback exists specifically to
  keep this robust where that doesn't hold, but hasn't been verified across a wide device matrix.
- No in-app photo editing/cropping (unlike `@capacitor/camera`'s `editable` option on
  `chooseFromGallery`). Out of scope for a plugin focused specifically on original-data recovery -
  edit the returned file yourself if needed.
- Photos only - no video support. GPS/EXIF recovery is the entire reason this plugin exists, and
  doesn't apply to video the same way.
- `allowMultipleSelection` is a no-op here (see the options table above) - kept in the type only
  for forward compatibility.
- The unit tests, CI workflow, and Gradle setup described above have not been executed end-to-end
  in a real environment while writing them (no Android SDK, Gradle, or network access to resolve
  dependencies were available while building this plugin) - see "Testing" above for how to verify
  them for real, and please treat the first real run as exactly that: a first run, not a
  formality.

## License

MIT

export interface LocationAwarePhotoPickerPlugin {
  /**
   * Opens the Android system file picker (Storage Access Framework) to choose one or more photos
   * from the device's gallery, preserving their original GPS EXIF data where possible.
   *
   * Unlike `@capacitor/camera`'s `chooseFromGallery` - which, on Android, goes through the system
   * Photo Picker or the classic gallery UI, both of which strip GPS/location EXIF data from the
   * returned photo - this method deliberately uses the Storage Access Framework instead, so that
   * GPS EXIF can be recovered from the original file after picking. See this plugin's README for a
   * full explanation of why this trade-off exists and what it costs you in picker UX.
   *
   * Android only. Rejects with `UNIMPLEMENTED` on iOS and web.
   *
   * @since 0.1.0
   */
  chooseFromGallery(options?: ChooseFromGalleryOptions): Promise<MediaResults>;
}

export interface ChooseFromGalleryOptions {
  /**
   * Whether the user can select more than one photo.
   *
   * Not applicable to this plugin's implementation (backed by `GetMultipleContents`, which always
   * presents a multi-select-capable picker UI - the user can still choose just one). Kept in the
   * type for forward compatibility. Use `limit` to constrain the number of results either way.
   * @default false
   */
  allowMultipleSelection?: boolean;

  /**
   * The maximum number of photos to return. Extra selections beyond this limit are discarded.
   * `0` means no limit.
   * @default 0
   */
  limit?: number;

  /**
   * The quality of the returned image, from 0-100. Only applied when set below 100, or when
   * `targetWidth`/`targetHeight` require re-encoding the image anyway.
   * @default 100
   */
  quality?: number;

  /**
   * Maximum width, in pixels, of the returned image. Resizing always preserves aspect ratio.
   * `0` means no constraint.
   * @default 0
   */
  targetWidth?: number;

  /**
   * Maximum height, in pixels, of the returned image. Resizing always preserves aspect ratio.
   * `0` means no constraint.
   * @default 0
   */
  targetHeight?: number;

  /**
   * Whether to correct the image's orientation based on its EXIF orientation tag, so the returned
   * file always displays right-side-up regardless of how the camera that took it was held.
   * @default true
   */
  correctOrientation?: boolean;

  /**
   * Whether the result should include metadata (dimensions, format, size, and EXIF - including GPS,
   * recovered where possible; see this plugin's README for when recovery isn't possible).
   * If an error occurs while reading metadata, it's returned empty rather than failing the call.
   * @default false
   */
  includeMetadata?: boolean;
}

export interface MediaMetadata {
  /**
   * The resolution of the image, in `<width>x<height>` format. Example: '1920x1080'.
   */
  resolution?: string;

  /**
   * The size of the returned file, in bytes, as a string.
   */
  size?: string;

  /**
   * The format of the returned image. Always 'jpeg' - images are always re-encoded as JPEG so that
   * quality/resize options and EXIF/GPS recovery can be applied consistently regardless of the
   * original file's format.
   */
  format?: string;

  /**
   * When the photo was created, in ISO 8601 format. Resolved in priority order:
   *
   * 1. The photo's own EXIF `DateTimeOriginal`/`DateTime` tag, combined with its *paired*
   *    `OffsetTimeOriginal`/`OffsetTime` tag (EXIF 2.31+) when the camera recorded one - a growing
   *    but still not universal share of photos. When available, the string includes that offset,
   *    e.g. `'2026-01-15T22:13:20+01:00'`.
   * 2. The same `DateTimeOriginal`/`DateTime` tag alone, when present but with no matching offset
   *    tag (true for the vast majority of camera-taken photos). This is the camera's local
   *    wall-clock time with no timezone attached, so the string has **no trailing `Z` or offset** -
   *    e.g. `'2026-01-15T22:13:20'`. Treat it as "local time, zone unknown", not UTC - don't assume
   *    the *absence* of an offset means UTC.
   * 3. MediaStore's own `DATE_TAKEN`/`DATE_MODIFIED` columns, for photos with no usable EXIF date at
   *    all (screenshots, downloaded images, EXIF stripped by another app). These *are* genuine UTC
   *    instants, so the string **does** have a trailing `Z` - e.g. `'2026-01-15T22:13:20Z'`.
   *
   * In short: check for a trailing `Z` or `±HH:MM` suffix before assuming this string is
   * timezone-qualified - it might not be, and that's a property of the *source photo's own
   * metadata*, not something this plugin can improve on.
   *
   * `undefined` if none of the above has anything usable - never a fabricated value, and never the
   * time the photo happened to be picked/copied by this plugin.
   */
  creationDate?: string;

  /**
   * EXIF data read from the returned file, as an object keyed by EXIF tag name (matching
   * androidx.exifinterface.media.ExifInterface's `TAG_*` constant values, e.g. `"GPSLatitude"`).
   * Values are the raw string form ExifInterface itself returns - un-parsed DMS rationals for GPS
   * coordinates, etc. Only present when `includeMetadata` was set to `true`.
   *
   * GPS-related keys (`GPSLatitude`, `GPSLongitude`, `GPSAltitude`, etc.) are populated only when
   * recovery succeeded - see this plugin's README for the cases where that isn't possible.
   */
  exif?: Record<string, string | null>;
}

export interface MediaResult {
  /**
   * Always `'picture'`. Kept as a field, rather than assumed, for future extensibility and for
   * shape-compatibility with `@capacitor/camera`'s `MediaResult`.
   */
  type: 'picture';

  /**
   * A `file://` URI pointing to the returned photo, copied into this app's own private storage.
   */
  uri: string;

  /**
   * A `capacitor://`-scheme path suitable for use directly as an `<img src>` in a WebView, derived
   * from `uri`.
   */
  webPath: string;

  /**
   * Present only when `includeMetadata` was set to `true`.
   */
  metadata?: MediaMetadata;
}

export interface MediaResults {
  results: MediaResult[];
}

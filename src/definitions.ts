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

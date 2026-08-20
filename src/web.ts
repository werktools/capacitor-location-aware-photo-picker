import { WebPlugin } from '@capacitor/core';

import type { ChooseFromGalleryOptions, MediaResults, LocationAwarePhotoPickerPlugin } from './definitions';

export class LocationAwarePhotoPickerWeb extends WebPlugin implements LocationAwarePhotoPickerPlugin {
  async chooseFromGallery(_options?: ChooseFromGalleryOptions): Promise<MediaResults> {
    throw this.unimplemented(
      'LocationAwarePhotoPicker.chooseFromGallery is only implemented on Android. On iOS, use @capacitor/camera\'s ' +
        'chooseFromGallery instead - PHPicker already preserves EXIF GPS when the right permission is granted, ' +
        'so this workaround isn\'t needed there. On web, use a standard <input type="file"> element.'
    );
  }
}

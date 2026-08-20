import { registerPlugin } from '@capacitor/core';

import type { LocationAwarePhotoPickerPlugin } from './definitions';

const LocationAwarePhotoPicker = registerPlugin<LocationAwarePhotoPickerPlugin>('LocationAwarePhotoPicker', {
  web: () => import('./web').then((m) => new m.LocationAwarePhotoPickerWeb())
});

export * from './definitions';
export { LocationAwarePhotoPicker };

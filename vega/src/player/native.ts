/*
 * Amazon's optional native modules, for Play sound on (Connectivity Controller) and Match
 * frame rate (Graphics). Not built in: Amazon's display library is a required system part
 * once it's in the app, and a stick without it won't open the app at all, so both stay out
 * until they can be tried on a stick. Settings says where the Fire TV's own settings are.
 */
export function optionalModule<T>(_name: 'connectivity' | 'graphics'): T | null {
  return null;
}

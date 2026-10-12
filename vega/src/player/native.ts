/*
 * Amazon's optional native modules, loaded when first wanted rather than at start: one the
 * stick hasn't got, or won't load, is null, and what uses it says so instead of failing.
 */
export function optionalModule<T>(name: 'connectivity' | 'graphics'): T | null {
  try {
    // Named in full for the bundler, which only bundles what it can see.
    if (name === 'connectivity') return require('@amazon-devices/kepler-connectivity-controller') as T;
    return require('@amazon-devices/kepler-graphics') as T;
  } catch {
    return null;
  }
}

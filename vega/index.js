// Timers cleared as a browser clears them (see src/core/timers.ts).
import './src/core/timers';
// Full URL and URLSearchParams, before anything uses them (see src/core/url.ts).
import './src/core/url';
import { AppRegistry } from 'react-native';
import { App } from './src/App';
import { name } from './app.json';

AppRegistry.registerComponent(name, () => App);

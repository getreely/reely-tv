import { AppRegistry } from 'react-native';
import { App } from '../src/App';

// Vega lays the screen out at 1920 by 1080; the preview page is that size.
AppRegistry.registerComponent('reely', () => App);
AppRegistry.runApplication('reely', { rootTag: document.getElementById('root') });

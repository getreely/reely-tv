// Amazon's optional modules, for the preview: none, as on a stick that won't load them,
// unless a test sets `reely:fake-devices` before the page opens. Then a stick with two
// pairs of headphones paired (one connected), a speaker nearby to pair, and a display
// that refuses to switch, as a stick without the privilege does.

type Listener = Record<string, (...args: any[]) => void>;

function connectivity() {
  const AUDIO = 'accessory.category.AUDIO_VIDEO';
  const paired = [
    { identity: 'bt-1', name: 'Sony WH-1000XM5', protocol: 'accessory.protocol.BLUETOOTH', category: AUDIO },
    { identity: 'bt-2', name: 'AirPods Pro', protocol: 'accessory.protocol.BLUETOOTH', category: AUDIO },
  ];
  const connected = new Set(['bt-1']);
  const nearby = { identity: 'bt-3', name: 'JBL Flip 6', protocol: 'accessory.protocol.BLUETOOTH', category: AUDIO };
  class ConnectivityController {
    constructor(private cb: Listener) {}
    async initialize() { return 0; }
    async getAccessoryList(type: string) {
      return type.endsWith('CONNECTED') ? paired.filter((p) => connected.has(p.identity)) : paired.slice();
    }
    async connectAccessory(id: string) {
      setTimeout(() => {
        connected.clear();
        connected.add(id);
        const b = paired.find((p) => p.identity === id)!;
        this.cb.onAccessoryConnectionStateChange(b, 1);
      }, 400);
      return 0;
    }
    async disconnectAccessory(id: string) { connected.delete(id); return 0; }
    async startAccessoryDiscovery() {
      this.cb.onAccessoryDiscoveryStateChange(1);
      setTimeout(() => this.cb.onAccessoryDiscovery(nearby), 500);
      setTimeout(() => this.cb.onAccessoryDiscoveryStateChange(2), 1500);
      return 0;
    }
    async cancelAccessoryDiscovery() { return 0; }
    async pairAccessory(id: string) {
      setTimeout(() => {
        if (!paired.some((p) => p.identity === id)) paired.push(nearby);
        this.cb.onAccessoryPairingStateChange(nearby, 1);
      }, 400);
      return 0;
    }
    async sendAccessoryPairingResponse() { return 0; }
  }
  return {
    ConnectivityController,
    AccessoryListType: { PAIRED: 'accessory.listtype.PAIRED', CONNECTED: 'accessory.listtype.CONNECTED' },
    AccessoryProtocol: { BLUETOOTH: 'accessory.protocol.BLUETOOTH' },
    AccessoryCategory: { AUDIO_VIDEO: AUDIO },
  };
}

function graphics() {
  return {
    DisplayController: { isPrimaryDisplayConnected: () => true },
    Display: {
      getCurrentConfig: () => ({ widthInPixels: 3840, heightInPixels: 2160, supportedRefreshRatesInMillihertz: [23976, 24000, 25000, 50000, 59940, 60000] }),
      getCurrentRefreshRateInMillihertz: () => 60000,
    },
    DisplayManager: {
      addListener: (l: Listener) => { (graphics as any).listener = l; return { unsubscribe() {} }; },
      requestConfigChange: () => undefined,
      commitStates: () => { setTimeout(() => (graphics as any).listener?.onCommitFailure(1), 100); return 1; },
    },
  };
}

export function optionalModule<T>(name: 'connectivity' | 'graphics'): T | null {
  let on = false;
  try { on = localStorage.getItem('reely:fake-devices') === '1'; } catch { /* none */ }
  if (!on) return null;
  return (name === 'connectivity' ? connectivity() : graphics()) as T;
}

/*
 * Headphones and speakers, as the Fire TV's Play sound on has them (AudioOutputs.kt,
 * BluetoothAudio.kt), through Amazon's Connectivity Controller: the Bluetooth sound
 * devices paired with the stick, connecting the one chosen, and finding and pairing new
 * ones. Vega sends all sound to what's connected, so connecting is choosing.
 *
 * Fire TV may not let an app other than its own Settings do this: every call needs a
 * privilege the stick grants or doesn't. When it doesn't, or the module isn't there,
 * this says so once (`available: false`) and Settings says where to do it instead.
 */

import { optionalModule } from './native';

type AccModule = typeof import('@amazon-devices/kepler-connectivity-controller');
type Controller = InstanceType<AccModule['ConnectivityController']>;
type Basic = { identity: string; name: string; category: string };

export interface Output {
  id: string;
  name: string;
  connected: boolean;
}

export interface OutputsState {
  /** null until it's been tried; false when the stick won't let Reely do it. */
  available: boolean | null;
  paired: Output[];
  /** Sound devices nearby that aren't paired, while looking and after. */
  found: Output[];
  searching: boolean;
  /** The one being paired or connected now, and the last one that wouldn't. */
  busy: string | null;
  failed: string | null;
  /** A code to type on the headphones, when pairing asks for one to be shown. */
  code: string | null;
}

const AUDIO = 'accessory.category.AUDIO_VIDEO';
const SEARCH_SECONDS = 30;
/** How long connecting is given before it's taken as not answering: out of range, or off. */
const CONNECT_WAIT_MS = 12_000;

let mod: AccModule | null | undefined;
function load(): AccModule | null {
  if (mod === undefined) {
    mod = optionalModule<AccModule>('connectivity');
  }
  return mod;
}

class Outputs {
  state: OutputsState = { available: null, paired: [], found: [], searching: false, busy: null, failed: null, code: null };
  private listeners = new Set<(s: OutputsState) => void>();
  private client: Controller | null = null;
  private starting: Promise<boolean> | null = null;
  private waiting = new Map<string, (connected: boolean) => void>();

  subscribe(listener: (s: OutputsState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private set(change: Partial<OutputsState>) {
    this.state = { ...this.state, ...change };
    this.listeners.forEach((l) => l(this.state));
  }

  /** Connects to the stick's accessory service, once; false when it can't or won't. */
  start(): Promise<boolean> {
    if (!this.starting) this.starting = this.begin();
    return this.starting;
  }

  private async begin(): Promise<boolean> {
    const m = load();
    if (!m) {
      this.set({ available: false });
      return false;
    }
    try {
      const client = new m.ConnectivityController({
        onAccessoryDiscoveryStateChange: (state) => {
          if (Number(state) !== 1) this.set({ searching: false });
        },
        onAccessoryDiscovery: (b) => {
          const info = b as Basic;
          if (info.category !== AUDIO || this.state.paired.some((p) => p.id === info.identity)) return;
          if (this.state.found.some((f) => f.id === info.identity)) return;
          this.set({ found: [...this.state.found, { id: info.identity, name: info.name || 'Headphones', connected: false }] });
        },
        onAccessoryAutopairStateChange: () => undefined,
        onAccessoryAutopair: () => undefined,
        onAccessoryPairingRequest: (b, request) => void this.answer((b as Basic).identity, request.message),
        onAccessoryPairingStateChange: () => void this.refresh(),
        onAccessoryConnectionStateChange: (b, state) => {
          const id = (b as Basic).identity;
          const n = Number(state);
          if (n === 1 || n === 2 || n === 5) this.waiting.get(id)?.(n === 1);
          void this.refresh();
        },
        onAccessoryInfoUpdate: () => undefined,
        onAccessoryDfuStateChange: () => undefined,
      });
      await client.initialize();
      this.client = client;
      this.set({ available: true });
      await this.refresh();
      return true;
    } catch {
      this.set({ available: false });
      return false;
    }
  }

  /** The paired sound devices, and which is connected. */
  async refresh() {
    const c = this.client;
    const m = load();
    if (!c || !m) return;
    try {
      const paired = (await c.getAccessoryList(m.AccessoryListType.PAIRED)) as Basic[];
      const connected = new Set(((await c.getAccessoryList(m.AccessoryListType.CONNECTED)) as Basic[]).map((b) => b.identity));
      const outputs = paired.filter((b) => b.category === AUDIO).map((b) => ({ id: b.identity, name: b.name || 'Headphones', connected: connected.has(b.identity) }));
      this.set({ paired: outputs, found: this.state.found.filter((f) => !outputs.some((o) => o.id === f.id)) });
    } catch {
      // Kept as it was.
    }
  }

  /** Connected, and so where the sound goes. True once it is. */
  async connect(id: string): Promise<boolean> {
    if (!(await this.start()) || !this.client) return false;
    if (this.state.paired.some((p) => p.id === id && p.connected)) return true;
    this.set({ busy: id, failed: null });
    const connected = new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => { this.waiting.delete(id); resolve(false); }, CONNECT_WAIT_MS);
      this.waiting.set(id, (ok) => { clearTimeout(timer); this.waiting.delete(id); resolve(ok); });
    });
    try {
      await this.client.connectAccessory(id);
    } catch {
      this.waiting.get(id)?.(false);
    }
    const ok = await connected;
    await this.refresh();
    this.set({ busy: null, failed: ok ? null : id });
    return ok;
  }

  /** Back to the TV's own sound: whatever headphones are connected, let go. */
  async disconnectAll() {
    if (!(await this.start()) || !this.client) return;
    for (const p of this.state.paired.filter((o) => o.connected)) {
      try {
        await this.client.disconnectAccessory(p.id);
      } catch {
        // It's gone already.
      }
    }
    await this.refresh();
  }

  /** Looks for headphones in pairing mode nearby, for half a minute. */
  async search() {
    const m = load();
    if (!(await this.start()) || !this.client || !m) return;
    this.set({ searching: true, found: [], failed: null, code: null });
    try {
      await this.client.startAccessoryDiscovery(m.AccessoryProtocol.BLUETOOTH, m.AccessoryCategory.AUDIO_VIDEO, SEARCH_SECONDS);
    } catch {
      this.set({ searching: false });
    }
  }

  /** Paired, then connected: the new headphones are where the sound goes. */
  async pair(id: string): Promise<boolean> {
    const m = load();
    if (!(await this.start()) || !this.client || !m) return false;
    this.set({ busy: id, failed: null, code: null });
    try {
      await this.client.cancelAccessoryDiscovery(m.AccessoryProtocol.BLUETOOTH).catch(() => undefined);
      await this.client.pairAccessory(id);
    } catch {
      this.set({ busy: null, failed: id });
      return false;
    }
    // Pairing finishes by its own event; connecting waits for it, as a stick's Settings does.
    for (let i = 0; i < 20 && !this.state.paired.some((p) => p.id === id); i++) {
      await new Promise<void>((r) => setTimeout(() => r(), 500));
      await this.refresh();
    }
    if (!this.state.paired.some((p) => p.id === id)) {
      this.set({ busy: null, failed: id, code: null });
      return false;
    }
    return this.connect(id);
  }

  /**
   * What pairing asks. Headphones have no screen: a yes, or a code to compare, is
   * answered yes, as pairing was asked for here; one asking for a PIN gets the usual
   * 0000; one that shows a code on the TV to type in is put up for the person.
   */
  private async answer(id: string, request: { variant: string; keytype?: string; keysize?: number; keydata?: string }) {
    const c = this.client;
    if (!c) return;
    if (request.variant === 'DISPLAY') {
      this.set({ code: request.keydata ?? null });
      return;
    }
    const keydata = request.variant === 'INPUT' ? '0000' : 'YES';
    try {
      await c.sendAccessoryPairingResponse(id, { message: { variant: request.variant as never, keytype: request.keytype as never, keysize: keydata.length, keydata } });
    } catch {
      this.set({ busy: null, failed: id });
    }
  }
}

export const outputs = new Outputs();

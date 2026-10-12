import AsyncStorage from "@amazon-devices/react-native-async-storage__async-storage";
import { MemoryStorage } from "./storage";

/*
 * What's kept on the stick, for the app's Store, which reads and writes at once. Everything
 * kept is read into memory before the app starts; each change is written behind. A write
 * that fails loses only that change, and only if the app is closed before the next.
 */

const PREFIX = "reely:";

export class PersistedStorage extends MemoryStorage {
  /** The order writes were asked for in, so the last for a key is the one kept. */
  private queue: Promise<unknown> = Promise.resolve();

  setItem(key: string, value: string) {
    super.setItem(key, value);
    this.queue = this.queue.then(() => AsyncStorage.setItem(key, String(value))).catch(() => undefined);
  }

  removeItem(key: string) {
    super.removeItem(key);
    this.queue = this.queue.then(() => AsyncStorage.removeItem(key)).catch(() => undefined);
  }

  clear() {
    super.clear();
    this.queue = this.queue.then(() => AsyncStorage.clear()).catch(() => undefined);
  }

  /** Everything the app kept last time, read in. */
  static async load(): Promise<PersistedStorage> {
    const storage = new PersistedStorage();
    try {
      const keys = (await AsyncStorage.getAllKeys()).filter((k) => k.startsWith(PREFIX));
      const pairs = await AsyncStorage.multiGet(keys);
      // Straight into memory: these are already kept.
      for (const [key, value] of pairs) if (value != null) MemoryStorage.prototype.setItem.call(storage, key, value);
    } catch {
      // Nothing kept, or it can't be read: a first start.
    }
    return storage;
  }
}

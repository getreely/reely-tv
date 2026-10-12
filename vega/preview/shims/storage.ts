// Amazon's AsyncStorage, kept in the browser's localStorage for the preview.
const AsyncStorage = {
  getAllKeys: async () => Object.keys(localStorage),
  multiGet: async (keys: string[]) => keys.map((k) => [k, localStorage.getItem(k)] as [string, string | null]),
  setItem: async (k: string, v: string) => localStorage.setItem(k, v),
  removeItem: async (k: string) => localStorage.removeItem(k),
  clear: async () => localStorage.clear(),
};
export default AsyncStorage;

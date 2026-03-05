/**
 * Preload for main window (settings/connect UI)
 */
import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('mainAPI', {
  getConfig: () => ipcRenderer.invoke('main:getConfig'),
  getCurrentStatus: () => ipcRenderer.invoke('main:getCurrentStatus') as Promise<string>,
  doConnect: (gatewayUrl: string, displayName: string) =>
    ipcRenderer.invoke('main:doConnect', gatewayUrl, displayName),
  doDisconnect: () => ipcRenderer.invoke('main:doDisconnect'),
  getLaunchAtStartup: () => ipcRenderer.invoke('main:getLaunchAtStartup'),
  setLaunchAtStartup: (enabled: boolean) => ipcRenderer.invoke('main:setLaunchAtStartup', enabled),
  onStatusUpdate: (cb: (status: string) => void) => {
    ipcRenderer.on('main:statusUpdate', (_ev, status: string) => cb(status));
  },
  notifyReady: () => ipcRenderer.send('main:rendererReady'),
  windowMinimize: () => ipcRenderer.send('main:windowMinimize'),
  windowClose: () => ipcRenderer.send('main:windowClose'),
});

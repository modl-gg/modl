import { AssetLoadError } from '../AssetLibrary.ts';
import { loadBundles } from './loadBundles.ts';
import { type LoaderMessage, parseLoadRequest } from './workerProtocol.ts';

let lastProgressAt = 0;

function send(message: LoaderMessage, transfer: Transferable[] = []): void {
  postMessage(message, { transfer });
}

async function run(version: string): Promise<void> {
  try {
    const bundles = await loadBundles(version, async (url, init) => fetch(url, init), (loaded, total) => {
      const now = performance.now();
      if (now - lastProgressAt < 100 && loaded < total) return;
      lastProgressAt = now;
      send({ type: 'progress', loaded, total });
    });
    send({ type: 'done', bundles }, bundles.map((entry) => entry.bytes));
  } catch (error) {
    if (error instanceof AssetLoadError) {
      send({ type: 'failure', kind: error.kind, message: error.message });
    } else {
      send({ type: 'failure', kind: 'corrupt', message: error instanceof Error ? error.message : 'asset load failed' });
    }
  }
}

addEventListener('message', (event: MessageEvent<unknown>) => {
  const request = parseLoadRequest(event.data);
  if (request === null) return;
  void run(request.version);
});

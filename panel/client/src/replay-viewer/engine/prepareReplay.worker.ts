import { ReplayFormatError } from '../format/ReplayModel.ts';
import { preparedTransfers } from './PreparedReplay.ts';
import { type PreparationResponse, parsePreparationRequest } from './preparationProtocol.ts';
import { prepareReplayData } from './prepareReplay.ts';

function send(message: PreparationResponse, transfer: Transferable[] = []): void {
  postMessage(message, { transfer });
}

async function prepare(buffer: ArrayBuffer): Promise<void> {
  try {
    const replay = await prepareReplayData(buffer);
    send({ type: 'prepared', replay }, preparedTransfers(replay));
  } catch (error) {
    send({ type: 'failure', format: error instanceof ReplayFormatError, message: error instanceof Error ? error.message : 'replay preparation failed' });
  }
}

addEventListener('message', (event: MessageEvent<unknown>) => {
  const request = parsePreparationRequest(event.data);
  if (request !== null) void prepare(request.buffer);
});

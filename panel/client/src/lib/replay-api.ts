import { create } from '@bufbuild/protobuf';
import { ReplayLabelResponseSchema, SubmitReplayLabelsRequestSchema } from '@proto/modl/v1/replay_pb.ts';
import { protoSend } from './proto-fetch';

export interface ReplayLabelInput {
  readonly uuid: string;
  readonly playerName: string;
  readonly verdict: string;
  readonly confidence: number;
  readonly notes: string;
  readonly cheats: readonly {
    readonly type: string;
    readonly timeRanges: readonly { readonly startMs: number; readonly endMs: number }[];
  }[];
}

export async function submitReplayLabels(replayId: string, labels: readonly ReplayLabelInput[]): Promise<void> {
  const request = create(SubmitReplayLabelsRequestSchema, {
    players: labels.map((label) => ({
      uuid: label.uuid,
      playerName: label.playerName,
      verdict: label.verdict,
      confidence: label.confidence,
      notes: label.notes,
      cheats: label.cheats.map((cheat) => ({
        type: cheat.type,
        timeRanges: cheat.timeRanges.map((range) => ({ startMs: BigInt(Math.round(range.startMs)), endMs: BigInt(Math.round(range.endMs)) })),
      })),
    })),
  });
  await protoSend('POST', `/v1/panel/replays/${encodeURIComponent(replayId)}/label`, SubmitReplayLabelsRequestSchema, request, ReplayLabelResponseSchema);
}

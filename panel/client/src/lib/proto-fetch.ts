import { fromJsonString, toJson, type DescMessage, type MessageShape } from '@bufbuild/protobuf';
import { apiFetch, type ApiRequestOptions, type RequestMethod } from '@/lib/api';
import { ApiHttpError } from '@/lib/http-error';

type MutateMethod = Exclude<RequestMethod, 'GET'>;

interface ProtoFetchInit {
  method?: RequestMethod;
  body?: unknown;
  signal?: AbortSignal;
}

type ProtoSendOptions = Pick<ApiRequestOptions, 'rateLimitFeedback'>;

const HTTP_NOT_FOUND = 404;

export const READ_OPTS = { ignoreUnknownFields: true } as const;

export class ProtoHttpError extends ApiHttpError {
  constructor(status: number, statusText: string, bodyText: string) {
    super(status, statusText, bodyText);
    this.name = 'ProtoHttpError';
  }
}

export async function protoHttpErrorOf(res: Response): Promise<ProtoHttpError> {
  return new ProtoHttpError(res.status, res.statusText, await res.text().catch(() => ''));
}

export async function decodeProtoResponse<Desc extends DescMessage>(
  schema: Desc,
  res: Response,
): Promise<MessageShape<Desc>> {
  return fromJsonString(schema, await res.text(), READ_OPTS);
}

async function sendProtoRequest(path: string, init: ProtoFetchInit | undefined): Promise<Response> {
  return apiFetch(path, { method: init?.method ?? 'GET', body: init?.body, signal: init?.signal ?? null });
}

export async function protoFetch<Desc extends DescMessage>(
  schema: Desc,
  path: string,
  init?: ProtoFetchInit,
): Promise<MessageShape<Desc>> {
  const res = await sendProtoRequest(path, init);
  if (!res.ok) {
    throw await protoHttpErrorOf(res);
  }
  return decodeProtoResponse(schema, res);
}

export async function protoFetchOrNull<Desc extends DescMessage>(
  schema: Desc,
  path: string,
  init?: ProtoFetchInit,
): Promise<MessageShape<Desc> | null> {
  const res = await sendProtoRequest(path, init);
  if (res.status === HTTP_NOT_FOUND) return null;
  if (!res.ok) {
    throw await protoHttpErrorOf(res);
  }
  return decodeProtoResponse(schema, res);
}

export async function protoSend<ReqDesc extends DescMessage, ResDesc extends DescMessage>(
  method: MutateMethod,
  path: string,
  reqSchema: ReqDesc,
  req: MessageShape<ReqDesc>,
  resSchema: ResDesc,
  options: ProtoSendOptions = {},
): Promise<MessageShape<ResDesc>> {
  const body = toJson(reqSchema, req);
  const res = await apiFetch(path, { ...options, method, body });
  if (!res.ok) {
    throw await protoHttpErrorOf(res);
  }
  return decodeProtoResponse(resSchema, res);
}

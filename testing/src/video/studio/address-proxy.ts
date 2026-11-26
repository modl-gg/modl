import { createServer, request, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { connect, type Socket } from "node:net";

export interface AddressRoute {
  publicOrigin: string;
  targetPort: number;
}

const LOOPBACK = "127.0.0.1";

function hostOf(origin: string): string {
  return new URL(origin).host;
}

export class AddressProxy {
  private readonly routes = new Map<string, number>();
  private readonly server: Server;
  private readonly sockets = new Set<Socket>();

  constructor() {
    this.server = createServer((incoming, outgoing) => {
      this.forward(incoming, outgoing);
    });
    this.server.on("connect", (incoming: IncomingMessage, client: Socket, head: Buffer) => {
      this.tunnel(incoming, client, head);
    });
    this.server.on("connection", (socket: Socket) => {
      this.sockets.add(socket);
      socket.once("close", () => this.sockets.delete(socket));
    });
  }

  route(route: AddressRoute): void {
    this.routes.set(hostOf(route.publicOrigin), route.targetPort);
  }

  async listen(): Promise<string> {
    await new Promise<void>((resolve, reject) => {
      this.server.once("error", reject);
      this.server.listen(0, LOOPBACK, () => {
        resolve();
      });
    });
    const address = this.server.address();
    if (address === null || typeof address === "string") throw new Error("the address proxy did not get a port");
    return `http://${LOOPBACK}:${address.port}`;
  }

  async close(): Promise<void> {
    for (const socket of this.sockets) socket.destroy();
    await new Promise<void>((resolve) => {
      this.server.close(() => {
        resolve();
      });
    });
  }

  routedAuthorities(): string[] {
    return [...this.routes.keys()];
  }

  private targetFor(authority: string): number | null {
    return this.routes.get(authority) ?? null;
  }

  private forward(incoming: IncomingMessage, outgoing: ServerResponse): void {
    const raw = incoming.url ?? "";
    const port = URL.canParse(raw) ? this.targetFor(new URL(raw).host) : null;
    if (port === null) {
      outgoing.writeHead(403).end();
      return;
    }
    const url = new URL(raw);
    const upstream = request(
      { host: LOOPBACK, port, method: incoming.method, path: `${url.pathname}${url.search}`, headers: incoming.headers },
      (response) => {
        outgoing.writeHead(response.statusCode ?? 502, response.headers);
        response.pipe(outgoing);
      },
    );
    upstream.on("error", () => {
      if (!outgoing.headersSent) outgoing.writeHead(502);
      outgoing.end();
    });
    incoming.pipe(upstream);
  }

  private tunnel(incoming: IncomingMessage, client: Socket, head: Buffer): void {
    const port = this.targetFor(incoming.url ?? "");
    if (port === null) {
      client.end("HTTP/1.1 403 Forbidden\r\n\r\n");
      return;
    }
    const upstream = connect(port, LOOPBACK, () => {
      client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      upstream.write(head);
      upstream.pipe(client);
      client.pipe(upstream);
    });
    this.sockets.add(upstream);
    upstream.once("close", () => this.sockets.delete(upstream));
    upstream.on("error", () => client.destroy());
    client.on("error", () => upstream.destroy());
  }
}

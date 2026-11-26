import prismarineChat from "prismarine-chat";

export class ChatRenderer {
  private readonly ChatMessage: ReturnType<typeof prismarineChat>;

  constructor(version: string) {
    this.ChatMessage = prismarineChat(version);
  }

  html(componentJson: string): string {
    return this.ChatMessage.fromNotch(componentJson).toHTML();
  }
}

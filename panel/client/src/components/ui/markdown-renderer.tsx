import { createContext, use, useMemo, type ComponentProps, type JSX, type ReactNode } from 'react';
import ReactMarkdown, { type Components, type ExtraProps } from 'react-markdown';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import { cn } from '@/lib/utils';
import { ClickablePlayer } from './clickable-player';
import { keyByContent } from './stable-keys';
import { useMediaTrustPolicy } from '@/hooks/use-media-upload';
import { getTrustedEvidenceMediaType, type MediaTrustPolicy } from '@/utils/evidence-utils';

interface MarkdownRendererProps {
  content: string;
  className?: string;
  allowHtml?: boolean;
  disableClickablePlayers?: boolean;
}

const CHAT_MESSAGES_HEADER = '**Chat Messages:**';
const MARKDOWN_SPECIAL_CHARACTER = /[\\`*_{}[\]()#+\-.!|<>~]/g;
const MARKDOWN_ESCAPED_CHARACTER = /\\([\\`*_{}[\]()#+\-.!|<>~])/g;
const PLAYER_MARKER_LABEL = /^\[PLAYER:(.*)\]$/;

const chatMessageSchema = z.object({
  username: z.string().regex(/^\.?[A-Za-z0-9_ ]{1,32}$/),
  message: z.string().min(1),
  timestamp: z.union([z.string(), z.number()]).optional().catch(undefined),
});

type ChatMessage = z.infer<typeof chatMessageSchema>;

function parseChatMessageLine(trimmedLine: string): ChatMessage | null {
  if (!trimmedLine.startsWith('{') || !trimmedLine.includes('"username"') || !trimmedLine.includes('"message"')) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(trimmedLine);
    const result = chatMessageSchema.safeParse(parsed);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

function escapeMarkdownText(text: string): string {
  return text.replace(/[\r\n]+/g, ' ').replace(MARKDOWN_SPECIAL_CHARACTER, '\\$&');
}

function unescapeMarkdownText(text: string): string {
  return text.replace(MARKDOWN_ESCAPED_CHARACTER, '$1');
}

function formatChatTimestamp(timestamp: string | number | undefined, unknownTimeLabel: string): string {
  if (timestamp === undefined || timestamp === '') {
    return unknownTimeLabel;
  }
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime()) ? unknownTimeLabel : date.toLocaleString();
}

function formatChatMessageLine(line: string, unknownTimeLabel: string): string {
  const message = parseChatMessageLine(line.trim());
  if (!message) {
    return `  \n${line}`;
  }
  const timestamp = formatChatTimestamp(message.timestamp, unknownTimeLabel);
  return `  \n[${timestamp}] **${message.username}**: ${escapeMarkdownText(message.message)}`;
}

function formatChatMessagesSection(content: string, unknownTimeLabel: string): string {
  const chatHeaderIndex = content.indexOf(CHAT_MESSAGES_HEADER);
  if (chatHeaderIndex === -1) {
    return content;
  }
  const beforeChat = content.substring(0, chatHeaderIndex);
  const afterChatHeader = content.substring(chatHeaderIndex + CHAT_MESSAGES_HEADER.length);
  const chatSectionEnd = afterChatHeader.search(/\n\*\*[^*]+\*\*:|$/);
  const chatSection = afterChatHeader.substring(0, chatSectionEnd);
  const afterChat = afterChatHeader.substring(chatSectionEnd);

  const formattedMessages = chatSection
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => formatChatMessageLine(line, unknownTimeLabel))
    .join('');

  return beforeChat + CHAT_MESSAGES_HEADER + formattedMessages + afterChat;
}

function isPlayerUsernameLabel(username: string): boolean {
  return username !== '' && !username.includes(':') && !username.includes('\n') && !username.includes('Chat Messages');
}

function markPlayerUsernames(content: string): string {
  return content.replace(/\*\*([^*\n]+)\*\*:/g, (match, username: string) =>
    isPlayerUsernameLabel(username) ? `**[PLAYER:${username}]**:` : match
  );
}

function processMarkdownContent(content: string, disableClickablePlayers: boolean, unknownTimeLabel: string): string {
  if (!content) return '';
  const formatted = formatChatMessagesSection(content, unknownTimeLabel);
  return disableClickablePlayers ? formatted : markPlayerUsernames(formatted);
}

function plainText(node: ReactNode): string {
  if (typeof node === 'string') return node;
  if (typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map((child: ReactNode) => plainText(child)).join('');
  return '';
}

function PlayerName({ username, bold = false }: { username: string; bold?: boolean }) {
  const className = 'text-primary cursor-pointer hover:underline';
  return (
    <ClickablePlayer playerText={username} variant="text">
      {bold ? <strong className={className}>{username}</strong> : <span className={className}>{username}</span>}
    </ClickablePlayer>
  );
}

const MediaTrustPolicyContext = createContext<MediaTrustPolicy | null>(null);

function useTrustedMediaKind(href: string | undefined): 'image' | 'video' | null {
  const mediaTrustPolicy = use(MediaTrustPolicyContext);
  if (!href || mediaTrustPolicy === null) return null;
  const mediaType = getTrustedEvidenceMediaType(href, mediaTrustPolicy);
  return mediaType === 'image' || mediaType === 'video' ? mediaType : null;
}

type MarkdownElementProps<Tag extends keyof JSX.IntrinsicElements> = ComponentProps<Tag> & ExtraProps;

function PlayerAwareStrong({ children, node: _node, ...props }: MarkdownElementProps<'strong'>) {
  const playerMatch = PLAYER_MARKER_LABEL.exec(plainText(children));
  if (playerMatch) {
    return <PlayerName username={playerMatch[1] ?? ''} bold />;
  }
  return <strong {...props}>{children}</strong>;
}

function MediaFooterLink({ href, children }: { href: string | undefined; children: ReactNode }) {
  return (
    <div className="text-xs text-muted-foreground mt-1">
      <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">
        {children}
      </a>
    </div>
  );
}

function MarkdownLink({ href, children, node: _node, ...props }: MarkdownElementProps<'a'>) {
  const { t } = useTranslation();
  const mediaKind = useTrustedMediaKind(href);

  switch (mediaKind) {
    case 'image':
      return (
        <div className="my-4">
          <img
            src={href}
            alt={plainText(children) || t('markdown.media')}
            className="max-w-full h-auto rounded border"
            style={{ maxWidth: '500px' }}
          />
          <MediaFooterLink href={href}>{t('markdown.viewFullSize')}</MediaFooterLink>
        </div>
      );
    case 'video':
      return (
        <div className="my-4">
          <video src={href} controls className="max-w-full h-auto rounded border" style={{ maxWidth: '500px' }} />
          <MediaFooterLink href={href}>{t('markdown.openInNewTab')}</MediaFooterLink>
        </div>
      );
    case null: {
      const isExternal = href?.startsWith('http') === true;
      return (
        <a
          href={href}
          target={isExternal ? '_blank' : undefined}
          rel={isExternal ? 'noopener noreferrer' : undefined}
          {...props}
        >
          {children}
        </a>
      );
    }
  }
}

function MarkdownImage({ src, alt, node: _node, ...props }: MarkdownElementProps<'img'>) {
  const source = typeof src === 'string' ? src : undefined;
  const mediaKind = useTrustedMediaKind(source);

  if (mediaKind === 'image') {
    return (
      <img
        src={source}
        alt={alt ?? ''}
        className="max-w-full h-auto rounded border"
        style={{ maxWidth: '500px' }}
        {...props}
      />
    );
  }

  return (
    <a href={source} target="_blank" rel="noopener noreferrer">
      {alt || source}
    </a>
  );
}

function MarkdownCode({ className, children, node: _node, ...props }: MarkdownElementProps<'code'>) {
  const isBlock = /language-(\w+)/.test(className ?? '');

  if (!isBlock) {
    return (
      <code className="bg-muted px-1 py-0.5 rounded text-sm font-mono" {...props}>
        {children}
      </code>
    );
  }

  return (
    <pre className="bg-muted p-3 rounded border overflow-auto">
      <code className={className} {...props}>
        {children}
      </code>
    </pre>
  );
}

function MarkdownBlockquote({ children, node: _node, ...props }: MarkdownElementProps<'blockquote'>) {
  return (
    <blockquote className="border-l-4 border-border pl-4 py-2 bg-muted/30 rounded-r" {...props}>
      {children}
    </blockquote>
  );
}

function MarkdownUnorderedList({ children, node: _node, ...props }: MarkdownElementProps<'ul'>) {
  return (
    <ul className="list-disc list-inside space-y-1" {...props}>
      {children}
    </ul>
  );
}

function MarkdownOrderedList({ children, node: _node, ...props }: MarkdownElementProps<'ol'>) {
  return (
    <ol className="list-decimal list-inside space-y-1" {...props}>
      {children}
    </ol>
  );
}

const plainMarkdownComponents: Components = {
  a: MarkdownLink,
  img: MarkdownImage,
  code: MarkdownCode,
  blockquote: MarkdownBlockquote,
  ul: MarkdownUnorderedList,
  ol: MarkdownOrderedList,
};

const playerAwareMarkdownComponents: Components = {
  ...plainMarkdownComponents,
  strong: PlayerAwareStrong,
};

const STRUCTURED_CONTENT_PATTERN = /\*\*[^*]+\*\*:\s*\n(•[^\n]*\n?)+/;
const BOLD_LABEL_PATTERN = /^\*\*([^*]+)\*\*:/;
const PLAYER_TOKEN_PATTERN = /\*\*\[PLAYER:([^\]]+)\]\*\*/g;

function StructuredLineText({ line, disableClickablePlayers }: { line: string; disableClickablePlayers: boolean }) {
  if (disableClickablePlayers) {
    return unescapeMarkdownText(line.replace(PLAYER_TOKEN_PATTERN, '$1'));
  }
  const parts: ReactNode[] = [];
  let lastIndex = 0;
  for (const match of line.matchAll(PLAYER_TOKEN_PATTERN)) {
    if (match.index > lastIndex) {
      parts.push(unescapeMarkdownText(line.slice(lastIndex, match.index)));
    }
    parts.push(<PlayerName key={match.index} username={match[1] ?? ''} />);
    lastIndex = match.index + match[0].length;
  }
  if (lastIndex < line.length) {
    parts.push(unescapeMarkdownText(line.slice(lastIndex)));
  }
  return parts;
}

function StructuredLine({ line, disableClickablePlayers }: { line: string; disableClickablePlayers: boolean }) {
  const boldLabel = BOLD_LABEL_PATTERN.exec(line);
  if (boldLabel) {
    const label = boldLabel[1] ?? '';
    const remainder = line.slice(boldLabel[0].length);
    const playerMatch = PLAYER_MARKER_LABEL.exec(label);
    return (
      <div className="font-semibold mt-2 first:mt-0">
        {playerMatch && !disableClickablePlayers ? <PlayerName username={playerMatch[1] ?? ''} /> : label}
        :
        {remainder.trim() !== '' && (
          <span className="font-normal">
            <StructuredLineText line={remainder} disableClickablePlayers={disableClickablePlayers} />
          </span>
        )}
      </div>
    );
  }
  if (line.startsWith('• ')) {
    return (
      <div className="ml-4">
        <StructuredLineText line={line} disableClickablePlayers={disableClickablePlayers} />
      </div>
    );
  }
  if (line.trim()) {
    return (
      <div>
        <StructuredLineText line={line} disableClickablePlayers={disableClickablePlayers} />
      </div>
    );
  }
  return <div className="h-2" />;
}

const MarkdownRenderer = ({ content, className, allowHtml = false, disableClickablePlayers = false }: MarkdownRendererProps) => {
  const { t } = useTranslation();
  const mediaTrustPolicy = useMediaTrustPolicy();
  const unknownTimeLabel = t('markdown.unknownTime');

  const processedContent = useMemo(
    () => processMarkdownContent(content, disableClickablePlayers, unknownTimeLabel),
    [content, disableClickablePlayers, unknownTimeLabel]
  );

  const structuredLines = useMemo(
    () => (STRUCTURED_CONTENT_PATTERN.test(processedContent) ? keyByContent(processedContent.split('\n'), (line) => line) : null),
    [processedContent]
  );

  if (content === '') {
    return <div className={className}></div>;
  }

  if (structuredLines) {
    return (
      <div className={cn(
        "text-sm whitespace-pre-wrap wrap-break-word",
        className
      )}>
        {structuredLines.map(({ key, item }) => (
          <StructuredLine key={key} line={item} disableClickablePlayers={disableClickablePlayers} />
        ))}
      </div>
    );
  }

  return (
    <div className={cn(
      "prose prose-sm max-w-none",
      "prose-headings:text-foreground prose-p:text-foreground prose-strong:text-foreground",
      "prose-code:text-foreground prose-code:bg-muted prose-code:px-1 prose-code:py-0.5 prose-code:rounded",
      "prose-pre:bg-muted prose-pre:text-foreground prose-pre:border prose-pre:border-border",
      "prose-blockquote:text-muted-foreground prose-blockquote:border-l-border",
      "prose-ul:text-foreground prose-ol:text-foreground prose-li:text-foreground",
      "prose-a:text-primary prose-a:no-underline prose-a:hover:underline",
      "prose-hr:border-border",
      className
    )}>
      <MediaTrustPolicyContext value={mediaTrustPolicy}>
        <ReactMarkdown
          skipHtml={!allowHtml}
          components={disableClickablePlayers ? plainMarkdownComponents : playerAwareMarkdownComponents}
        >
          {processedContent}
        </ReactMarkdown>
      </MediaTrustPolicyContext>
    </div>
  );
};

export default MarkdownRenderer;

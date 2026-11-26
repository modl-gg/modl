import { useTranslation } from 'react-i18next';
import { FooterLinkIcon } from '@/components/ui/footer-link-icon';
import { usePublicHomepage, type FooterLink } from '@/hooks/data/homepage-settings';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { cn, safeExternalHref } from '@/lib/utils';

interface SafeFooterLink extends FooterLink {
  key: string;
  href: string;
}

function safeLinks(links: readonly FooterLink[]): SafeFooterLink[] {
  const seen = new Set<string>();
  return links.flatMap((link) => {
    const href = safeExternalHref(link.url);
    const key = `${link.kind} ${link.label} ${href ?? ''}`;
    if (href === undefined || href.startsWith('/') || seen.has(key)) return [];
    seen.add(key);
    return [{ ...link, key, href }];
  });
}

export function HomeFooter() {
  const { t } = useTranslation();
  const serverName = usePublicSettings().data?.serverDisplayName ?? '';
  const links = safeLinks(usePublicHomepage().data?.footerLinks ?? []);
  if (links.length === 0) return null;

  return (
    <div className="mx-auto w-full max-w-[960px] px-4 pt-12">
      <div className={cn('flex flex-col-reverse items-center gap-4 border-t border-border/60 pt-6 sm:flex-row', serverName === '' ? 'justify-center' : 'sm:justify-between')}>
        {serverName !== '' && <p data-modl="footer-name" className="text-sm text-muted-foreground">{serverName}</p>}
        <nav aria-label={t('home.footerLinks')}>
          <ul className="flex flex-wrap items-center justify-center gap-x-6 gap-y-2">
            {links.map((link) => (
              <li key={link.key} className="flex">
                <a
                  data-modl="footer-link"
                  href={link.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-2 rounded-sm text-sm text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <FooterLinkIcon kind={link.kind} />
                  {link.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
      </div>
    </div>
  );
}

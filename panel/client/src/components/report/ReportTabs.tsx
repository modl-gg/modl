import type { ReactNode } from 'react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

interface ReportTab<T extends string> {
  value: T;
  label: string;
}

interface ReportTabsProps<T extends string> {
  tabs: readonly ReportTab<T>[];
  value: T;
  onChange: (value: T) => void;
  label: string;
  children: ReactNode;
}

export function ReportTabs<T extends string>({ tabs, value, onChange, label, children }: ReportTabsProps<T>) {
  const select = (next: string) => {
    const tab = tabs.find((candidate) => candidate.value === next);
    if (tab !== undefined) onChange(tab.value);
  };

  return (
    <Tabs value={value} onValueChange={select} className="space-y-8">
      <TabsList
        aria-label={label}
        className="flex h-auto w-full justify-start gap-6 overflow-x-auto rounded-none border-b bg-transparent p-0"
      >
        {tabs.map((tab) => (
          <TabsTrigger
            key={tab.value}
            value={tab.value}
            className="-mb-px rounded-none border-b-2 border-transparent bg-transparent px-0 pb-2 pt-0 font-normal text-muted-foreground shadow-none ring-offset-0 hover:bg-transparent focus-visible:ring-0 focus-visible:border-foreground/40 focus-visible:text-foreground hover:text-foreground data-[state=active]:border-foreground data-[state=active]:bg-transparent data-[state=active]:font-medium data-[state=active]:text-foreground data-[state=active]:shadow-none"
          >
            {tab.label}
          </TabsTrigger>
        ))}
      </TabsList>
      <TabsContent value={value} className="mt-0">
        {children}
      </TabsContent>
    </Tabs>
  );
}

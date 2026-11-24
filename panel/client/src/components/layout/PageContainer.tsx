import { type ReactNode } from "react";
import { PAGE_GUTTER_X } from "./page-gutter";

interface PageContainerProps {
  children: ReactNode;
  title?: string;
}

const PageContainer = ({ children, title }: PageContainerProps) => {
  // Responsive container that adapts to mobile devices
  return (
    <section className={`transition-all duration-300 ${PAGE_GUTTER_X} py-4 md:my-6 md:py-8`}>
      {title && (
        <div className="flex justify-between items-center mb-4">
          <h2 className="text-xl font-semibold">{title}</h2>
        </div>
      )}
      {children}
    </section>
  );
};

export default PageContainer;
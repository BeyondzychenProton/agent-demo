import { createContext, memo, useContext, type ReactNode } from "react";

import { cn } from "@/lib/utils";

const DecorativeCopy = createContext<boolean | undefined>(undefined);

/**
 * Port of deepseek-harness `TextShimmer`: one shared highlight sweep across a process row.
 */
export const TextShimmer = memo(function TextShimmer({
  children,
  active = false,
  className,
  contentClassName,
}: {
  children: ReactNode;
  active?: boolean;
  className?: string;
  contentClassName?: string;
}) {
  const decorative = useContext(DecorativeCopy);
  if (decorative !== undefined) {
    return <span className={cn(className)}>{children}</span>;
  }

  return (
    <span className={cn("dsh-shimmer", className)} data-shimmer={active || undefined}>
      <DecorativeCopy.Provider value={false}>
        <span className={cn("dsh-shimmer-content", contentClassName)}>{children}</span>
      </DecorativeCopy.Provider>
      {active ? (
        <span className="dsh-shimmer-decoration" aria-hidden>
          <span className="dsh-shimmer-sweep">
            <DecorativeCopy.Provider value={true}>
              <span className={cn("dsh-shimmer-content", "dsh-shimmer-highlight", contentClassName)}>
                {children}
              </span>
            </DecorativeCopy.Provider>
          </span>
        </span>
      ) : null}
    </span>
  );
});
